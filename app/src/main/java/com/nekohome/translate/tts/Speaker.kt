package com.nekohome.translate.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Which text box is being read aloud. */
enum class SpeakTarget { SOURCE, OUTPUT }

/** Why reading aloud could not start (shown as a snackbar that can open the system TTS settings). */
sealed interface SpeakProblem {
    /** No TTS engine installed / it failed to start. */
    data object NoEngine : SpeakProblem

    /** The engine has no voice for [code]; [missingData] = supported but the voice data isn't downloaded. */
    data class MissingVoice(val code: String, val missingData: Boolean) : SpeakProblem

    data object Failed : SpeakProblem
}

/**
 * Thin wrapper around the system [TextToSpeech] engine. The engine is created lazily on the first
 * request (binding it costs a few hundred ms and most sessions never speak) and released by
 * [shutdown]. Call everything from the main thread; [speaking] may change from engine threads.
 */
class Speaker(context: Context, private val onProblem: (SpeakProblem) -> Unit) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _speaking = MutableStateFlow<SpeakTarget?>(null)

    /** The box being read (or about to be, while the engine starts), else null. */
    val speaking: StateFlow<SpeakTarget?> = _speaking.asStateFlow()

    private class Request(val target: SpeakTarget, val text: String, val code: String)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Request? = null

    /** Bumped on every start / stop; utterance ids carry it so stale callbacks are ignored. */
    @Volatile
    private var session = 0

    /** Starts reading [text] in language [code], or stops if [target] is already being read. */
    fun toggle(target: SpeakTarget, text: String, code: String) {
        if (_speaking.value == target) {
            stop()
        } else {
            speak(Request(target, text, code))
        }
    }

    fun stop() {
        session++
        pending = null
        if (ready) tts?.stop()
        _speaking.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun speak(request: Request) {
        stop()
        if (request.text.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            start(engine, request)
            return
        }
        // Show the button as active right away; the request runs once the engine is up.
        pending = request
        _speaking.value = request.target
        if (engine == null) {
            tts = try {
                TextToSpeech(appContext) { status -> main.post { onInit(status) } }
            } catch (e: Exception) {
                null
            }
            if (tts == null) onInit(TextToSpeech.ERROR)
        }
    }

    private fun onInit(status: Int) {
        val engine = tts
        val request = pending
        pending = null
        if (status != TextToSpeech.SUCCESS || engine == null) {
            engine?.shutdown()
            tts = null
            ready = false
            _speaking.value = null
            if (request != null) onProblem(SpeakProblem.NoEngine)
            return
        }
        ready = true
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(listener)
        if (request != null) start(engine, request)
    }

    private fun start(engine: TextToSpeech, request: Request) {
        val locale = TtsLocales.localeFor(request.code)
        var missingData = false
        val chosen = sequenceOf(locale, TtsLocales.fallbackFor(locale)).filterNotNull().firstOrNull { l ->
            val r = engine.isLanguageAvailable(l)
            if (r == TextToSpeech.LANG_MISSING_DATA) missingData = true
            r >= TextToSpeech.LANG_AVAILABLE && engine.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE
        }
        if (chosen == null) {
            _speaking.value = null
            onProblem(SpeakProblem.MissingVoice(request.code, missingData))
            return
        }
        val maxLen = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK)
        val chunks = TtsLocales.chunk(request.text, maxLen)
        if (chunks.isEmpty()) return
        val sid = ++session
        _speaking.value = request.target
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(chunk, mode, null, "$sid:$i:${chunks.size}") != TextToSpeech.SUCCESS) {
                stop()
                onProblem(SpeakProblem.Failed)
                return
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val (sid, index, count) = parse(utteranceId) ?: return
            if (sid != session || index != count - 1) return
            main.post { if (sid == session) _speaking.value = null }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (sid) = parse(utteranceId) ?: return
            if (sid != session) return
            main.post {
                if (sid == session) {
                    stop()
                    onProblem(SpeakProblem.Failed)
                }
            }
        }
    }

    private fun parse(id: String?): List<Int>? =
        id?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }

    private companion object {
        /** Shorter utterances start sooner; the engine queues the rest seamlessly. */
        const val MAX_CHUNK = 1000
    }
}

/** Opens the system TTS settings (engine choice, voice downloads), falling back to Settings. */
fun openTtsSettings(context: Context) {
    val intents = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

/** Display name for a language code that isn't in the active model's list. */
internal fun localeDisplayName(code: String): String =
    TtsLocales.localeFor(code).getDisplayName(Locale.SIMPLIFIED_CHINESE).ifBlank { code }
