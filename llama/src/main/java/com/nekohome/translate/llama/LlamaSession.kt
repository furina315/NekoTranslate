package com.nekohome.translate.llama

import java.io.Closeable

data class LoadParams(
    val nCtx: Int = 4096,
    /** Threads for token generation (memory-bandwidth bound). */
    val nThreads: Int = defaultThreads(),
    /**
     * Threads for compute-bound work: prompt prefill and the vision encoder (mmproj). Defaults to
     * the performance cores (all 8 on a Snapdragon 8 Elite / Gen 5, which has no efficiency cores).
     */
    val nThreadsBatch: Int = defaultBatchThreads(),
    /**
     * Logical and physical batch size (n_batch = n_ubatch). 512 keeps text prefill and image
     * embeddings in large matmuls; Qwen-VL image chunks use causal attention with explicit M-RoPE
     * positions, so mtmd may split an image across several ubatches.
     */
    val nBatch: Int = 512,
    val useMmap: Boolean = true,
    /** Experimental GPU acceleration; see [GpuMode]. Falls back to the CPU natively if unavailable. */
    val gpuMode: GpuMode = GpuMode.OFF,
) {
    companion object {
        /** Generation threads: see [CpuCores.generationThreads]. */
        fun defaultThreads(): Int = CpuCores.defaultGenerationThreads()

        /** Prefill / image encode threads: see [CpuCores.batchThreads]. */
        fun defaultBatchThreads(): Int = CpuCores.defaultBatchThreads()
    }
}

data class SamplingParams(
    /** <= 0 means greedy decoding. */
    val temperature: Float = 0f,
    /** 1.0 disables top-p. */
    val topP: Float = 1f,
    /** 0 disables top-k. */
    val topK: Int = 0,
    val minP: Float = 0f,
    /** 1.0 disables the repetition penalty. */
    val repeatPenalty: Float = 1f,
    val repeatLastN: Int = 64,
    val maxTokens: Int = 1024,
    /** < 0 picks a random seed. */
    val seed: Int = -1,
)

enum class StopReason { EOG, MAX_TOKENS, CONTEXT_FULL, CANCELLED, ERROR }

data class GenerationStats(
    val status: Int,
    val promptTokens: Int,
    val promptMs: Double,
    val generatedTokens: Int,
    val generationMs: Double,
    val stopReason: StopReason,
) {
    val promptTokensPerSecond: Double
        get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val tokensPerSecond: Double
        get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0
    val ok: Boolean get() = status == 0 || status == 1

    fun errorMessage(): String? = when (status) {
        0, 1 -> null
        -2 -> "Tokenization failed"
        -3 -> "Prompt is longer than the context window ($promptTokens tokens)"
        -4 -> "llama_decode failed"
        -5 -> "No vision projector (mmproj) loaded"
        -6 -> "Image could not be processed"
        -7 -> "Image encoding failed"
        else -> "Generation failed (status $status)"
    }
}

data class ModelInfo(
    val architecture: String,
    val name: String,
    val fileType: String,
    val description: String,
    val parameterCount: Long,
    val sizeBytes: Long,
    val nCtxTrain: Int,
    val nCtx: Int,
    val chatTemplate: String?,
)

data class ChatMessage(val role: String, val content: String)

/** An image as packed RGB888 pixels (`width * height * 3` bytes, row-major, no padding). */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Empty image" }
        require(rgb.size.toLong() == width.toLong() * height * 3) { "RGB buffer size mismatch" }
    }
}

/**
 * One loaded model + context. Not thread-safe: use it from a single thread
 * (except [cancel], which may be called from anywhere).
 */
class LlamaSession private constructor(handle: Long) : Closeable {

    @Volatile
    private var handle: Long = handle

    companion object {
        /**
         * Placeholder for the image in an image prompt; must equal mtmd_default_marker() (checked
         * natively: a prompt without it gets the image prepended).
         */
        const val MEDIA_MARKER = "<__media__>"

        /** Loads from a file path. `/proc/self/fd/N` paths are accepted too. */
        fun load(path: String, params: LoadParams = LoadParams()): LlamaSession {
            val h = LlamaNative.nativeLoad(
                path.toByteArray(Charsets.UTF_8), -1,
                params.nCtx, params.nThreads, params.nThreadsBatch, params.nBatch, params.useMmap,
                params.gpuMode.nativeCode,
            )
            if (h == 0L) throw IllegalStateException("Failed to load model: $path")
            return LlamaSession(h)
        }

        /** Loads from an open file descriptor (e.g. ParcelFileDescriptor.fd). The fd stays owned by the caller. */
        fun loadFromFd(fd: Int, params: LoadParams = LoadParams()): LlamaSession {
            val h = LlamaNative.nativeLoad(
                null, fd, params.nCtx, params.nThreads, params.nThreadsBatch, params.nBatch, params.useMmap,
                params.gpuMode.nativeCode,
            )
            if (h == 0L) throw IllegalStateException("Failed to load model from fd $fd")
            return LlamaSession(h)
        }
    }

    val isOpen: Boolean get() = handle != 0L

    private fun requireHandle(): Long {
        check(handle != 0L) { "Session is closed" }
        return handle
    }

    fun meta(key: String): String? =
        LlamaNative.nativeMeta(requireHandle(), key.toByteArray(Charsets.UTF_8))
            ?.toString(Charsets.UTF_8)

    fun modelInfo(): ModelInfo {
        val packed = LlamaNative.nativeModelInfo(requireHandle())
            ?: throw IllegalStateException("No model info")
        val parts = packed.toString(Charsets.UTF_8).split('\u0000')
        val map = HashMap<String, String>()
        var i = 0
        while (i + 1 < parts.size) {
            map[parts[i]] = parts[i + 1]
            i += 2
        }
        return ModelInfo(
            architecture = map["general.architecture"].orEmpty(),
            name = map["general.name"].orEmpty(),
            fileType = map["ftype_name"].orEmpty(),
            description = map["desc"].orEmpty(),
            parameterCount = map["n_params"]?.toLongOrNull() ?: 0L,
            sizeBytes = map["size_bytes"]?.toLongOrNull() ?: 0L,
            nCtxTrain = map["n_ctx_train"]?.toIntOrNull() ?: 0,
            nCtx = map["n_ctx"]?.toIntOrNull() ?: 0,
            chatTemplate = map["chat_template"]?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Formats [messages] with llama_chat_apply_template. Uses the GGUF's embedded template when
     * [template] is null. Returns null if llama.cpp does not recognise the template (it does not run
     * Jinja; it matches known template families).
     */
    fun applyChatTemplate(
        messages: List<ChatMessage>,
        addAssistant: Boolean = true,
        template: String? = null,
    ): String? = LlamaNative.nativeApplyChatTemplate(
        requireHandle(),
        template?.toByteArray(Charsets.UTF_8),
        messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        addAssistant,
    )?.toString(Charsets.UTF_8)

    /**
     * Generates a completion for an already formatted [prompt]. Blocks until done; [onToken] is
     * invoked on the calling thread for each decoded UTF-8 chunk. Return false from it to stop.
     */
    fun generate(
        prompt: String,
        sampling: SamplingParams,
        onToken: (String) -> Boolean,
    ): GenerationStats {
        val r = LlamaNative.nativeGenerate(
            requireHandle(),
            prompt.toByteArray(Charsets.UTF_8),
            sampling.maxTokens,
            sampling.temperature,
            sampling.topP,
            sampling.topK,
            sampling.minP,
            sampling.repeatPenalty,
            sampling.repeatLastN,
            sampling.seed,
        ) { bytes -> onToken(bytes.toString(Charsets.UTF_8)) }
        return stats(r)
    }

    /** Text model (prefill + generation) actually runs on the GPU. */
    val textOnGpu: Boolean get() = handle != 0L && (LlamaNative.nativeBackendFlags(handle) and 1) != 0

    /** The loaded vision encoder actually runs on the GPU. */
    val encoderOnGpu: Boolean get() = handle != 0L && (LlamaNative.nativeBackendFlags(handle) and 2) != 0

    /** True while a vision projector is loaded for this session. */
    val hasMmproj: Boolean get() = handle != 0L && LlamaNative.nativeMmprojLoaded(handle)

    /**
     * Loads the vision projector [path] (an mmproj GGUF) for this model, replacing any loaded one.
     * [imageMaxTokens] > 0 caps the image tokens per image. [useGpu] runs the encoder on the GPU
     * when one was probed (CPU fallback if its init fails; see [encoderOnGpu]). Throws on failure.
     */
    fun loadMmproj(path: String, nThreads: Int, imageMaxTokens: Int, useGpu: Boolean = false) {
        when (val rc = LlamaNative.nativeMmprojLoad(requireHandle(), path.toByteArray(Charsets.UTF_8), nThreads, imageMaxTokens, useGpu)) {
            0 -> Unit
            -3 -> throw IllegalStateException("Not a vision projector: $path")
            else -> throw IllegalStateException("Failed to load mmproj ($rc): $path")
        }
    }

    fun freeMmproj() {
        val h = handle
        if (h != 0L) LlamaNative.nativeMmprojFree(h)
    }

    /**
     * Like [generate], for a formatted [prompt] containing [MEDIA_MARKER] plus [image].
     * Requires [loadMmproj] first. [onStage] receives StageListener.STAGE_* progress.
     */
    fun generateWithImage(
        prompt: String,
        image: RgbImage,
        sampling: SamplingParams,
        onStage: StageListener? = null,
        onToken: (String) -> Boolean,
    ): GenerationStats {
        val r = LlamaNative.nativeGenerateImage(
            requireHandle(),
            prompt.toByteArray(Charsets.UTF_8),
            image.rgb,
            image.width,
            image.height,
            sampling.maxTokens,
            sampling.temperature,
            sampling.topP,
            sampling.topK,
            sampling.minP,
            sampling.repeatPenalty,
            sampling.repeatLastN,
            sampling.seed,
            { bytes -> onToken(bytes.toString(Charsets.UTF_8)) },
            onStage,
        )
        return stats(r)
    }

    private fun stats(r: DoubleArray) = GenerationStats(
        status = r[0].toInt(),
        promptTokens = r[1].toInt(),
        promptMs = r[2],
        generatedTokens = r[3].toInt(),
        generationMs = r[4],
        stopReason = StopReason.entries.getOrElse(r[5].toInt()) { StopReason.ERROR },
    )

    /** Thread-safe: requests the running [generate] (or prompt processing) to stop. */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.nativeCancel(h)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            LlamaNative.nativeFree(h)
        }
    }
}
