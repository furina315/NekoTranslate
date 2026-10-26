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

    /**
     * The engine has no voice for [code]; [missingData] = supported but the voice data isn't
     * downloaded. [chinese] = Chinese text and the engine lacks a Chinese voice altogether
     * (LANG_MISSING_DATA / LANG_NOT_SUPPORTED): worth suggesting another engine.
     */
    data class MissingVoice(val code: String, val missingData: Boolean, val chinese: Boolean = false) : SpeakProblem

    data object Failed : SpeakProblem
}

/**
 * Thin wrapper around a [TextToSpeech] engine (the system default, or the one picked with
 * [setEngine]). The engine is created lazily on the first request (binding it costs a few hundred
 * ms and most sessions never speak) and released by [shutdown] or when the engine changes. Call
 * everything from the main thread; [speaking] may change from engine threads.
 */
class Speaker(context: Context, private val onProblem: (SpeakProblem) -> Unit) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _speaking = MutableStateFlow<SpeakTarget?>(null)

    /** The box being read (or about to be, while the engine starts), else null. */
    val speaking: StateFlow<SpeakTarget?> = _speaking.asStateFlow()

    private class Request(val target: SpeakTarget, val text: String, val code: String)

    private var tts: TextToSpeech? = null

    /** Engine package to bind (null = system default) and the identity of the current binding. */
    private var enginePackage: String? = null
    private var bindingKey: String? = null

    /** [bindingKey] the live [tts] instance was created with. */
    private var instanceKey: String? = null
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

    /**
     * Uses [packageName] (null = the system default engine) from the next request on. When the
     * binding changes ([key], see [TtsEngineChoice.bindingKey]) the old instance is stopped and
     * released; the new one is created lazily like the first.
     */
    fun setEngine(packageName: String?, key: String = TtsEngineChoice.bindingKey(packageName, null)) {
        bindingKey = key
        enginePackage = packageName
        if (tts != null && instanceKey != key) shutdown()
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
            var created: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(created, status) } }
            created = try {
                val pkg = enginePackage
                if (pkg != null) TextToSpeech(appContext, listener, pkg) else TextToSpeech(appContext, listener)
            } catch (e: Exception) {
                null
            }
            tts = created
            instanceKey = bindingKey
            if (created == null) onInit(null, TextToSpeech.ERROR)
        }
    }

    private fun onInit(instance: TextToSpeech?, status: Int) {
        // A callback from an instance released by an engine switch: ignore it.
        if (instance != null && instance !== tts) {
            instance.shutdown()
            return
        }
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
        val availability = ArrayList<Int>(2)
        val chosen = sequenceOf(locale, TtsLocales.fallbackFor(locale)).filterNotNull().firstOrNull { l ->
            val r = engine.isLanguageAvailable(l)
            availability += r
            r >= TextToSpeech.LANG_AVAILABLE && engine.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE
        }
        if (chosen == null) {
            _speaking.value = null
            onProblem(
                SpeakProblem.MissingVoice(
                    code = request.code,
                    missingData = TextToSpeech.LANG_MISSING_DATA in availability,
                    chinese = TtsEngineChoice.lacksChineseVoice(locale, availability),
                ),
            )
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

/** Opens the system 文字转语音输出 page (preferred engine), falling back to the Settings app. */
fun openSystemTtsSettings(context: Context) {
    for (intent in listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
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
