package com.nekohome.translate.engine

import android.content.Context
import com.nekohome.translate.BuildConfig
import com.nekohome.translate.data.ChatFormat
import com.nekohome.translate.data.GpuGuard
import com.nekohome.translate.data.GpuState
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.llama.ChatMessage
import com.nekohome.translate.llama.GenerationStats
import com.nekohome.translate.llama.LlamaBackend
import com.nekohome.translate.llama.LlamaSession
import com.nekohome.translate.llama.LoadParams
import com.nekohome.translate.llama.ModelInfo
import com.nekohome.translate.llama.RgbImage
import com.nekohome.translate.llama.SamplingParams
import com.nekohome.translate.llama.StageListener
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** What an image translation is doing before its first token streams. */
enum class ImageStage {
    /** First use: the vision projector (mmproj) is being loaded. */
    LOADING_MMPROJ,
    /** Vision encoder running on the image. */
    ENCODING,
    /** Image embeddings and prompt going through the language model. */
    PREFILL,
}

sealed interface GenerationEvent {
    /**
     * Image translation progress; [atMs] is SystemClock.elapsedRealtime() when it began.
     * [backend] ("GPU" / "CPU") names where the stage runs when GPU acceleration is on, else null.
     */
    data class Stage(val stage: ImageStage, val atMs: Long, val backend: String? = null) : GenerationEvent
    /** Text generated so far, already cleaned (think block / stop strings removed). */
    data class Partial(val text: String) : GenerationEvent
    data class Done(val text: String, val stats: GenerationStats) : GenerationEvent
}

data class LoadedModel(
    val path: String,
    val info: ModelInfo,
    val loadMs: Long,
    /** What was asked for (params.gpuMode is what [needsReload] compares against). */
    val params: LoadParams,
    /** Where the text model actually ended up (a failed GPU load falls back to the CPU). */
    val textOnGpu: Boolean = false,
)

/**
 * Owns the single [LlamaSession] and runs every native call on one dedicated thread
 * ("llama-engine"), so llama.cpp is never touched from the main thread or from two threads.
 */
class TranslationEngine(context: Context) {

    private val appContext = context.applicationContext
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

    @Volatile
    private var session: LlamaSession? = null

    /** Crash guard + self-test memory for the experimental GPU path (engine thread only). */
    private val gpuGuard = GpuGuard.create(appContext, BuildConfig.LLAMA_CPP_VERSION)

    @Volatile
    var loaded: LoadedModel? = null
        private set

    /** Vision projector currently loaded into [session] (path + token budget), if any. */
    private data class MmprojKey(val path: String, val imageMaxTokens: Int, val gpu: Boolean)

    @Volatile
    private var mmprojKey: MmprojKey? = null

    suspend fun systemInfo(): String = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        LlamaBackend.systemInfo()
    }

    /** Probes the GPU (once) and returns its state, consuming a crash mark left by a previous run. */
    suspend fun gpuState(): GpuState = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        gpuGuard.state(LlamaBackend.gpuProbe(appContext))
    }

    /**
     * Runs the GPU-vs-CPU matmul self-test and remembers the result. A few seconds the first time
     * (the driver compiles the OpenCL kernels; they are cached on disk afterwards).
     */
    suspend fun gpuSelfTest(): GpuState = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        val device = LlamaBackend.gpuProbe(appContext)
        if (device.isEmpty()) return@withContext GpuState(device = "")
        val t0 = SystemClock.elapsedRealtime()
        val error = gpuGuard.guarded(true) { LlamaBackend.gpuSelfTest() }
        Log.i(LOG_TAG, "GPU self-test on $device: ${error.ifEmpty { "passed" }} " +
            "(${SystemClock.elapsedRealtime() - t0} ms)")
        gpuGuard.recordSelfTest(device, error)
        gpuGuard.state(device)
    }

    /** Forgets a failed self-test / crash so the check can run again. */
    suspend fun gpuReset(): GpuState = withContext(dispatcher) {
        gpuGuard.clear()
        gpuGuard.state(LlamaBackend.gpuProbe(appContext))
    }

    /** Loads [file], replacing any loaded model. Throws on failure. */
    suspend fun load(file: File, params: LoadParams): LoadedModel = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        unloadInternal()
        val t0 = System.nanoTime()
        val s = gpuGuard.guarded(params.gpuMode.textOnGpu) { LlamaSession.load(file.absolutePath, params) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        session = s
        val onGpu = s.textOnGpu
        Log.i(LOG_TAG, "model ${file.name} loaded in $ms ms, gpu mode ${params.gpuMode}, text model on " +
            if (onGpu) "GPU" else "CPU")
        LoadedModel(file.absolutePath, s.modelInfo(), ms, params, textOnGpu = onGpu).also { loaded = it }
    }

    suspend fun unload() = withContext(dispatcher) { unloadInternal() }

    private fun unloadInternal() {
        session?.close() // also frees the session's mmproj natively
        session = null
        loaded = null
        mmprojKey = null
    }

    /** Frees the vision projector (e.g. after it was removed from the model) but keeps the model. */
    suspend fun releaseMmproj() = withContext(dispatcher) { releaseMmprojInternal() }

    private fun releaseMmprojInternal() {
        session?.freeMmproj()
        mmprojKey = null
    }

    /**
     * Loads [mmproj] into [s] unless that exact projector / budget is already loaded; calls
     * [onLoading] first when it actually has to load. Engine thread only.
     *
     * Kept lazy on purpose: the projector costs ~0.4–0.7 GB more RAM (weights + encoder compute
     * buffers) that text-only users should not pay, and loading it takes well under a second.
     */
    private fun ensureMmproj(s: LlamaSession, mmproj: File, imageMaxTokens: Int, onLoading: () -> Unit) {
        val gpu = loaded?.params?.gpuMode?.encoderOnGpu == true
        val key = MmprojKey(mmproj.absolutePath, imageMaxTokens, gpu)
        if (mmprojKey == key && s.hasMmproj) return
        onLoading()
        releaseMmprojInternal()
        // The vision encoder is compute bound: it gets the prefill (performance core) thread count.
        val threads = loaded?.params?.nThreadsBatch ?: LoadParams.defaultBatchThreads()
        val t0 = SystemClock.elapsedRealtime()
        gpuGuard.guarded(gpu) { s.loadMmproj(mmproj.absolutePath, threads, imageMaxTokens, useGpu = gpu) }
        Log.i(LOG_TAG, "mmproj ${mmproj.name} loaded in ${SystemClock.elapsedRealtime() - t0} ms " +
            "(threads=$threads, image_max_tokens=$imageMaxTokens, encoder on ${if (s.encoderOnGpu) "GPU" else "CPU"})")
        mmprojKey = key
    }

    /** Thread-safe stop request for the running generation. */
    fun cancel() {
        session?.cancel()
    }

    /**
     * Wraps [userPrompt] in [profile]'s chat format and streams the translation.
     */
    fun translate(
        profile: ModelProfile,
        userPrompt: String,
        sampling: SamplingParams = profile.sampling,
    ): Flow<GenerationEvent> = channelFlow {
        val s = session ?: throw IllegalStateException("No model loaded")
        val prompt = formatPrompt(s, profile.chatFormat, userPrompt)
        val raw = StringBuilder()
        val cleaner = OutputCleaner(profile.stopStrings)
        var hitStop = false
        val stats = s.generate(prompt, sampling) { piece ->
            raw.append(piece)
            val cleaned = cleaner.clean(raw)
            hitStop = cleaned.hitStop
            trySend(GenerationEvent.Partial(cleaned.text))
            !hitStop && !isClosedForSend
        }
        val finalText = cleaner.clean(raw).text
        send(GenerationEvent.Done(finalText, stats))
    }.buffer(Channel.CONFLATED).flowOn(dispatcher)

    /**
     * Translates the text in [image]. [userPrompt] must contain LlamaSession.MEDIA_MARKER; it is
     * wrapped in [profile]'s chat format like a text prompt. The vision projector [mmproj] is
     * loaded lazily on first use (and kept until the model is unloaded or [releaseMmproj]).
     */
    fun translateImage(
        profile: ModelProfile,
        mmproj: File,
        imageMaxTokens: Int,
        userPrompt: String,
        image: RgbImage,
        sampling: SamplingParams = profile.sampling,
    ): Flow<GenerationEvent> = channelFlow {
        val s = session ?: throw IllegalStateException("No model loaded")
        val t0 = SystemClock.elapsedRealtime()
        // Backend labels only when GPU acceleration is on for this model, so CPU users see no change.
        val gpuOn = loaded?.params?.gpuMode?.encoderOnGpu == true
        var encodeBackend: String? = null
        var prefillBackend: String? = null
        fun stage(st: ImageStage) {
            val backend = when (st) {
                ImageStage.ENCODING -> encodeBackend
                ImageStage.PREFILL -> prefillBackend
                ImageStage.LOADING_MMPROJ -> null
            }
            trySend(GenerationEvent.Stage(st, SystemClock.elapsedRealtime(), backend))
        }
        ensureMmproj(s, mmproj, imageMaxTokens) { stage(ImageStage.LOADING_MMPROJ) }
        if (gpuOn) {
            encodeBackend = if (s.encoderOnGpu) "GPU" else "CPU"
            prefillBackend = if (s.textOnGpu) "GPU" else "CPU"
        }
        val prompt = formatPrompt(s, profile.chatFormat, userPrompt)
        val raw = StringBuilder()
        val cleaner = OutputCleaner(profile.stopStrings)
        var hitStop = false
        var firstTokenLogged = false
        val onStage = StageListener { st ->
            when (st) {
                StageListener.STAGE_ENCODE -> stage(ImageStage.ENCODING)
                StageListener.STAGE_PREFILL -> stage(ImageStage.PREFILL)
                // STAGE_GENERATE: the prefill label stays until the first token replaces it.
            }
        }
        Log.i(LOG_TAG, "image ${image.width}x${image.height}, image_max_tokens=$imageMaxTokens")
        val stats = s.generateWithImage(prompt, image, sampling, onStage) { piece ->
            if (!firstTokenLogged) {
                firstTokenLogged = true
                Log.i(LOG_TAG, "first token after ${SystemClock.elapsedRealtime() - t0} ms")
            }
            raw.append(piece)
            val cleaned = cleaner.clean(raw)
            hitStop = cleaned.hitStop
            trySend(GenerationEvent.Partial(cleaned.text))
            !hitStop && !isClosedForSend
        }
        val finalText = cleaner.clean(raw).text
        send(GenerationEvent.Done(finalText, stats))
    }.buffer(Channel.CONFLATED).flowOn(dispatcher)

    private companion object {
        /** Image translation timings: `adb logcat -s NekoImg`. */
        const val LOG_TAG = "NekoImg"
    }

    private fun formatPrompt(s: LlamaSession, format: ChatFormat, userPrompt: String): String = when (format) {
        is ChatFormat.Wrapper -> format.template.replace("{prompt}", userPrompt)
        ChatFormat.Raw -> userPrompt
        ChatFormat.GgufTemplate ->
            s.applyChatTemplate(listOf(ChatMessage("user", userPrompt)), addAssistant = true)
                ?: userPrompt // template unknown to llama_chat_apply_template: fall back to raw
    }
}
