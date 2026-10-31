// NekoTranslate JNI bridge for llama.cpp.
//
// Only the public C APIs in llama.h and tools/mtmd (mtmd.h, mtmd-helper.h) are used (no common/
// helpers), so this file keeps compiling across llama.cpp updates as long as those stay source
// compatible. mtmd provides image input through a vision projector (mmproj GGUF).
//
// Experimental GPU acceleration (arm64-v8a, LT_OPENCL): ggml's OpenCL backend for Adreno GPUs is a
// separate module (libneko-opencl.so) loaded on demand by nativeGpuProbe(). Models and projectors
// are always loaded with an explicit device list, so the GPU is only used where the caller asks.
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
#include <cmath>
#include <cstdlib>
#include <random>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "NekoTranslate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
// Per-stage timings of image translation (`adb logcat -s NekoImg`).
#define IMG_TAG "NekoImg"
#define LOGIMG(...) __android_log_print(ANDROID_LOG_INFO, IMG_TAG, __VA_ARGS__)

namespace {

// ------------------------------------------------------------------------------------------------
// Session state
// ------------------------------------------------------------------------------------------------

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    mtmd_context  * mtmd  = nullptr;   // vision projector, loaded on demand (nativeMmprojLoad)
    std::atomic<bool> cancel{false};
    bool text_gpu = false;             // text model weights / compute on the GPU (GPU mode "all")
    bool mtmd_gpu = false;             // vision encoder on the GPU
    int32_t n_batch = 512;
    uint32_t eval_nodes = 0;           // node counter for mtmd_eval_cb (engine thread only)
};

// Image translation stages reported to StageListener.onStage (keep in sync with LlamaNative.kt).
constexpr int LT_STAGE_ENCODE   = 1;   // vision encoder running on the image
constexpr int LT_STAGE_PREFILL  = 2;   // image embeddings + prompt going through the text model
constexpr int LT_STAGE_GENERATE = 3;   // prompt done, first token being sampled

bool g_backend_ready = false;
bool g_mtmd_log_ready = false;
std::string g_lib_dir;                     // nativeLibraryDir (backend modules live here)
bool g_gpu_probed = false;
ggml_backend_dev_t g_gpu_dev = nullptr;   // first OpenCL GPU device, if any

// Load modes passed by Kotlin (keep in sync with GpuMode.nativeCode).
constexpr int LT_GPU_OFF     = 0;
constexpr int LT_GPU_ENCODER = 1;          // vision encoder only; text model on the CPU
constexpr int LT_GPU_ALL     = 2;          // vision encoder + text model (prefill and generation)

const char * backend_name(bool gpu) { return gpu ? "GPU" : "CPU"; }

// ggml_backend_sched eval callback installed on the mtmd (vision encoder) scheduler. The CLIP
// encoder has no abort callback, so this is how a stop request interrupts a running image
// encode: ggml asks `ask == true` per graph node whether we want to observe it; saying yes makes
// the scheduler compute up to that node, synchronize and call us again with `ask == false`, where
// returning false aborts the rest of the graph. We only ask for every 32nd node (so the encoder
// still runs in large multi-threaded chunks), or immediately once cancel is set. The encoder
// then returns a partially computed result, which the caller discards after checking `cancel`.
bool mtmd_eval_cb(struct ggml_tensor * /*t*/, bool ask, void * data) {
    auto * s = static_cast<Session *>(data);
    if (s == nullptr) return ask ? false : true;
    const bool cancelled = s->cancel.load(std::memory_order_relaxed);
    if (ask) return cancelled || ((++s->eval_nodes & 31u) == 0);
    return !cancelled;
}

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

jdoubleArray make_result(JNIEnv * env, double status, double n_prompt, double prompt_ms,
                         double n_gen, double gen_ms, double reason) {
    jdoubleArray arr = env->NewDoubleArray(6);
    const jdouble v[6] = {status, n_prompt, prompt_ms, n_gen, gen_ms, reason};
    env->SetDoubleArrayRegion(arr, 0, 6, v);
    return arr;
}

// Sampler chain: penalties -> top-k -> top-p -> min-p -> temperature -> dist (or greedy).
llama_sampler * make_sampler(const llama_vocab * vocab, float temperature, float top_p, int top_k, float min_p,
                             float repeat_penalty, int repeat_last_n, int seed) {
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
    return smpl;
}

struct GenResult {
    double status = 0;   // 0 ok, 1 cancelled, -4 decode failed
    double reason = 1;   // see nativeGenerate
    int    n_gen  = 0;
    double gen_ms = 0;
};

// Token generation after the prompt has been evaluated (logits of the last prompt position are
// available). `n_used` is the number of KV cells already occupied, for the context-full check.
// Each new token is decoded with llama_batch_get_one(), whose position llama.cpp assigns as
// "last position in the KV cache + 1"; that is also right after an image under M-RoPE, because the
// prompt always ends with a text chunk whose positions mtmd set explicitly.
// Streams UTF-8 chunks to callback.onToken(byte[]): boolean (return false to stop).
GenResult generate_loop(JNIEnv * env, Session * s, llama_sampler * smpl, jobject callback, jmethodID on_token,
                        int n_used, int max_tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    GenResult r;
    const double t_gen0 = now_ms();
    std::string pending;
    llama_token tok = 0;
    const int limit = max_tokens > 0 ? max_tokens : n_ctx;

    while (true) {
        if (s->cancel.load())  { r.status = 1; r.reason = 3; break; }
        if (r.n_gen >= limit)  { r.reason = 1; break; }
        if (n_used >= n_ctx)   { r.reason = 2; break; }

        tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) { r.reason = 0; break; }

        pending += token_to_piece(vocab, tok);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = string_to_bytes(env, pending.substr(0, ready));
            pending.erase(0, ready);
            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) { env->ExceptionClear(); r.status = 1; r.reason = 3; break; }
            if (keep_going == JNI_FALSE) { r.status = 1; r.reason = 3; break; }
        }

        ++r.n_gen;
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(&tok, 1));
        ++n_used;
        if (rc != 0) {
            const bool cancelled = s->cancel.load();
            r.status = cancelled ? 1 : -4;
            r.reason = cancelled ? 3 : 4;
            break;
        }
    }
    if (!pending.empty() && r.status == 0) {
        jbyteArray chunk = string_to_bytes(env, pending);
        env->CallBooleanMethod(callback, on_token, chunk);
        env->DeleteLocalRef(chunk);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    r.gen_ms = now_ms() - t_gen0;
    return r;
}

void log_perf(Session * s, const char * what, int n_prompt, double prompt_ms, const GenResult & g) {
    const llama_perf_context_data perf = llama_perf_context(s->ctx);
    LOGI("perf[%s]: prompt %d tok %.1f ms (llama: %.1f ms), gen %d tok %.1f ms (llama: %d tok %.1f ms)",
         what, n_prompt, prompt_ms, perf.t_p_eval_ms, g.n_gen, g.gen_ms, perf.n_eval, perf.t_eval_ms);
}

void free_mtmd(Session * s) {
    if (s != nullptr && s->mtmd != nullptr) {
        mtmd_free(s->mtmd);
        s->mtmd = nullptr;
        LOGI("mmproj freed");
    }
}


// ------------------------------------------------------------------------------------------------
// GPU self-check: quantized MUL_MAT on the GPU vs the CPU backend
// ------------------------------------------------------------------------------------------------

// Runs out = W x for one weight type and batch size on `backend`; returns the output (n_rows x n).
bool run_mul_mat(ggml_backend_t backend, ggml_type type, int64_t k, int64_t m, int64_t n,
                 const std::vector<uint8_t> & wq, const std::vector<float> & x, std::vector<float> & out) {
    ggml_init_params ip = { 3 * ggml_tensor_overhead() + ggml_graph_overhead(), nullptr, true };
    ggml_context * ctx = ggml_init(ip);
    if (ctx == nullptr) return false;
    ggml_tensor * w = ggml_new_tensor_2d(ctx, type, k, m);
    ggml_tensor * a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, k, n);
    ggml_tensor * y = ggml_mul_mat(ctx, w, a);
    // Computing on the backend directly (no scheduler): an unsupported op would abort. Not
    // supported is not a failure either: the scheduler would run that op on the CPU.
    if (!ggml_backend_supports_op(backend, y)) { ggml_free(ctx); out.clear(); return true; }
    ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, y);
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (buf == nullptr) { ggml_free(ctx); return false; }
    ggml_backend_buffer_set_usage(buf, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
    ggml_backend_tensor_set(w, wq.data(), 0, wq.size());
    ggml_backend_tensor_set(a, x.data(), 0, x.size() * sizeof(float));
    const bool ok = ggml_backend_graph_compute(backend, gf) == GGML_STATUS_SUCCESS;
    if (ok) {
        out.resize(static_cast<size_t>(m * n));
        ggml_backend_tensor_get(y, out.data(), 0, out.size() * sizeof(float));
    }
    ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    return ok;
}

// Compares the GPU against the CPU on the weight types the supported models use (Q4_K / Q6_K text
// weights, Q8_0 / F16 vision projectors) at batch sizes that hit the GEMV, small-batch GEMM and
// large GEMM kernels. Shapes are >= 512 so the Adreno-specific paths are taken. Returns "" when
// every case agrees (normalised MSE < 1e-2; quantisation noise between backends is ~1e-4, a broken
// kernel gives ~1 or NaN), else a short description of the first failure.
std::string gpu_self_test(ggml_backend_dev_t dev) {
    const double t0 = now_ms();
    ggml_backend_t gpu = ggml_backend_dev_init(dev, nullptr);   // compiles the kernels (cached on disk)
    if (gpu == nullptr) return "GPU backend init failed";
    ggml_backend_t cpu = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (cpu == nullptr) { ggml_backend_free(gpu); return "CPU backend init failed"; }
    const double t_init = now_ms() - t0;

    const int64_t k = 2048, m = 512;
    const int64_t batches[] = { 1, 8, 33, 128, 512 };
    const ggml_type types[] = { GGML_TYPE_Q4_K, GGML_TYPE_Q6_K, GGML_TYPE_Q8_0, GGML_TYPE_Q4_0, GGML_TYPE_F16 };
    std::mt19937 rng(42);
    std::normal_distribution<float> nd(0.0f, 1.0f);
    std::vector<float> wf(static_cast<size_t>(k * m));
    for (auto & v : wf) v = nd(rng) * 0.05f;
    std::vector<float> x(static_cast<size_t>(k * batches[4]));
    for (auto & v : x) v = nd(rng);

    std::string err;
    double worst = 0;
    for (ggml_type type : types) {
        if (!err.empty()) break;
        std::vector<uint8_t> wq(ggml_row_size(type, k) * static_cast<size_t>(m));
        ggml_quantize_chunk(type, wf.data(), wq.data(), 0, m, k, nullptr);
        for (int64_t n : batches) {
            std::vector<float> xn(x.begin(), x.begin() + k * n), yg, yc;
            if (!run_mul_mat(cpu, type, k, m, n, wq, xn, yc)) { err = "CPU compute failed"; break; }
            if (!run_mul_mat(gpu, type, k, m, n, wq, xn, yg)) {
                err = std::string("GPU compute failed: ") + ggml_type_name(type);
                break;
            }
            if (yg.empty() || yc.empty()) {   // not offered by the GPU backend: runs on the CPU anyway
                LOGI("GPU self-test: %s x %d not supported on the GPU, skipped", ggml_type_name(type), static_cast<int>(n));
                continue;
            }
            double se = 0, ref = 0;
            for (size_t i = 0; i < yc.size(); ++i) {
                const double d = static_cast<double>(yg[i]) - yc[i];
                se += d * d;
                ref += static_cast<double>(yc[i]) * yc[i];
            }
            const double nmse = ref > 0 ? se / ref : se;
            worst = std::max(worst, std::isfinite(nmse) ? nmse : 1e9);
            if (!std::isfinite(nmse) || nmse > 1e-2) {
                char b[128];
                snprintf(b, sizeof(b), "%s x %d: nmse %.3g", ggml_type_name(type), static_cast<int>(n), nmse);
                err = b;
                break;
            }
        }
    }
    ggml_backend_free(cpu);
    ggml_backend_free(gpu);
    LOGI("GPU self-test: %s (init %.0f ms, total %.0f ms, worst nmse %.2g)", err.empty() ? "passed" : err.c_str(),
         t_init, now_ms() - t0, worst);
    LOGIMG("GPU self-test %s in %.0f ms (kernel init %.0f ms)", err.empty() ? "passed" : "FAILED", now_ms() - t0, t_init);
    return err;
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
    g_lib_dir = dir;
    // CPU variants only: the OpenCL module has a name this does not match (see CMakeLists.txt).
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
        jint n_ctx, jint n_threads, jint n_threads_batch, jint n_batch,
        jboolean use_mmap, jint gpu_mode) {
    if (!g_backend_ready) {
        LOGE("nativeLoad called before nativeInitBackend");
        return 0;
    }

    // Always an explicit device list: with the OpenCL module registered, a NULL list would make
    // llama.cpp put every layer on the GPU. Empty list = CPU only (weights in the CPU / KleidiAI
    // repack buffers as before).
    const bool want_gpu = gpu_mode == LT_GPU_ALL && g_gpu_dev != nullptr;
    if (gpu_mode == LT_GPU_ALL && !want_gpu) LOGW("GPU mode requested but no GPU device; using the CPU");
    const std::string path = fd >= 0 ? std::string() : bytes_to_string(env, jpath);
    auto load_model = [&](bool gpu) -> llama_model * {
        static ggml_backend_dev_t cpu_only[1] = { nullptr };
        static ggml_backend_dev_t gpu_devs[2] = { nullptr, nullptr };
        gpu_devs[0] = g_gpu_dev;
        llama_model_params mparams = llama_model_default_params();
        mparams.devices      = gpu ? gpu_devs : cpu_only;
        mparams.n_gpu_layers = gpu ? 999 : 0;
        mparams.split_mode   = LLAMA_SPLIT_MODE_NONE;
        mparams.main_gpu     = 0;
        // b11707 replaced use_mmap/use_mlock with load_mode.
        mparams.load_mode    = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        if (fd >= 0) {
            int dup_fd = dup(fd);
            FILE * f = dup_fd >= 0 ? fdopen(dup_fd, "rb") : nullptr;
            if (f == nullptr) {
                if (dup_fd >= 0) close(dup_fd);
                LOGE("cannot open fd %d", fd);
                return nullptr;
            }
            llama_model * m = llama_model_load_from_file_ptr(f, mparams);
            fclose(f);
            return m;
        }
        LOGI("Loading model: %s (%s)", path.c_str(), backend_name(gpu));
        return llama_model_load_from_file(path.c_str(), mparams);
    };

    bool on_gpu = want_gpu;
    llama_model * model = load_model(on_gpu);
    if (model == nullptr && on_gpu) {
        LOGW("model load on the GPU failed; retrying on the CPU");
        on_gpu = false;
        model = load_model(false);
    }
    if (model == nullptr) {
        LOGE("llama_model_load failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(std::max(256, static_cast<int>(n_ctx)));
    // n_ubatch = n_batch: one llama_decode of up to n_batch prompt tokens / image embeddings is a
    // single graph (big matmuls). Qwen-VL images are decoded causally with explicit M-RoPE
    // positions, so mtmd may split an image chunk across ubatches without changing the result.
    cparams.n_batch         = static_cast<uint32_t>(std::max(32, static_cast<int>(n_batch)));
    cparams.n_ubatch        = cparams.n_batch;
    // Generation is memory-bandwidth bound, prefill compute bound: separate thread counts.
    cparams.n_threads       = std::max(1, static_cast<int>(n_threads));
    cparams.n_threads_batch = std::max(1, static_cast<int>(n_threads_batch));
    cparams.no_perf         = false;

    auto * session = new Session();
    session->model   = model;
    session->n_batch = static_cast<int32_t>(cparams.n_batch);
    cparams.abort_callback      = abort_cb;
    cparams.abort_callback_data = session;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr && on_gpu) {
        LOGW("llama_init_from_model on the GPU failed; reloading on the CPU");
        llama_model_free(model);
        on_gpu = false;
        model = load_model(false);
        session->model = model;
        ctx = model != nullptr ? llama_init_from_model(model, cparams) : nullptr;
    }
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        if (model != nullptr) llama_model_free(model);
        delete session;
        return 0;
    }
    session->ctx = ctx;
    session->text_gpu = on_gpu;
    LOGI("Model loaded: n_ctx=%u threads=%d threads_batch=%d batch=%d ubatch=%d backend=%s", llama_n_ctx(ctx),
         cparams.n_threads, cparams.n_threads_batch, cparams.n_batch, cparams.n_ubatch, backend_name(on_gpu));
    LOGIMG("text model on %s (prefill + generation), gpu_mode=%d", backend_name(on_gpu), static_cast<int>(gpu_mode));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT void JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return;
    free_mtmd(s);   // references the model; must go first
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
    Session * s = as_session(handle);
    if (s == nullptr) return make_result(env, -1, 0, 0, 0, 0, 4);
    s->cancel.store(false);

    // Method ids stay valid after the class reference is gone; drop the local ref right away.
    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
    env->DeleteLocalRef(cb_cls);
    if (on_token == nullptr) {
        env->ExceptionClear();   // NoSuchMethodError: report a bad callback instead of throwing
        return make_result(env, -1, 0, 0, 0, 0, 4);
    }

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = bytes_to_string(env, jprompt);

    // The prompt is already fully formatted (chat template applied), so special tokens are parsed
    // and BOS is only added when the vocab wants one and the template did not include it.
    std::vector<llama_token> tokens = tokenize(vocab, prompt, /*add_special*/ false, /*parse_special*/ true);
    if (tokens.empty()) return make_result(env, -2, 0, 0, 0, 0, 4);
    if (llama_vocab_get_add_bos(vocab)) {
        const llama_token bos = llama_vocab_bos(vocab);
        if (bos != LLAMA_TOKEN_NULL && tokens.front() != bos) tokens.insert(tokens.begin(), bos);
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    const int n_prompt = static_cast<int>(tokens.size());
    if (n_prompt >= n_ctx - 4) {
        LOGW("prompt too long: %d tokens, n_ctx %d", n_prompt, n_ctx);
        return make_result(env, -3, n_prompt, 0, 0, 0, 2);
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_perf_context_reset(s->ctx);

    llama_sampler * smpl = make_sampler(vocab, temperature, top_p, top_k, min_p, repeat_penalty, repeat_last_n, seed);

    // Prompt processing in n_batch chunks.
    const double t_prompt0 = now_ms();
    for (int i = 0; i < n_prompt; i += s->n_batch) {
        const int n = std::min(s->n_batch, n_prompt - i);
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n));
        if (rc != 0) {
            llama_sampler_free(smpl);
            const bool cancelled = s->cancel.load();
            LOGW("prompt decode rc=%d cancelled=%d", rc, cancelled);
            return make_result(env, cancelled ? 1 : -4, n_prompt, now_ms() - t_prompt0, 0, 0, cancelled ? 3 : 4);
        }
    }
    const double prompt_ms = now_ms() - t_prompt0;

    const GenResult g = generate_loop(env, s, smpl, callback, on_token, n_prompt, max_tokens);
    llama_sampler_free(smpl);
    log_perf(s, s->text_gpu ? "text/GPU" : "text/CPU", n_prompt, prompt_ms, g);
    return make_result(env, g.status, n_prompt, prompt_ms, g.n_gen, g.gen_ms, g.reason);
}

// ------------------------------------------------------------------------------------------------
// Image input (libmtmd)
// ------------------------------------------------------------------------------------------------

// mtmd_default_marker() as UTF-8 ("<__media__>"): where the image goes in the prompt.
JNIEXPORT jbyteArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeMediaMarker(JNIEnv * env, jobject) {
    return string_to_bytes(env, mtmd_default_marker());
}

// Loads the vision projector (mmproj GGUF) for the session's model, replacing any loaded one.
// `image_max_tokens` > 0 caps the number of image tokens (mtmd downscales larger images).
// Returns 0 ok, -1 bad handle, -2 load failed, -3 the projector has no vision encoder.
JNIEXPORT jint JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeMmprojLoad(
        JNIEnv * env, jobject, jlong handle, jbyteArray jpath, jint n_threads, jint image_max_tokens,
        jboolean use_gpu) {
    Session * s = as_session(handle);
    if (s == nullptr || s->model == nullptr) return -1;
    free_mtmd(s);
    if (!g_mtmd_log_ready) {
        mtmd_helper_log_set(log_cb, nullptr);   // also routes mtmd/clip logs to logcat
        g_mtmd_log_ready = true;
    }

    const std::string path = bytes_to_string(env, jpath);
    const bool want_gpu = use_gpu == JNI_TRUE && g_gpu_dev != nullptr;
    mtmd_context_params mp = mtmd_context_params_default();
    // Explicit device: clip throws (mtmd_init fails) instead of silently picking another backend,
    // so we know where the encoder runs. The clip repack patch only touches CPU-only contexts.
    mp.use_gpu       = want_gpu;
    mp.device        = want_gpu ? g_gpu_dev : nullptr;
    mp.print_timings = true;
    mp.n_threads     = std::max(1, static_cast<int>(n_threads));
    mp.media_marker  = mtmd_default_marker();
    mp.warmup        = false;   // skip the max-size warmup encode: faster load, less peak memory
    if (image_max_tokens > 0) mp.image_max_tokens = image_max_tokens;
    mp.cb_eval           = mtmd_eval_cb;
    mp.cb_eval_user_data = s;

    LOGI("Loading mmproj: %s (threads=%d, image_max_tokens=%d, %s)", path.c_str(), mp.n_threads,
         image_max_tokens, backend_name(want_gpu));
    const double t0 = now_ms();
    mtmd_context * m = mtmd_init_from_file(path.c_str(), s->model, mp);
    bool on_gpu = want_gpu;
    if (m == nullptr && want_gpu) {
        LOGW("mmproj init on the GPU failed; retrying on the CPU");
        mp.use_gpu = false;
        mp.device  = nullptr;
        on_gpu = false;
        m = mtmd_init_from_file(path.c_str(), s->model, mp);
    }
    if (m == nullptr) {
        LOGE("mtmd_init_from_file failed");
        return -2;
    }
    if (!mtmd_support_vision(m)) {
        LOGE("mmproj has no vision encoder");
        mtmd_free(m);
        return -3;
    }
    s->mtmd = m;
    s->mtmd_gpu = on_gpu;
    LOGI("mmproj loaded in %.0f ms (mrope=%d)", now_ms() - t0, mtmd_decode_use_mrope(m) ? 1 : 0);
    LOGIMG("mmproj load %.0f ms on %s (threads=%d, image_max_tokens=%d, non_causal=%d)", now_ms() - t0,
           backend_name(on_gpu), mp.n_threads, image_max_tokens, mtmd_decode_use_non_causal(m, nullptr) ? 1 : 0);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeMmprojFree(JNIEnv *, jobject, jlong handle) {
    free_mtmd(as_session(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeMmprojLoaded(JNIEnv *, jobject, jlong handle) {
    Session * s = as_session(handle);
    return s != nullptr && s->mtmd != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Like nativeGenerate, but the formatted prompt contains the media marker and `rgb` is the image
// as packed RGB888 (width * height * 3 bytes, row-major, no padding).
// Extra statuses: -5 no mmproj loaded, -6 bad image / image preprocessing failed, -7 image encode
// or embedding decode failed.
JNIEXPORT jdoubleArray JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeGenerateImage(
        JNIEnv * env, jobject, jlong handle,
        jbyteArray jprompt, jbyteArray jrgb, jint width, jint height,
        jint max_tokens,
        jfloat temperature, jfloat top_p, jint top_k, jfloat min_p,
        jfloat repeat_penalty, jint repeat_last_n, jint seed,
        jobject callback, jobject stage_listener) {
    Session * s = as_session(handle);
    if (s == nullptr) return make_result(env, -1, 0, 0, 0, 0, 4);
    s->cancel.store(false);
    s->eval_nodes = 0;
    const double t_start = now_ms();

    // Stage notifications for the UI: StageListener.onStage(int), see LlamaNative.kt.
    jmethodID on_stage = nullptr;
    if (stage_listener != nullptr) {
        jclass st_cls = env->GetObjectClass(stage_listener);
        on_stage = env->GetMethodID(st_cls, "onStage", "(I)V");
        if (on_stage == nullptr) env->ExceptionClear();
        env->DeleteLocalRef(st_cls);
    }
    auto stage = [&](int id) {
        if (on_stage == nullptr) return;
        env->CallVoidMethod(stage_listener, on_stage, static_cast<jint>(id));
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    if (s->mtmd == nullptr) return make_result(env, -5, 0, 0, 0, 0, 4);

    // Method ids stay valid after the class reference is gone; drop the local ref right away.
    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
    env->DeleteLocalRef(cb_cls);
    if (on_token == nullptr) {
        env->ExceptionClear();   // NoSuchMethodError: report a bad callback instead of throwing
        return make_result(env, -1, 0, 0, 0, 0, 4);
    }

    // ---- image -> mtmd_bitmap (copies the pixels)
    if (jrgb == nullptr || width <= 0 || height <= 0) return make_result(env, -6, 0, 0, 0, 0, 4);
    const size_t n_bytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    if (static_cast<size_t>(env->GetArrayLength(jrgb)) != n_bytes) {
        LOGE("image buffer size %d != %dx%dx3", env->GetArrayLength(jrgb), width, height);
        return make_result(env, -6, 0, 0, 0, 0, 4);
    }
    mtmd_bitmap * bitmap = nullptr;
    {
        jbyte * px = env->GetByteArrayElements(jrgb, nullptr);
        if (px == nullptr) return make_result(env, -6, 0, 0, 0, 0, 4);
        bitmap = mtmd_bitmap_init(static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                  reinterpret_cast<const unsigned char *>(px));
        env->ReleaseByteArrayElements(jrgb, px, JNI_ABORT);
    }
    if (bitmap == nullptr) return make_result(env, -6, 0, 0, 0, 0, 4);

    // ---- prompt + image -> chunks
    std::string prompt = bytes_to_string(env, jprompt);
    const std::string marker = mtmd_default_marker();
    if (prompt.find(marker) == std::string::npos) {
        LOGW("prompt has no media marker; putting the image first");
        prompt.insert(0, marker);
    }
    mtmd_input_text text;
    text.text          = prompt.c_str();
    text.text_len      = prompt.size();
    text.add_special   = true;   // BOS only if the vocab wants one (Qwen does not)
    text.parse_special = true;   // the chat template's special tokens are written literally

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = { bitmap };
    // mtmd_tokenize also preprocesses the image (resize / normalise for the encoder).
    stage(LT_STAGE_ENCODE);
    const double t_tok0 = now_ms();
    const int32_t tok_rc = mtmd_tokenize(s->mtmd, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    const double tokenize_ms = now_ms() - t_tok0;
    if (tok_rc != 0) {
        LOGE("mtmd_tokenize failed: %d", tok_rc);
        mtmd_input_chunks_free(chunks);
        return make_result(env, tok_rc == 2 ? -6 : -2, 0, 0, 0, 0, 4);
    }

    const int n_ctx    = static_cast<int>(llama_n_ctx(s->ctx));
    const int n_prompt = static_cast<int>(mtmd_helper_get_n_tokens(chunks));
    if (n_prompt >= n_ctx - 4) {
        LOGW("image prompt too long: %d tokens, n_ctx %d", n_prompt, n_ctx);
        mtmd_input_chunks_free(chunks);
        return make_result(env, -3, n_prompt, 0, 0, 0, 2);
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_perf_context_reset(s->ctx);

    // ---- evaluate chunk by chunk: text via llama_decode, images as CLIP encode (STAGE_ENCODE) then
    // embedding decode (STAGE_PREFILL). A stop request is honoured between chunks as well as
    // inside each one (abort_cb / mtmd_eval_cb).
    const double t_prompt0 = now_ms();
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    llama_pos n_past = 0;
    double encode_ms = 0, image_decode_ms = 0, text_ms = 0;
    int n_image_tokens = 0;
    auto cancelled_result = [&]() {
        mtmd_input_chunks_free(chunks);
        LOGIMG("cancelled after %.0f ms", now_ms() - t_start);
        return make_result(env, 1, n_prompt, now_ms() - t_prompt0, 0, 0, 3);
    };
    for (size_t i = 0; i < n_chunks; ++i) {
        if (s->cancel.load()) return cancelled_result();
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        const bool is_image = mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT;
        const bool last = i == n_chunks - 1;
        llama_pos new_n_past = n_past;
        int32_t rc = 0;
        if (!is_image) {
            const double t0 = now_ms();
            rc = mtmd_helper_eval_chunk_single(s->mtmd, s->ctx, chunk, n_past, /*seq_id*/ 0,
                                               s->n_batch, /*logits_last*/ last, &new_n_past);
            text_ms += now_ms() - t0;
        } else {
            n_image_tokens += static_cast<int>(mtmd_input_chunk_get_n_tokens(chunk));
            stage(LT_STAGE_ENCODE);
            double t0 = now_ms();
            rc = mtmd_encode_chunk(s->mtmd, chunk);
            encode_ms += now_ms() - t0;
            // A cancelled encode returns "success" with a partial result: check the flag first.
            if (s->cancel.load()) return cancelled_result();
            if (rc == 0) {
                stage(LT_STAGE_PREFILL);
                t0 = now_ms();
                rc = mtmd_helper_decode_image_chunk(s->mtmd, s->ctx, chunk, mtmd_get_output_embd(s->mtmd),
                                                    n_past, /*seq_id*/ 0, s->n_batch, &new_n_past,
                                                    nullptr, nullptr);
                image_decode_ms += now_ms() - t0;
            }
        }
        if (s->cancel.load()) return cancelled_result();
        if (rc != 0) {
            LOGE("image prompt chunk %zu (%s) failed: rc=%d", i, is_image ? "image" : "text", rc);
            mtmd_input_chunks_free(chunks);
            return make_result(env, is_image ? -7 : -4, n_prompt, now_ms() - t_prompt0, 0, 0, 4);
        }
        n_past = new_n_past;
    }
    mtmd_input_chunks_free(chunks);
    const double prompt_ms = now_ms() - t_prompt0;
    LOGIMG("image %dx%d -> %d image tokens (%d prompt tokens, %zu chunks): tokenize %.0f ms, "
           "encode %.0f ms [%s], image prefill %.0f ms [%s], text prefill %.0f ms [%s], batch %d",
           width, height, n_image_tokens, n_prompt, n_chunks, tokenize_ms, encode_ms, backend_name(s->mtmd_gpu),
           image_decode_ms, backend_name(s->text_gpu), text_ms, backend_name(s->text_gpu), s->n_batch);

    stage(LT_STAGE_GENERATE);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    llama_sampler * smpl = make_sampler(vocab, temperature, top_p, top_k, min_p, repeat_penalty, repeat_last_n, seed);
    const GenResult g = generate_loop(env, s, smpl, callback, on_token, n_prompt, max_tokens);
    llama_sampler_free(smpl);
    log_perf(s, "image", n_prompt, prompt_ms, g);
    LOGIMG("generate %d tok in %.0f ms (%.1f tok/s) [%s]; total %.0f ms", g.n_gen, g.gen_ms,
           g.gen_ms > 0 ? g.n_gen * 1000.0 / g.gen_ms : 0.0, backend_name(s->text_gpu), now_ms() - t_start);
    return make_result(env, g.status, n_prompt, prompt_ms, g.n_gen, g.gen_ms, g.reason);
}

// ------------------------------------------------------------------------------------------------
// Experimental GPU (OpenCL / Adreno)
// ------------------------------------------------------------------------------------------------

// Loads the OpenCL backend module once and returns the GPU's description ("" = no usable GPU:
// non-arm64 build, no libOpenCL.so, or no Adreno device). `cache_dir` holds the compiled kernel
// binaries so only the first GPU use pays for the kernel build.
JNIEXPORT jstring JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeGpuProbe(JNIEnv * env, jobject, jstring jcache_dir) {
#if LT_OPENCL && LT_BACKEND_DL
    if (!g_gpu_probed && g_backend_ready) {
        g_gpu_probed = true;
        const char * cache = env->GetStringUTFChars(jcache_dir, nullptr);
        if (cache != nullptr && cache[0] != '\0') setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache, 1);
        if (cache != nullptr) env->ReleaseStringUTFChars(jcache_dir, cache);
        const std::string path = g_lib_dir + "/libneko-opencl.so";
        if (access(path.c_str(), R_OK) != 0) {
            LOGI("GPU: %s not present", path.c_str());
        } else {
            const double t0 = now_ms();
            ggml_backend_reg_t reg = ggml_backend_load(path.c_str());
            if (reg == nullptr) {
                LOGI("GPU: OpenCL backend unavailable (no usable libOpenCL.so on this device)");
            } else {
                for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
                    ggml_backend_dev_t d = ggml_backend_reg_dev_get(reg, i);
                    if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU) { g_gpu_dev = d; break; }
                }
                LOGI("GPU: OpenCL probe %.0f ms, device: %s", now_ms() - t0,
                     g_gpu_dev ? ggml_backend_dev_description(g_gpu_dev) : "none");
            }
        }
    }
    if (g_gpu_dev == nullptr) return env->NewStringUTF("");
    std::string desc = ggml_backend_dev_description(g_gpu_dev);
    std::string clean;
    for (char c : desc) clean.push_back((c >= 0x20 && c < 0x7f) ? c : '?');   // NewStringUTF wants ASCII here
    return env->NewStringUTF(clean.c_str());
#else
    (void) jcache_dir;
    return env->NewStringUTF("");
#endif
}

// GPU vs CPU check of the quantized matmul kernels (see gpu_self_test). Returns "" when it passed,
// else the reason. Takes a few seconds on first use (kernel build), well under a second after.
JNIEXPORT jstring JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeGpuSelfTest(JNIEnv * env, jobject) {
    if (g_gpu_dev == nullptr) return env->NewStringUTF("no GPU device");
    return env->NewStringUTF(gpu_self_test(g_gpu_dev).c_str());
}

// Where the session runs: bit 0 = text model on the GPU, bit 1 = vision encoder on the GPU.
JNIEXPORT jint JNICALL
Java_com_nekohome_translate_llama_LlamaNative_nativeBackendFlags(JNIEnv *, jobject, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return 0;
    return (s->text_gpu ? 1 : 0) | (s->mtmd != nullptr && s->mtmd_gpu ? 2 : 0);
}

} // extern "C"
