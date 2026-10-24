# Build-time patch for llama.cpp's tools/mtmd/clip.cpp (the vision encoder), applied to a copy in
# the build tree so the submodule stays untouched.
#
# Why: llama.cpp places the text model's quantized matmul weights in the CPU backend's "extra"
# buffer types (KleidiAI / CPU_REPACK), whose interleaved layouts run i8mm / dotprod GEMM kernels.
# clip.cpp allocates every mmproj weight in the plain CPU buffer, so the vision encoder's Q8_0
# matmuls (~80 % of an image encode) fall back to the generic vec_dot path at a fraction of the
# speed. The patch gives CPU-only clip contexts the same treatment: each 2-D quantized weight goes
# to the first extra buffer type whose supports_op() accepts it as a MUL_MAT src0 (the same test
# llama.cpp's own loader runs); everything else stays in the default buffer.
#
# Usage (from CMakeLists.txt): lt_patch_clip(<llama.cpp source dir> <output var>)
# Sets <output var> to the patched clip.cpp, or to "" when the anchors no longer match (a newer
# llama.cpp): the build then keeps the original file and prints a warning.

function(lt_patch_clip LLAMA_SRC OUT_VAR)
    set(src "${LLAMA_SRC}/tools/mtmd/clip.cpp")
    set(dst "${CMAKE_CURRENT_BINARY_DIR}/lt-patched/clip.cpp")
    file(READ "${src}" code)

    set(a1 [=[    ggml_backend_buffer_ptr buf;
]=])
    set(r1 [=[    ggml_backend_buffer_ptr buf;
    std::vector<ggml_backend_buffer_ptr> lt_extra_bufs; // NekoTranslate: repacked matmul weights
]=])

    set(a2 [=[            ggml_backend_buffer_type_t buft = ggml_backend_get_default_buffer_type(ctx_clip.backend);
            ctx_clip.buf.reset(ggml_backend_alloc_ctx_tensors_from_buft(ctx_clip.ctx_data.get(), buft));
]=])
    set(r2 [=[            ggml_backend_buffer_type_t buft = ggml_backend_get_default_buffer_type(ctx_clip.backend);
            if (!lt_clip_alloc_with_extra_bufts(ctx_clip)) {
                ctx_clip.buf.reset(ggml_backend_alloc_ctx_tensors_from_buft(ctx_clip.ctx_data.get(), buft));
            }
]=])

    set(a3 [=[                    if (ggml_backend_buft_is_host(buft)) {]=])
    set(r3 [=[                    if (ggml_backend_buffer_is_host(cur->buffer)) {]=])

    set(a4 [=[struct clip_model_loader {
]=])
    set(r4 [=[// ---- NekoTranslate patch (llama/src/main/cpp/patch-clip-repack.cmake) ---------------------------
// CPU-only: put 2-D quantized matmul weights in the CPU backend's extra buffer types (KleidiAI /
// CPU_REPACK) like llama.cpp does for the text model, so the encoder uses the i8mm / dotprod GEMMs.
// Returns false (nothing allocated) when there is nothing to gain; the caller then allocates as usual.
static bool lt_clip_alloc_with_extra_bufts(clip_ctx & ctx_clip) {
    if (ctx_clip.no_alloc || ctx_clip.backend != ctx_clip.backend_cpu) return false;
    if (std::getenv("LT_CLIP_NO_REPACK") != nullptr) return false;
    ggml_backend_dev_t dev = ggml_backend_get_device(ctx_clip.backend_cpu);
    ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
    if (reg == nullptr) return false;
    auto get_extra = (ggml_backend_dev_get_extra_bufts_t)
            ggml_backend_reg_get_proc_address(reg, "ggml_backend_dev_get_extra_bufts");
    if (get_extra == nullptr) return false;
    std::vector<ggml_backend_buffer_type_t> extra;
    for (ggml_backend_buffer_type_t * p = get_extra(dev); p != nullptr && *p != nullptr; ++p) extra.push_back(*p);
    if (extra.empty()) return false;

    // Zero-size buffers only carry the buffer type for the supports_op probe.
    std::vector<ggml_backend_buffer_ptr> probes;
    for (auto * bt : extra) probes.emplace_back(ggml_backend_buft_alloc_buffer(bt, 0));

    ggml_context * cdata = ctx_clip.ctx_data.get();
    std::vector<std::vector<ggml_tensor *>> groups(extra.size() + 1); // last = default buffer
    size_t n_extra = 0;
    for (ggml_tensor * t = ggml_get_first_tensor(cdata); t != nullptr; t = ggml_get_next_tensor(cdata, t)) {
        size_t g = extra.size();
        const std::string name = t->name;
        // Only linear-layer weights: embeddings / position tables are read by other ops.
        if (ggml_is_quantized(t->type) && ggml_n_dims(t) == 2 &&
                name.find("embd") == std::string::npos && name.find("pos") == std::string::npos) {
            for (size_t i = 0; i < extra.size() && probes[i]; ++i) {
                ggml_init_params ip = { 4 * ggml_tensor_overhead(), nullptr, true };
                ggml_context_ptr c(ggml_init(ip));
                ggml_tensor * w = ggml_dup_tensor(c.get(), t);
                w->buffer = probes[i].get();
                ggml_tensor * x = ggml_new_tensor_2d(c.get(), GGML_TYPE_F32, t->ne[0], 64);
                if (ggml_backend_dev_supports_op(dev, ggml_mul_mat(c.get(), w, x))) { g = i; break; }
            }
        }
        if (g < extra.size()) ++n_extra;
        groups[g].push_back(t);
    }
    if (n_extra == 0) return false;

    auto alloc_group = [](ggml_backend_buffer_type_t bt, const std::vector<ggml_tensor *> & ts) {
        const size_t align = ggml_backend_buft_get_alignment(bt);
        size_t size = 0;
        for (auto * t : ts) size += GGML_PAD(ggml_backend_buft_get_alloc_size(bt, t), align);
        ggml_backend_buffer_t b = ggml_backend_buft_alloc_buffer(bt, std::max(size, align));
        if (b == nullptr) throw std::runtime_error("clip: failed to allocate weight buffer");
        ggml_tallocr ta = ggml_tallocr_new(b);
        for (auto * t : ts) {
            if (ggml_tallocr_alloc(&ta, t) != GGML_STATUS_SUCCESS) {
                ggml_backend_buffer_free(b);
                throw std::runtime_error("clip: failed to place weight tensor");
            }
        }
        ggml_backend_buffer_set_usage(b, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
        return b;
    };
    for (size_t i = 0; i < extra.size(); ++i) {
        if (groups[i].empty()) continue;
        ctx_clip.lt_extra_bufs.emplace_back(alloc_group(extra[i], groups[i]));
        LOG_INF("%s: %zu weight tensors in %s\n", __func__, groups[i].size(), ggml_backend_buft_name(extra[i]));
    }
    ctx_clip.buf.reset(alloc_group(ggml_backend_get_default_buffer_type(ctx_clip.backend_cpu), groups.back()));
    return true;
}
// ---- end NekoTranslate patch -------------------------------------------------------------------

struct clip_model_loader {
]=])

    set(ok TRUE)
    foreach(i 1 2 3 4)
        string(FIND "${code}" "${a${i}}" pos)
        if (pos EQUAL -1)
            set(ok FALSE)
            message(WARNING "NekoTranslate: clip.cpp anchor ${i} not found; building the unpatched vision encoder (slower image encode). Update llama/src/main/cpp/patch-clip-repack.cmake for this llama.cpp version.")
            break()
        endif()
    endforeach()
    if (NOT ok)
        set(${OUT_VAR} "" PARENT_SCOPE)
        return()
    endif()
    foreach(i 1 2 3 4)
        string(REPLACE "${a${i}}" "${r${i}}" code "${code}")
    endforeach()
    # Write only when changed so incremental builds don't recompile clip.cpp every configure.
    set(old "")
    if (EXISTS "${dst}")
        file(READ "${dst}" old)
    endif()
    if (NOT old STREQUAL code)
        file(WRITE "${dst}" "${code}")
    endif()
    set(${OUT_VAR} "${dst}" PARENT_SCOPE)
endfunction()
