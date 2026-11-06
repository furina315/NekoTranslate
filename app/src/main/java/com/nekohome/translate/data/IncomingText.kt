package com.nekohome.translate.data

import android.content.Intent

/**
 * Text to translate in the quick-translate card, and whether 「替换」 may write a result back.
 * [originalLength] is the length of the (trimmed) text that arrived; it is larger than
 * `text.length` only when the text was over [IncomingText.MAX_CHARS] and had to be cut.
 */
data class QuickRequest(val text: String, val replaceable: Boolean, val originalLength: Int = text.length) {
    val truncated: Boolean get() = originalLength > text.length
}

/** Cleaned incoming text; [originalLength] > `text.length` means it was cut at [IncomingText.MAX_CHARS]. */
data class CleanText(val text: String, val originalLength: Int) {
    val truncated: Boolean get() = originalLength > text.length
}

/** Cleans text arriving from PROCESS_TEXT / ACTION_SEND and decides how the card handles it. */
object IncomingText {
    /**
     * Safety cap for text arriving from other apps. Android's own TextView hands out at most
     * 100 000 chars (TextUtils.PARCEL_SAFE_TEXT_LENGTH); a Compose text field this tall (CJK at
     * default font size) is still well below the layout size limit at 50 000. Anything longer is
     * cut and the UI says so — never silently.
     */
    const val MAX_CHARS = 50_000

    /** Trimmed text capped at [MAX_CHARS], or null when blank. */
    fun clean(raw: CharSequence?): String? = cleanDetailed(raw)?.text

    /** Like [clean], but also reports whether (and from how long) the text was cut. */
    fun cleanDetailed(raw: CharSequence?): CleanText? {
        val t = raw?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= MAX_CHARS) return CleanText(t, t.length)
        return CleanText(cutSafely(t, MAX_CHARS).trimEnd(), t.length)
    }

    /** First [max] chars of [s], one less when that would split a surrogate pair (emoji). */
    fun cutSafely(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1]) && Character.isLowSurrogate(s[end])) end--
        return s.substring(0, end)
    }

    /**
     * What the quick-translate card should translate, or null (nothing usable: the card closes).
     *
     * @param action       the intent action (PROCESS_TEXT or SEND)
     * @param mimeType     the intent type (SEND: only text/plain is handled here; images go to the main screen)
     * @param processText  EXTRA_PROCESS_TEXT
     * @param sendText     EXTRA_TEXT
     * @param readOnly     EXTRA_PROCESS_TEXT_READONLY (absent = false)
     * @param forResult    the caller started us for a result (getCallingActivity() != null)
     */
    fun quickRequest(
        action: String?,
        mimeType: String?,
        processText: CharSequence?,
        sendText: CharSequence?,
        readOnly: Boolean,
        forResult: Boolean,
    ): QuickRequest? = when (action) {
        Intent.ACTION_PROCESS_TEXT ->
            cleanDetailed(processText)?.let { QuickRequest(it.text, canReplace(action, readOnly, forResult), it.originalLength) }
        Intent.ACTION_SEND ->
            if (mimeType == null || mimeType.startsWith("text/")) {
                cleanDetailed(sendText)?.let { QuickRequest(it.text, replaceable = false, originalLength = it.originalLength) }
            } else {
                null
            }
        else -> null
    }

    /**
     * 「替换」 only makes sense for an editable selection (READONLY false) whose app waits for our
     * result; a read-only selection or a share has nowhere to put the text.
     */
    fun canReplace(action: String?, readOnly: Boolean, forResult: Boolean): Boolean =
        action == Intent.ACTION_PROCESS_TEXT && !readOnly && forResult

    /** Text handed back as EXTRA_PROCESS_TEXT for 「替换」, or null when there is nothing to put back. */
    fun replacement(translation: String?): String? = translation?.trim()?.takeIf { it.isNotEmpty() }
}
