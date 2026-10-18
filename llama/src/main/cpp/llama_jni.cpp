// NekoTranslate JNI bridge for llama.cpp.
//
// Only the public C API in llama.h is used (no common/ helpers), so this file keeps compiling across
// llama.cpp updates as long as llama.h stays source compatible.
//
// Threading: every call except nativeCancel() must come from the same thread (the Kotlin side runs
// all of them on one dedicated engine thread). nativeCancel() only flips an atomic flag.
//
// Strings cross the boundary as UTF-8 byte arrays, not jstring, because JNI's "modified UTF-8"
// mangles 4-byte characters (emoji, rare CJK) in both directions.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "NekoTranslate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ------------------------------------------------------------------------------------------------
// Session state
// ------------------------------------------------------------------------------------------------

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    std::atomic<bool> cancel{false};
    int32_t n_batch = 512;
};

bool g_backend_ready = false;

bool abort_cb(void * data) {
    auto * s = static_cast<Session *>(data);
    return s != nullptr && s->cancel.load(std::memory_order_relaxed);
}

void log_cb(ggml_log_level level, const char * text, void * /*user*/) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

// jlong is 64-bit on every ABI; go through intptr_t so 32-bit (armeabi-v7a) builds stay exact.
Session * as_session(jlong handle) { return reinterpret_cast<Session *>(static_cast<intptr_t>(handle)); }

// ------------------------------------------------------------------------------------------------
// UTF-8 helpers
// ------------------------------------------------------------------------------------------------

std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t complete_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    // Walk back at most 3 bytes looking for the lead byte of the last code point.
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        --i; ++back;
        const auto c = static_cast<unsigned char>(s[i]);
        if ((c & 0xC0) != 0x80) {          // lead byte (or ASCII)
            size_t need = 1;
            if      ((c & 0x80) == 0x00) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            else return n;                  // invalid lead byte: flush everything
            return (n - i >= need) ? n : i;
        }
    }
    return n; // only continuation bytes: malformed, flush
}

std::string meta_str(const llama_model * model, const char * key) {
    std::vector<char> buf(256);
    int32_t len = llama_model_meta_val_str(model, key, buf.data(), buf.size());
    if (len < 0) return {};
    if (static_cast<size_t>(len) >= buf.size()) {
        buf.resize(static_cast<size_t>(len) + 1);
        len = llama_model_meta_val_str(model, key, buf.data(), buf.size());
        if (len < 0) return {};
    }
    return std::string(buf.data(), static_cast<size_t>(len));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, parse_special);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    int32_t got = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), n, add_special, parse_special);
    if (got < 0) return {};
    tokens.resize(static_cast<size_t>(got));
    return tokens;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
    return n >= 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
}

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

} // namespace

// ================================================================================================
// JNI: com.nekohome.translate.llama.LlamaNative
// ================================================================================================

extern "C" {

JNIEXPORT void JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeInitBackend(JNIEnv * env, jobject, jstring jlib_dir) {
    if (g_backend_ready) return;
    llama_log_set(log_cb, nullptr);
#if LT_BACKEND_DL
    // CPU backend variants (libggml-cpu-android_armv*.so) live in the app's nativeLibraryDir;
    // ggml scores each against the running CPU's features and loads the best one.
    const char * dir = env->GetStringUTFChars(jlib_dir, nullptr);
    LOGI("Loading ggml backends from %s", dir);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jlib_dir, dir);
#else
    (void) env; (void) jlib_dir;
#endif
    llama_backend_init();
    g_backend_ready = true;
    LOGI("llama backend ready: %s", llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::string info = llama_print_system_info();
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        info += i == 0 ? " | backends: " : ", ";
        info += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    return env->NewStringUTF(info.c_str()); // ASCII only
}

// Returns a session handle, or 0 on failure.
// `fd` >= 0 loads from an already-open file descriptor (dup'ed internally) instead of `path`.
// Paths such as /proc/self/fd/N are also accepted through `path`.
JNIEXPORT jlong JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeLoad(
        JNIEnv * env, jobject,
        jbyteArray jpath, jint fd,
        jint n_ctx, jint n_threads, jint n_batch,
        jboolean use_mmap) {
    if (!g_backend_ready) {
        LOGE("nativeLoad called before nativeInitBackend");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;            // CPU backend only for now
    // b11707 replaced use_mmap/use_mlock with load_mode.
    mparams.load_mode    = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model * model = nullptr;
    if (fd >= 0) {
        int dup_fd = dup(fd);
        FILE * f = dup_fd >= 0 ? fdopen(dup_fd, "rb") : nullptr;
        if (f == nullptr) {
            if (dup_fd >= 0) close(dup_fd);
            LOGE("cannot open fd %d", fd);
            return 0;
        }
        model = llama_model_load_from_file_ptr(f, mparams);
        fclose(f);
    } else {
        const std::string path = bytes_to_string(env, jpath);
        LOGI("Loading model: %s", path.c_str());
        model = llama_model_load_from_file(path.c_str(), mparams);
    }
    if (model == nullptr) {
        LOGE("llama_model_load failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(std::max(256, static_cast<int>(n_ctx)));
    cparams.n_batch         = static_cast<uint32_t>(std::max(32, static_cast<int>(n_batch)));
    cparams.n_ubatch        = cparams.n_batch;
    cparams.n_threads       = std::max(1, static_cast<int>(n_threads));
    cparams.n_threads_batch = cparams.n_threads;
    cparams.no_perf         = false;

    auto * session = new Session();
    session->model   = model;
    session->n_batch = static_cast<int32_t>(cparams.n_batch);
    cparams.abort_callback      = abort_cb;
    cparams.abort_callback_data = session;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        delete session;
        return 0;
    }
    session->ctx = ctx;
    LOGI("Model loaded: n_ctx=%u threads=%d batch=%d", llama_n_ctx(ctx), cparams.n_threads, cparams.n_batch);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT void JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session * s = as_session(handle);
    if (s != nullptr) s->cancel.store(true, std::memory_order_relaxed);
}

// Metadata value as UTF-8 bytes, or null when the key is absent.
JNIEXPORT jbyteArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeMeta(JNIEnv * env, jobject, jlong handle, jbyteArray jkey) {
    Session * s = as_session(handle);
    if (s == nullptr) return nullptr;
    const std::string key = bytes_to_string(env, jkey);
    std::vector<char> probe(1);
    if (llama_model_meta_val_str(s->model, key.c_str(), probe.data(), probe.size()) < 0) return nullptr;
    return string_to_bytes(env, meta_str(s->model, key.c_str()));
}

// Summary used by the UI: alternating key/value UTF-8 strings packed as "key\0value\0...".
JNIEXPORT jbyteArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeModelInfo(JNIEnv * env, jobject, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return nullptr;
    const llama_model * m = s->model;

    char desc[256] = {0};
    llama_model_desc(m, desc, sizeof(desc));
    const char * tmpl = llama_model_chat_template(m, nullptr);

    std::vector<std::pair<std::string, std::string>> kv = {
        {"general.architecture", meta_str(m, "general.architecture")},
        {"general.name",         meta_str(m, "general.name")},
        {"general.file_type",    meta_str(m, "general.file_type")},
        {"ftype_name",           llama_ftype_name(llama_model_ftype(m))},
        {"desc",                 desc},
        {"n_params",             std::to_string(llama_model_n_params(m))},
        {"size_bytes",           std::to_string(llama_model_size(m))},
        {"n_ctx_train",          std::to_string(llama_model_n_ctx_train(m))},
        {"n_ctx",                std::to_string(llama_n_ctx(s->ctx))},
        {"chat_template",        tmpl ? tmpl : ""},
    };
    std::string packed;
    for (auto & p : kv) {
        packed += p.first;  packed.push_back('\0');
        packed += p.second; packed.push_back('\0');
    }
    return string_to_bytes(env, packed);
}

// Applies a chat template (the GGUF's own when `jtmpl` is null) via llama_chat_apply_template.
// Note: llama_chat_apply_template does not run Jinja; it pattern-matches known template families.
// Returns null if the template is not supported.
JNIEXPORT jbyteArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv * env, jobject, jlong handle,
        jbyteArray jtmpl, jobjectArray jroles, jobjectArray jcontents, jboolean add_assistant) {
    Session * s = as_session(handle);
    std::string tmpl;
    const char * tmpl_ptr = nullptr;
    if (jtmpl != nullptr) {
        tmpl = bytes_to_string(env, jtmpl);
        tmpl_ptr = tmpl.c_str();
    } else if (s != nullptr) {
        tmpl_ptr = llama_model_chat_template(s->model, nullptr);
    }
    if (tmpl_ptr == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(static_cast<size_t>(n)), contents(static_cast<size_t>(n));
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        roles[i]    = bytes_to_string(env, r);
        contents[i] = bytes_to_string(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
        total += roles[i].size() + contents[i].size();
    }
    for (jsize i = 0; i < n; ++i) msgs[i] = { roles[i].c_str(), contents[i].c_str() };

    std::vector<char> buf(std::max<size_t>(1024, total * 2 + 512));
    int32_t len = llama_chat_apply_template(tmpl_ptr, msgs.data(), msgs.size(), add_assistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0) return nullptr;
    if (static_cast<size_t>(len) > buf.size()) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl_ptr, msgs.data(), msgs.size(), add_assistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
        if (len < 0) return nullptr;
    }
    return string_to_bytes(env, std::string(buf.data(), static_cast<size_t>(len)));
}

// Runs one completion over an already formatted prompt (KV cache is cleared first).
// Streams UTF-8 chunks to callback.onToken(byte[]): boolean (return false to stop).
// Returns double[]{status, n_prompt, prompt_ms, n_gen, gen_ms, stop_reason}
//   status: 0 ok, 1 cancelled, -1 bad handle, -2 tokenize failed, -3 prompt too long, -4 decode failed
//   stop_reason: 0 eog, 1 max tokens, 2 context full, 3 cancelled/callback, 4 error
JNIEXPORT jdoubleArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong handle,
        jbyteArray jprompt, jint max_tokens,
        jfloat temperature, jfloat top_p, jint top_k, jfloat min_p,
        jfloat repeat_penalty, jint repeat_last_n, jint seed,
        jobject callback) {
    auto result = [&](double status, double n_prompt, double prompt_ms, double n_gen, double gen_ms, double reason) {
        jdoubleArray arr = env->NewDoubleArray(6);
        const jdouble v[6] = {status, n_prompt, prompt_ms, n_gen, gen_ms, reason};
        env->SetDoubleArrayRegion(arr, 0, 6, v);
        return arr;
    };

    Session * s = as_session(handle);
    if (s == nullptr) return result(-1, 0, 0, 0, 0, 4);
    s->cancel.store(false);

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
    if (on_token == nullptr) return result(-1, 0, 0, 0, 0, 4);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = bytes_to_string(env, jprompt);

    // The prompt is already fully formatted (chat template applied), so special tokens are parsed
    // and BOS is only added when the vocab wants one and the template did not include it.
    std::vector<llama_token> tokens = tokenize(vocab, prompt, /*add_special*/ false, /*parse_special*/ true);
    if (tokens.empty()) return result(-2, 0, 0, 0, 0, 4);
    if (llama_vocab_get_add_bos(vocab)) {
        const llama_token bos = llama_vocab_bos(vocab);
        if (bos != LLAMA_TOKEN_NULL && tokens.front() != bos) tokens.insert(tokens.begin(), bos);
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    const int n_prompt = static_cast<int>(tokens.size());
    if (n_prompt >= n_ctx - 4) {
        LOGW("prompt too long: %d tokens, n_ctx %d", n_prompt, n_ctx);
        return result(-3, n_prompt, 0, 0, 0, 2);
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_perf_context_reset(s->ctx);

    // Sampler chain: penalties -> top-k -> top-p -> min-p -> temperature -> dist (or greedy).
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (repeat_penalty > 0.0f && repeat_penalty != 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), repeat_last_n, repeat_penalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)                    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f)                 llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    // Prompt processing in n_batch chunks.
    const double t_prompt0 = now_ms();
    for (int i = 0; i < n_prompt; i += s->n_batch) {
        const int n = std::min(s->n_batch, n_prompt - i);
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n));
        if (rc != 0) {
            llama_sampler_free(smpl);
            const bool cancelled = s->cancel.load();
            LOGW("prompt decode rc=%d cancelled=%d", rc, cancelled);
            return result(cancelled ? 1 : -4, n_prompt, now_ms() - t_prompt0, 0, 0, cancelled ? 3 : 4);
        }
    }
    const double prompt_ms = now_ms() - t_prompt0;

    // Generation.
    const double t_gen0 = now_ms();
    int n_gen = 0;
    int n_past = n_prompt;
    double status = 0, reason = 1;
    std::string pending;
    llama_token tok = 0;
    const int limit = max_tokens > 0 ? max_tokens : n_ctx;

    while (true) {
        if (s->cancel.load()) { status = 1; reason = 3; break; }
        if (n_gen >= limit)   { reason = 1; break; }
        if (n_past >= n_ctx)  { reason = 2; break; }

        tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) { reason = 0; break; }

        pending += token_to_piece(vocab, tok);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = string_to_bytes(env, pending.substr(0, ready));
            pending.erase(0, ready);
            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) { env->ExceptionClear(); status = 1; reason = 3; break; }
            if (keep_going == JNI_FALSE) { status = 1; reason = 3; break; }
        }

        ++n_gen;
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(&tok, 1));
        ++n_past;
        if (rc != 0) {
            const bool cancelled = s->cancel.load();
            status = cancelled ? 1 : -4;
            reason = cancelled ? 3 : 4;
            break;
        }
    }
    if (!pending.empty() && status == 0) {
        jbyteArray chunk = string_to_bytes(env, pending);
        env->CallBooleanMethod(callback, on_token, chunk);
        env->DeleteLocalRef(chunk);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    const double gen_ms = now_ms() - t_gen0;
    llama_sampler_free(smpl);

    const llama_perf_context_data perf = llama_perf_context(s->ctx);
    LOGI("perf: prompt %d tok %.1f ms (llama: %.1f ms), gen %d tok %.1f ms (llama: %d tok %.1f ms)",
         n_prompt, prompt_ms, perf.t_p_eval_ms, n_gen, gen_ms, perf.n_eval, perf.t_eval_ms);
    return result(status, n_prompt, prompt_ms, n_gen, gen_ms, reason);
}

} // extern "C"
