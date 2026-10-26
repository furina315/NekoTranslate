package com.nekohome.translate.tts

import java.util.Locale

/**
 * Maps the app's language codes to the [Locale] a system TTS engine expects, and guesses the
 * language of text whose source is 「自动检测」 (by Unicode script; good enough to pick a voice).
 */
object TtsLocales {

    /** BCP-47 tag a TTS engine understands for an app language code. */
    fun tagFor(code: String): String = when (code) {
        "zh" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        "yue" -> "yue-HK"
        "en" -> "en-US"
        "pt" -> "pt-BR"
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "tl", "fil" -> "fil-PH"
        "nb" -> "nb-NO"
        "ms" -> "ms-MY"
        else -> code
    }

    fun localeFor(code: String): Locale = Locale.forLanguageTag(tagFor(code))

    /**
     * Fallback when an engine has no voice for [locale]: Cantonese -> Hong Kong Chinese voices,
     * a regional variant -> the bare language. Null when there is nothing else to try.
     */
    fun fallbackFor(locale: Locale): Locale? = when {
        locale.language == "yue" -> Locale.forLanguageTag("zh-HK")
        locale.country.isNotEmpty() -> Locale.forLanguageTag(locale.language)
        else -> null
    }

    /**
     * Best-effort language code of [text] from its dominant script, for an auto-detected source.
     * Kana wins over Han (Japanese mixes both); Latin and anything unknown fall back to [latinDefault].
     */
    fun guessCode(text: String, latinDefault: String = "en"): String {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        var seen = 0
        while (i < text.length && seen < SAMPLE) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            seen++
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (counts.isEmpty()) return latinDefault
        val kana = (counts[Character.UnicodeScript.HIRAGANA] ?: 0) + (counts[Character.UnicodeScript.KATAKANA] ?: 0)
        if (kana > 0 && kana * 10 >= seen) return "ja"
        val top = counts.maxByOrNull { it.value }!!.key
        return when (top) {
            Character.UnicodeScript.HAN -> "zh"
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
            Character.UnicodeScript.HANGUL -> "ko"
            Character.UnicodeScript.CYRILLIC -> "ru"
            Character.UnicodeScript.ARABIC -> "ar"
            Character.UnicodeScript.HEBREW -> "he"
            Character.UnicodeScript.GREEK -> "el"
            Character.UnicodeScript.THAI -> "th"
            Character.UnicodeScript.DEVANAGARI -> "hi"
            Character.UnicodeScript.BENGALI -> "bn"
            Character.UnicodeScript.TAMIL -> "ta"
            Character.UnicodeScript.TELUGU -> "te"
            Character.UnicodeScript.GUJARATI -> "gu"
            Character.UnicodeScript.KHMER -> "km"
            Character.UnicodeScript.MYANMAR -> "my"
            Character.UnicodeScript.TIBETAN -> "bo"
            else -> latinDefault
        }
    }

    /**
     * Splits [text] into pieces no longer than [maxLen] (the engine's input limit), preferring
     * paragraph, then sentence, then whitespace boundaries. Blank pieces are dropped.
     */
    fun chunk(text: String, maxLen: Int): List<String> {
        require(maxLen > 0)
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > maxLen) {
            val window = rest.substring(0, maxLen)
            val cut = listOf(
                window.lastIndexOf("\n"),
                SENTENCE_END.findAll(window).lastOrNull()?.range?.last ?: -1,
                window.lastIndexOf(' '),
            ).firstOrNull { it >= maxLen / 2 }?.plus(1) ?: maxLen
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }

    private const val SAMPLE = 400
    private val SENTENCE_END = Regex("[。！？!?.;；…]")
}
