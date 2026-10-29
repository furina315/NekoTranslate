package com.nekohome.translate.data

import android.content.Intent

/** Text to translate in the quick-translate card, and whether 「替换」 may write a result back. */
data class QuickRequest(val text: String, val replaceable: Boolean)

/** Cleans text arriving from PROCESS_TEXT / ACTION_SEND and decides how the card handles it. */
object IncomingText {
    const val MAX_CHARS = 20_000

    /** Trimmed text capped at [MAX_CHARS], or null when blank. */
    fun clean(raw: CharSequence?): String? {
        val t = raw?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length > MAX_CHARS) t.substring(0, MAX_CHARS) else t
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
        Intent.ACTION_PROCESS_TEXT -> clean(processText)?.let { QuickRequest(it, canReplace(action, readOnly, forResult)) }
        Intent.ACTION_SEND ->
            if (mimeType == null || mimeType.startsWith("text/")) clean(sendText)?.let { QuickRequest(it, replaceable = false) }
            else null
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
