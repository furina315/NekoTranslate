package com.nekohome.translate.data

/** Cleans text arriving from PROCESS_TEXT / ACTION_SEND. */
object IncomingText {
    const val MAX_CHARS = 20_000

    /** Trimmed text capped at [MAX_CHARS], or null when blank. */
    fun clean(raw: CharSequence?): String? {
        val t = raw?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length > MAX_CHARS) t.substring(0, MAX_CHARS) else t
    }
}
