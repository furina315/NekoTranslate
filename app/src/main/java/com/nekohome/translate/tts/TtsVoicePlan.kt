package com.nekohome.translate.tts

import android.speech.tts.TextToSpeech
import java.util.Locale

/** A voice as [TextToSpeech.getVoices] reports it, minus the Android type (unit-testable). */
data class VoiceInfo(
    val name: String,
    val locale: Locale,
    val needsNetwork: Boolean = false,
    /** The engine lists the voice but its data is not downloaded (KEY_FEATURE_NOT_INSTALLED). */
    val notInstalled: Boolean = false,
)

/** What to do when no locale / voice of the wanted language could be set. */
sealed interface NoMatchDecision {
    /**
     * Speak with whatever the engine has (its default voice). Used when the user explicitly picked
     * an engine: third-party engines (e.g. sherpa-onnx Kokoro multi-lang, which declares only
     * `eng` + `zho`) often answer NOT_SUPPORTED for locales their single voice reads fine.
     */
    data object EngineDefault : NoMatchDecision

    /** Do not speak; report the missing voice (the system default engine lacks the language). */
    data class Block(val missingData: Boolean, val chinese: Boolean) : NoMatchDecision
}

/**
 * Pure decisions for choosing a TTS language / voice (unit-tested on the JVM). The order tried by
 * [com.nekohome.translate.tts.Speaker]: every locale of [candidates] with setLanguage, then the
 * best voice from [pickVoice] with setVoice, then [onNoMatch].
 */
object TtsVoicePlan {

    /**
     * Locales to try with isLanguageAvailable / setLanguage, best first: the locale itself
     * (zh-CN), its fallback (Cantonese -> zh-HK, region -> bare language), the bare language and
     * the ISO 639-2 three-letter code (`zho`, `eng`) some engines match literally.
     */
    fun candidates(locale: Locale): List<Locale> {
        val out = LinkedHashSet<Locale>()
        out += locale
        TtsLocales.fallbackFor(locale)?.let { out += it }
        if (locale.language.isNotEmpty()) out += Locale.Builder().setLanguage(locale.language).build()
        iso3(locale)?.takeIf { it != locale.language }?.let { iso -> runCatching { Locale.Builder().setLanguage(iso).build() }.getOrNull()?.let { out += it } }
        return out.toList()
    }

    /** ISO 639-2/T code of [locale]'s language, or null when Java doesn't know it. */
    fun iso3(locale: Locale): String? =
        runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Same language, comparing ISO-3 codes so `zh` matches `zho` and `en` matches `eng`. */
    fun sameLanguage(a: Locale, b: Locale): Boolean {
        if (a.language.equals(b.language, ignoreCase = true)) return true
        val ia = iso3(a) ?: return false
        val ib = iso3(b) ?: return false
        return ia.equals(ib, ignoreCase = true)
    }

    /**
     * How well [voice] fits [wanted]: 3 = language and region, 2 = language, 1 = both Chinese
     * family (a Mandarin voice for Cantonese text and the like), 0 = no fit.
     */
    fun score(voice: Locale, wanted: Locale): Int = when {
        sameLanguage(voice, wanted) && wanted.country.isNotEmpty() &&
            voice.country.equals(wanted.country, ignoreCase = true) -> 3
        sameLanguage(voice, wanted) -> 2
        TtsEngineChoice.isChinese(voice) && TtsEngineChoice.isChinese(wanted) -> 1
        else -> 0
    }

    /** Best installed, offline voice for [wanted], or null. Ties keep the engine's order. */
    fun pickVoice(voices: List<VoiceInfo>, wanted: Locale): VoiceInfo? =
        voices.asSequence()
            .filter { !it.notInstalled && !it.needsNetwork }
            .map { it to score(it.locale, wanted) }
            .filter { it.second > 0 }
            .fold(null as Pair<VoiceInfo, Int>?) { best, cur -> if (best == null || cur.second > best.second) cur else best }
            ?.first

    /**
     * Nothing matched [wanted]. With an engine the user chose explicitly ([explicitEngine]) speak
     * anyway with its default voice; with the system default engine block and report it
     * ([availability] = the isLanguageAvailable / setLanguage results seen).
     */
    fun onNoMatch(explicitEngine: Boolean, wanted: Locale, availability: List<Int>): NoMatchDecision =
        if (explicitEngine) {
            NoMatchDecision.EngineDefault
        } else {
            NoMatchDecision.Block(
                missingData = TextToSpeech.LANG_MISSING_DATA in availability,
                chinese = TtsEngineChoice.lacksChineseVoice(wanted, availability),
            )
        }

    /**
     * Whether the 「安装中文语音」 sheet may open: only for the system default engine. Once the
     * user picked an engine themselves the sheet must not keep coming back.
     */
    fun mayShowChineseVoiceSheet(explicitEngine: Boolean, hintDismissed: Boolean): Boolean =
        !explicitEngine && !hintDismissed
}

/**
 * The request made while a [TextToSpeech] engine is still binding / initialising. Third-party
 * engines can take many seconds the first time (sherpa-onnx copies its espeak-ng data and loads a
 * ~330 MB model in the service's onCreate), so the request is queued until onInit instead of
 * dropped, the user is told it is loading after [slowNoticeMs], warned after [timeoutMs] and the
 * request is given up after [giveUpMs]. Pure: the caller supplies the clock and runs the actions.
 */
class TtsInitGate<R : Any>(
    val slowNoticeMs: Long = 1_500,
    val timeoutMs: Long = 15_000,
    val giveUpMs: Long = 60_000,
) {
    enum class State { IDLE, INITIALIZING, READY, FAILED }

    sealed interface Action<out R> {
        /** Engine ready: speak now. */
        data class Speak<R>(val request: R) : Action<R>
        /** Create the engine; the request waits for onInit. */
        data class StartInit<R>(val request: R) : Action<R>
        /** The engine is already starting; the request (replacing any older one) waits for it. */
        data class Queued<R>(val request: R) : Action<R>
    }

    enum class Tick { NONE, LOADING, TIMEOUT, GIVE_UP }

    var state: State = State.IDLE
        private set

    var pending: R? = null
        private set

    private var since = 0L
    private var loadingShown = false
    private var timeoutShown = false

    fun request(request: R, nowMs: Long): Action<R> = when (state) {
        State.READY -> {
            pending = null
            Action.Speak(request)
        }
        State.INITIALIZING -> {
            pending = request
            Action.Queued(request)
        }
        State.IDLE, State.FAILED -> {
            state = State.INITIALIZING
            pending = request
            since = nowMs
            loadingShown = false
            timeoutShown = false
            Action.StartInit(request)
        }
    }

    /** Stop pressed / text changed: forget the queued request (the engine keeps starting). */
    fun cancel() {
        pending = null
    }

    /** onInit arrived. Returns the queued request to speak (success) or to report (failure). */
    fun onInit(success: Boolean): R? {
        if (state != State.INITIALIZING) return null
        state = if (success) State.READY else State.FAILED
        return pending.also { pending = null }
    }

    /** Clock tick while initialising: what to tell the user (each notice once). */
    fun onTick(nowMs: Long): Tick {
        if (state != State.INITIALIZING || pending == null) return Tick.NONE
        val elapsed = nowMs - since
        return when {
            elapsed >= giveUpMs -> {
                pending = null
                Tick.GIVE_UP
            }
            elapsed >= timeoutMs && !timeoutShown -> {
                timeoutShown = true
                loadingShown = true
                Tick.TIMEOUT
            }
            elapsed >= slowNoticeMs && !loadingShown -> {
                loadingShown = true
                Tick.LOADING
            }
            else -> Tick.NONE
        }
    }

    /** The engine instance was released (engine switch / shutdown). */
    fun reset() {
        state = State.IDLE
        pending = null
    }
}
