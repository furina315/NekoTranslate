package com.nekohome.translate.engine

import android.content.Context
import com.nekohome.translate.data.ChatFormat
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.llama.ChatMessage
import com.nekohome.translate.llama.GenerationStats
import com.nekohome.translate.llama.LlamaBackend
import com.nekohome.translate.llama.LlamaSession
import com.nekohome.translate.llama.LoadParams
import com.nekohome.translate.llama.ModelInfo
import com.nekohome.translate.llama.SamplingParams
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

sealed interface GenerationEvent {
    /** Text generated so far, already cleaned (think block / stop strings removed). */
    data class Partial(val text: String) : GenerationEvent
    data class Done(val text: String, val stats: GenerationStats) : GenerationEvent
}

data class LoadedModel(
    val path: String,
    val info: ModelInfo,
    val loadMs: Long,
    val params: LoadParams,
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

    @Volatile
    var loaded: LoadedModel? = null
        private set

    suspend fun systemInfo(): String = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        LlamaBackend.systemInfo()
    }

    /** Loads [file], replacing any loaded model. Throws on failure. */
    suspend fun load(file: File, params: LoadParams): LoadedModel = withContext(dispatcher) {
        LlamaBackend.init(appContext)
        unloadInternal()
        val t0 = System.nanoTime()
        val s = LlamaSession.load(file.absolutePath, params)
        val ms = (System.nanoTime() - t0) / 1_000_000
        session = s
        LoadedModel(file.absolutePath, s.modelInfo(), ms, params).also { loaded = it }
    }

    suspend fun unload() = withContext(dispatcher) { unloadInternal() }

    private fun unloadInternal() {
        session?.close()
        session = null
        loaded = null
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

    private fun formatPrompt(s: LlamaSession, format: ChatFormat, userPrompt: String): String = when (format) {
        is ChatFormat.Wrapper -> format.template.replace("{prompt}", userPrompt)
        ChatFormat.Raw -> userPrompt
        ChatFormat.GgufTemplate ->
            s.applyChatTemplate(listOf(ChatMessage("user", userPrompt)), addAssistant = true)
                ?: userPrompt // template unknown to llama_chat_apply_template: fall back to raw
    }
}
