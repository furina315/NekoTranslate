package com.nekohome.translate.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nekohome.translate.diag.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Which text box is being read aloud. */
enum class SpeakTarget { SOURCE, OUTPUT }

/** Why reading aloud could not start (shown as a snackbar that can open the system TTS settings). */
sealed interface SpeakProblem {
    /** No TTS engine installed / the system default failed to start. */
    data object NoEngine : SpeakProblem

    /** The engine the user picked ([packageName]) failed to start (onInit ERROR). */
    data class EngineInitFailed(val packageName: String) : SpeakProblem

    /**
     * The engine has no voice for [code]; [missingData] = supported but the voice data isn't
     * downloaded. [chinese] = Chinese text and the engine lacks a Chinese voice altogether
     * (LANG_MISSING_DATA / LANG_NOT_SUPPORTED): worth suggesting another engine. Only reported for
     * the system default engine (see [TtsVoicePlan.onNoMatch]).
     */
    data class MissingVoice(val code: String, val missingData: Boolean, val chinese: Boolean = false) : SpeakProblem

    /** speak() was refused or the engine reported an error ([errorCode], TextToSpeech.ERROR_*). */
    data class Failed(val errorCode: Int = TextToSpeech.ERROR) : SpeakProblem

    /** The engine took the text but never started it (engine process died or hung). */
    data object NoResponse : SpeakProblem
}

/** Progress worth telling the user while a (slow, third-party) engine starts. */
enum class SpeakNotice {
    /** Still binding / loading its model after a moment: 「朗读引擎加载中…」. */
    ENGINE_LOADING,
    /** Not ready after [TtsInitGate.timeoutMs]; the request stays queued a while longer. */
    ENGINE_SLOW,
    /** Gave up waiting ([TtsInitGate.giveUpMs]). */
    ENGINE_GAVE_UP,
}

/**
 * Thin wrapper around a [TextToSpeech] engine (the system default, or the one picked with
 * [setEngine]). The engine is created lazily on the first request (binding it costs a few hundred
 * ms and most sessions never speak) and released by [shutdown] or when the engine changes. A
 * request made while the engine starts is queued until onInit ([TtsInitGate]). Call everything
 * from the main thread; [speaking] may change from engine threads.
 *
 * Diagnostics: `adb logcat -s NekoTts`.
 */
class Speaker(
    context: Context,
    private val onProblem: (SpeakProblem) -> Unit,
    private val onNotice: (SpeakNotice) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _speaking = MutableStateFlow<SpeakTarget?>(null)

    /** The box being read (or about to be, while the engine starts), else null. */
    val speaking: StateFlow<SpeakTarget?> = _speaking.asStateFlow()

    private val _highlight = MutableStateFlow<SpeakHighlight?>(null)

    /** Sentence (and, for engines reporting ranges, word) being read; null when idle. */
    val highlight: StateFlow<SpeakHighlight?> = _highlight.asStateFlow()

    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f

    /** Speech rate / pitch for the next request (see [SpeechPlan.RATE_STOPS]). */
    fun setTuning(rate: Float, pitch: Float) {
        this.rate = rate
        this.pitch = pitch
    }

    /** Spans of the session being read, and whether the engine reports word ranges. */
    @Volatile private var spans: List<SpeechSpan> = emptyList()
    @Volatile private var spanTarget = SpeakTarget.OUTPUT

    private class Request(val target: SpeakTarget, val text: String, val code: String)

    private var tts: TextToSpeech? = null

    /** Engine package to bind (null = system default) and the identity of the current binding. */
    private var enginePackage: String? = null
    private var bindingKey: String? = null

    /** [bindingKey] / package the live [tts] instance was created with. */
    private var instanceKey: String? = null
    private var instancePackage: String? = null

    private val gate = TtsInitGate<Request>()

    /** Bumped on every start / stop; utterance ids carry it so stale callbacks are ignored. */
    @Volatile
    private var session = 0

    /** Session whose first utterance has reported onStart (watchdog). */
    @Volatile
    private var startedSession = -1

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
        gate.cancel()
        main.removeCallbacks(initTicker)
        main.removeCallbacks(watchdog)
        if (gate.state == TtsInitGate.State.READY) runCatching { tts?.stop() }
        _speaking.value = null
        _highlight.value = null
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
        runCatching { tts?.shutdown() }
        tts = null
        gate.reset()
    }

    private fun speak(request: Request) {
        stop()
        if (request.text.isBlank()) return
        // Show the button as active right away; a queued request runs once the engine is up.
        _speaking.value = request.target
        when (val action = gate.request(request, SystemClock.elapsedRealtime())) {
            is TtsInitGate.Action.Speak -> tts?.let { start(it, action.request) } ?: run {
                gate.reset()
                speak(request)
            }
            is TtsInitGate.Action.Queued -> scheduleTicks()
            is TtsInitGate.Action.StartInit -> {
                createEngine()
                scheduleTicks()
            }
        }
    }

    private fun createEngine() {
        runCatching { tts?.shutdown() }
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(created, status) } }
        val pkg = enginePackage
        AppLog.i(TAG, "binding engine ${pkg ?: "(system default)"}")
        created = try {
            if (pkg != null) TextToSpeech(appContext, listener, pkg) else TextToSpeech(appContext, listener)
        } catch (e: Exception) {
            AppLog.w(TAG, "creating TextToSpeech for ${pkg ?: "(system default)"} failed", e)
            null
        }
        tts = created
        instanceKey = bindingKey
        instancePackage = pkg
        if (created == null) onInit(null, TextToSpeech.ERROR)
    }

    private fun scheduleTicks() {
        main.removeCallbacks(initTicker)
        main.postDelayed(initTicker, TICK_MS)
    }

    private val initTicker = object : Runnable {
        override fun run() {
            when (gate.onTick(SystemClock.elapsedRealtime())) {
                TtsInitGate.Tick.LOADING -> onNotice(SpeakNotice.ENGINE_LOADING)
                TtsInitGate.Tick.TIMEOUT -> {
                    AppLog.w(TAG, "engine ${instancePackage ?: "(system default)"} not ready after ${gate.timeoutMs} ms")
                    onNotice(SpeakNotice.ENGINE_SLOW)
                }
                TtsInitGate.Tick.GIVE_UP -> {
                    AppLog.w(TAG, "engine ${instancePackage ?: "(system default)"} not ready after ${gate.giveUpMs} ms, request dropped")
                    _speaking.value = null
                    onNotice(SpeakNotice.ENGINE_GAVE_UP)
                    return
                }
                TtsInitGate.Tick.NONE -> Unit
            }
            if (gate.state == TtsInitGate.State.INITIALIZING && gate.pending != null) main.postDelayed(this, TICK_MS)
        }
    }

    private fun onInit(instance: TextToSpeech?, status: Int) {
        // A callback from an instance released by an engine switch: ignore it.
        if (instance != null && instance !== tts) {
            runCatching { instance.shutdown() }
            return
        }
        main.removeCallbacks(initTicker)
        val engine = tts
        val pkg = instancePackage
        if (status != TextToSpeech.SUCCESS || engine == null) {
            AppLog.w(TAG, "onInit failed: engine=${pkg ?: "(system default)"} status=$status")
            runCatching { engine?.shutdown() }
            tts = null
            val request = gate.onInit(success = false)
            gate.reset()
            _speaking.value = null
            if (request != null) onProblem(if (pkg != null) SpeakProblem.EngineInitFailed(pkg) else SpeakProblem.NoEngine)
            return
        }
        AppLog.i(TAG, "onInit ok: engine=${pkg ?: "(system default)"}")
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(listener)
        val request = gate.onInit(success = true)
        if (request != null) start(engine, request) else if (_speaking.value != null && gate.pending == null) {
            // Nothing queued any more (stopped while starting).
            _speaking.value = null
        }
    }

    /** Sets the language for [request]; false when it must not be spoken (problem reported). */
    private fun chooseVoice(engine: TextToSpeech, request: Request): Boolean {
        val locale = TtsLocales.localeFor(request.code)
        val explicit = instancePackage != null
        val availability = ArrayList<Int>(4)
        for (l in TtsVoicePlan.candidates(locale)) {
            val avail = runCatching { engine.isLanguageAvailable(l) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            availability += avail
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                val set = runCatching { engine.setLanguage(l) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (set >= TextToSpeech.LANG_AVAILABLE) return true
                availability += set
            }
        }
        // Some engines only expose voices (or match odd locale codes): pick one by language.
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet()).map { v ->
            VoiceInfo(
                name = v.name,
                locale = v.locale,
                needsNetwork = v.isNetworkConnectionRequired,
                notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in v.features.orEmpty(),
            )
        }
        val voice = TtsVoicePlan.pickVoice(voices, locale)
        if (voice != null) {
            val v = runCatching { engine.voices.firstOrNull { it.name == voice.name } }.getOrNull()
            if (v != null && runCatching { engine.setVoice(v) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) {
                AppLog.i(TAG, "language ${locale.toLanguageTag()} via voice ${voice.name} (${voice.locale})")
                return true
            }
        }
        val ids = availability.joinToString()
        return when (val d = TtsVoicePlan.onNoMatch(explicit, locale, availability)) {
            NoMatchDecision.EngineDefault -> {
                AppLog.i(TAG, "engine $instancePackage has no ${locale.toLanguageTag()} voice (results $ids, " +
                    "${voices.size} voices); speaking with its default voice")
                runCatching { engine.defaultVoice }.getOrNull()?.let { dv -> runCatching { engine.setVoice(dv) } }
                true
            }
            is NoMatchDecision.Block -> {
                AppLog.i(TAG, "system engine has no ${locale.toLanguageTag()} voice (results $ids)")
                _speaking.value = null
                onProblem(SpeakProblem.MissingVoice(code = request.code, missingData = d.missingData, chinese = d.chinese))
                false
            }
        }
    }

    private fun start(engine: TextToSpeech, request: Request) {
        if (!chooseVoice(engine, request)) return
        val maxLen = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK)
        val sentences = SpeechPlan.split(request.text, maxLen)
        val chunks = sentences.map { request.text.substring(it.start, it.end) }
        if (chunks.isEmpty()) {
            _speaking.value = null
            return
        }
        val sid = ++session
        spans = sentences
        spanTarget = request.target
        runCatching {
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
        }
        _speaking.value = request.target
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = runCatching { engine.speak(chunk, mode, params, utteranceId(sid, i, chunks.size)) }
                .getOrDefault(TextToSpeech.ERROR)
            if (result != TextToSpeech.SUCCESS) {
                AppLog.w(TAG, "speak() refused: engine=${instancePackage ?: "(system default)"} result=$result chunk=$i/${chunks.size}")
                stop()
                onProblem(SpeakProblem.Failed(result))
                return
            }
        }
        AppLog.i(TAG, "speak ${request.target}: ${chunks.size} utterance(s), ${request.text.length} chars, lang ${request.code}, " +
            "rate $rate pitch $pitch, engine ${instancePackage ?: "(system default)"}")
        // Watchdog: an engine whose process died / hung never calls back at all.
        watchdogSession = sid
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, NO_START_MS)
    }

    @Volatile
    private var watchdogSession = -1

    /** One Runnable (removed by [stop]) instead of a lambda per request kept alive for 20 s. */
    private val watchdog = Runnable {
        val sid = watchdogSession
        if (sid == session && startedSession != sid && _speaking.value != null) {
            AppLog.w(TAG, "engine ${instancePackage ?: "(system default)"} accepted the text but never started (${NO_START_MS} ms)")
            stop()
            onProblem(SpeakProblem.NoResponse)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (sid, i) = parse(utteranceId) ?: return
            startedSession = sid
            val sp = spans.getOrNull(i) ?: return
            if (sid == session) _highlight.value = SpeakHighlight(spanTarget, sp)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (sid, i) = parse(utteranceId) ?: return
            if (sid != session) return
            val sp = spans.getOrNull(i) ?: return
            val word = SpeechPlan.wordSpan(sp, start, end) ?: return
            _highlight.value = SpeakHighlight(spanTarget, sp, word)
        }

        override fun onDone(utteranceId: String?) {
            val (sid, index, count) = parse(utteranceId) ?: return
            if (sid != session || index != count - 1) return
            main.post { if (sid == session) { _speaking.value = null; _highlight.value = null } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            AppLog.w(TAG, "onError: engine=${instancePackage ?: "(system default)"} utterance=$utteranceId errorCode=$errorCode")
            val (sid) = parse(utteranceId) ?: return
            if (sid != session) return
            main.post {
                if (sid == session) {
                    stop()
                    onProblem(SpeakProblem.Failed(errorCode))
                }
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }

    private val instanceTag = Integer.toHexString(System.identityHashCode(this))

    /** Unique per speak and per Speaker instance: `<sid>:<index>:<count>:<instance>`. */
    private fun utteranceId(sid: Int, index: Int, count: Int) = "$sid:$index:$count:$instanceTag"

    private fun parse(id: String?): List<Int>? =
        id?.split(':')?.take(3)?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }

    private companion object {
        const val TAG = "NekoTts"

        /** Shorter utterances start sooner; the engine queues the rest seamlessly. */
        const val MAX_CHUNK = 1000
        const val TICK_MS = 500L
        const val NO_START_MS = 20_000L
    }
}

/** Opens the system 文字转语音输出 page (preferred engine), falling back to the Settings app. */
fun openSystemTtsSettings(context: Context) = openFirst(
    context, Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS),
)

/** Opens the system TTS settings (engine choice, voice downloads), falling back to Settings. */
fun openTtsSettings(context: Context) = openFirst(
    context,
    Intent("com.android.settings.TTS_SETTINGS"),
    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
    Intent(Settings.ACTION_SETTINGS),
)

private fun openFirst(context: Context, vararg intents: Intent) {
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
