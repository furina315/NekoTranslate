package com.nekohome.translate.engine

/**
 * Post-processing of streamed model output:
 * - drops a leading `<think>...</think>` block (hidden while still open),
 * - cuts at the first stop string,
 * - trims surrounding whitespace.
 */
class OutputCleaner(private val stopStrings: List<String>) {

    data class Result(val text: String, val hitStop: Boolean)

    fun clean(raw: CharSequence): Result {
        var s = raw.toString()
        val trimmedStart = s.trimStart()
        if (trimmedStart.startsWith(THINK_OPEN)) {
            val end = trimmedStart.indexOf(THINK_CLOSE)
            if (end < 0) return Result("", false) // still thinking
            s = trimmedStart.substring(end + THINK_CLOSE.length)
        } else if (s.contains(THINK_CLOSE)) {
            // Template pre-filled "<think>" so only the closing tag shows up.
            s = s.substringAfter(THINK_CLOSE)
        }
        var hit = false
        for (stop in stopStrings) {
            val i = s.indexOf(stop)
            if (i >= 0) {
                s = s.substring(0, i)
                hit = true
            }
        }
        return Result(s.trim(), hit)
    }

    companion object {
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"
    }
}
