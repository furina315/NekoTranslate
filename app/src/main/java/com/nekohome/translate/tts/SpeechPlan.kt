package com.nekohome.translate.tts

/** A sentence to read: [start] inclusive, [end] exclusive, as char offsets into the original text. */
data class SpeechSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/** What is highlighted while reading: the sentence, and (engines that report it) the current word. */
data class SpeakHighlight(
    val target: SpeakTarget,
    val sentence: SpeechSpan,
    val word: SpeechSpan? = null,
)

/** Splitting text into sentences for per-sentence utterances, and rate / pitch rules. */
object SpeechPlan {
    private const val STRONG = "。！？!?；;…\n"
    private const val WEAK = "，,、：:"
    private const val CLOSERS = "\"'”’）)」』】》"

    /**
     * Sentence spans of [text] (trimmed of whitespace), each at most [maxLen] chars. Splits after
     * 。！？；.!? … and line breaks (a '.' only before whitespace/end, so 3.14 and a.b stay whole);
     * an over-long sentence is cut after ，,、 then whitespace, then hard.
     */
    fun split(text: String, maxLen: Int): List<SpeechSpan> {
        require(maxLen > 0)
        val out = ArrayList<SpeechSpan>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val boundary = c in STRONG || (c == '.' && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in CLOSERS))
            if (boundary) {
                var end = i + 1
                while (end < text.length && (text[end] in STRONG.replace("\n", "") || text[end] == '.' || text[end] in CLOSERS)) end++
                addTrimmed(text, start, end, maxLen, out)
                start = end
                i = end
            } else i++
        }
        addTrimmed(text, start, text.length, maxLen, out)
        return out
    }

    private fun addTrimmed(text: String, from: Int, to: Int, maxLen: Int, out: MutableList<SpeechSpan>) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return
        while (e - s > maxLen) {
            val window = text.substring(s, s + maxLen)
            var cut = window.indexOfLast { it in WEAK }
            if (cut < maxLen / 3) cut = window.lastIndexOf(' ')
            val end = if (cut < maxLen / 3) s + maxLen else s + cut + 1
            addTrimmed(text, s, end, maxLen, out)
            s = end
            while (s < e && text[s].isWhitespace()) s++
        }
        if (s < e) out += SpeechSpan(s, e)
    }

    /** Index of the sentence containing char [offset], or -1. */
    fun indexAt(spans: List<SpeechSpan>, offset: Int): Int = spans.indexOfFirst { offset >= it.start && offset < it.end }

    val RATE_STOPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val PITCH_STOPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

    fun nearestIndex(stops: List<Float>, v: Float): Int =
        stops.indices.minByOrNull { kotlin.math.abs(stops[it] - v) } ?: 0

    /** The sherpa-onnx engine reads only the speech rate; pitch has no effect there. */
    fun pitchSupported(enginePackage: String?): Boolean = enginePackage?.contains("com.k2fsa.sherpa.onnx") != true

    /** Absolute span of a word reported as [startInUtterance, endInUtterance) of sentence [s]. */
    fun wordSpan(s: SpeechSpan, startInUtterance: Int, endInUtterance: Int): SpeechSpan? {
        val a = s.start + startInUtterance
        val b = s.start + endInUtterance
        return if (startInUtterance >= 0 && endInUtterance > startInUtterance && b <= s.end) SpeechSpan(a, b) else null
    }
}
