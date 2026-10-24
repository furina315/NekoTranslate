package com.nekohome.translate.llama

/**
 * Raw JNI surface of `liblocaltranslate_jni.so`. Use [LlamaSession] instead of calling this directly.
 *
 * Strings cross the boundary as UTF-8 [ByteArray]s (see llama_jni.cpp for why).
 * Every call except [nativeCancel] must happen on the same thread.
 */
internal object LlamaNative {
    init {
        System.loadLibrary("localtranslate_jni")
    }

    external fun nativeInitBackend(nativeLibDir: String)
    external fun nativeSystemInfo(): String

    /** Returns a session handle or 0. [fd] >= 0 loads from that file descriptor instead of [path]. */
    external fun nativeLoad(
        path: ByteArray?,
        fd: Int,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nBatch: Int,
        useMmap: Boolean,
    ): Long

    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeMeta(handle: Long, key: ByteArray): ByteArray?
    external fun nativeModelInfo(handle: Long): ByteArray?

    external fun nativeApplyChatTemplate(
        handle: Long,
        template: ByteArray?,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        addAssistant: Boolean,
    ): ByteArray?

    /** @return doubleArrayOf(status, nPrompt, promptMs, nGen, genMs, stopReason) */
    external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        callback: TokenCallback,
    ): DoubleArray

    // ---- image input (libmtmd + mmproj)

    /** mtmd_default_marker() as UTF-8. */
    external fun nativeMediaMarker(): ByteArray

    /** @return 0 ok, -1 bad handle, -2 load failed, -3 no vision encoder in the file */
    external fun nativeMmprojLoad(handle: Long, path: ByteArray, nThreads: Int, imageMaxTokens: Int): Int
    external fun nativeMmprojFree(handle: Long)
    external fun nativeMmprojLoaded(handle: Long): Boolean

    /**
     * Like [nativeGenerate]; [prompt] contains the media marker and [rgb] is packed RGB888
     * (`width * height * 3` bytes). Same result layout and [TokenCallback] contract.
     * [stageListener] is told when encoding / prefill / generation starts (on this thread).
     */
    external fun nativeGenerateImage(
        handle: Long,
        prompt: ByteArray,
        rgb: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        callback: TokenCallback,
        stageListener: StageListener?,
    ): DoubleArray
}

/** Receives complete UTF-8 chunks of generated text. Return false to stop generation. */
fun interface TokenCallback {
    fun onToken(utf8: ByteArray): Boolean
}

/**
 * Progress of an image generation, called on the engine thread with one of the STAGE_* values
 * (llama_jni.cpp: LT_STAGE_*). Keep it cheap: it runs between native compute steps.
 */
fun interface StageListener {
    fun onStage(stage: Int)

    companion object {
        /** Vision encoder running on the image (includes image preprocessing). */
        const val STAGE_ENCODE = 1
        /** Image embeddings and prompt text going through the language model. */
        const val STAGE_PREFILL = 2
        /** Prompt done; tokens are about to stream. */
        const val STAGE_GENERATE = 3
    }
}
