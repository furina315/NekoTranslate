package com.nekohome.translate.data

/** What the quick-translate card hands to the main screen for 「在 App 中打开」. */
data class HandoffPayload(
    val source: String,
    val translation: String?,
    val targetCode: String?,
    /** Length of the text the card originally received (> source.length when it was cut). */
    val originalLength: Int = source.length,
)

/**
 * In-process hand-off from the quick-translate card to MainActivity. Both run in the same process,
 * so the (possibly very long) text is kept here and only an id travels in the Intent: a Binder
 * transaction is limited to ~1 MB shared by everything in flight, and a long source plus its
 * translation as Intent extras could throw TransactionTooLargeException. Short texts are also put
 * inline ([inlineFits]) as a fallback for a process that was restarted in between.
 */
object TextHandoff {
    /** Chars (source + translation) still sent inline as Intent extras: ~64 KB as UTF-16 in a Parcel. */
    const val INLINE_MAX_CHARS = 32_000
    private const val CAPACITY = 4

    private val entries = LinkedHashMap<Long, HandoffPayload>()
    private var nextId = 1L

    /** Stores [payload] and returns its id (oldest entries beyond [CAPACITY] are dropped). */
    @Synchronized
    fun put(payload: HandoffPayload): Long {
        val id = nextId++
        entries[id] = payload
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
        return id
    }

    /** The payload for [id], removed so it is handed over once; null when unknown (e.g. after a process restart). */
    @Synchronized
    fun take(id: Long): HandoffPayload? = if (id <= 0) null else entries.remove(id)

    /** True when [payload] is small enough to also travel inline in the Intent. */
    fun inlineFits(payload: HandoffPayload): Boolean =
        payload.source.length + (payload.translation?.length ?: 0) <= INLINE_MAX_CHARS
}
