package com.nekohome.translate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 翻译历史 on plain SQLite (no extra dependencies). One process-wide instance; every call runs on
 * the IO dispatcher. [changes] ticks after each write so observers can re-query.
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, VERSION) {

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_text TEXT NOT NULL,
                target_text TEXT NOT NULL,
                source_code TEXT NOT NULL,
                target_code TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                starred INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX idx_history_created ON history(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE history ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
    }

    /** Saves [entry] (its id is ignored); a repeat of the newest entry only refreshes its time. */
    suspend fun add(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val latest = queryRows(db, null, null, 1).firstOrNull()
            if (HistoryRules.isDuplicate(latest, entry) && latest != null) {
                db.update("history", ContentValues().apply { put("created_at", entry.createdAt) }, "id = ?", arrayOf(latest.id.toString()))
            } else {
                db.insert("history", null, values(entry, withId = false))
                // Keep only the newest MAX_ENTRIES rows.
                db.execSQL(
                    "DELETE FROM history WHERE starred = 0 AND id NOT IN (SELECT id FROM history WHERE starred = 0 ORDER BY created_at DESC, id DESC LIMIT ${HistoryRules.MAX_ENTRIES})",
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _changes.value++
    }

    /** Newest first; a blank [query] lists everything, otherwise matches source or translation text. */
    suspend fun search(
        query: String,
        starredOnly: Boolean = false,
        limit: Int = HistoryRules.SEARCH_LIMIT,
    ): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            val star = if (starredOnly) "starred = 1" else null
            if (q.isEmpty()) {
                queryRows(readableDatabase, star, null, limit)
            } else {
                val p = HistoryRules.likePattern(q)
                val match = "(source_text LIKE ? ESCAPE '\\' OR target_text LIKE ? ESCAPE '\\')"
                queryRows(
                    readableDatabase,
                    if (star != null) "$star AND $match" else match,
                    arrayOf(p, p),
                    limit,
                )
            }
        }

    suspend fun setStarred(id: Long, starred: Boolean) = withContext(Dispatchers.IO) {
        writableDatabase.update(
            "history", ContentValues().apply { put("starred", if (starred) 1 else 0) }, "id = ?", arrayOf(id.toString()),
        )
        _changes.value++
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        _changes.value++
    }

    /** Puts a deleted [entry] back with its original id and time (undo). */
    suspend fun restore(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("history", null, values(entry, withId = true), SQLiteDatabase.CONFLICT_REPLACE)
        _changes.value++
    }

    /** Removes everything except starred entries. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        writableDatabase.delete("history", "starred = 0", null)
        _changes.value++
    }

    private fun values(e: HistoryEntry, withId: Boolean) = ContentValues().apply {
        if (withId) put("id", e.id)
        put("source_text", e.sourceText)
        put("target_text", e.targetText)
        put("source_code", e.sourceCode)
        put("target_code", e.targetCode)
        put("model", e.modelName)
        put("created_at", e.createdAt)
        put("starred", if (e.starred) 1 else 0)
    }

    private fun queryRows(db: SQLiteDatabase, where: String?, args: Array<String>?, limit: Int): List<HistoryEntry> =
        db.query(
            "history",
            arrayOf("id", "source_text", "target_text", "source_code", "target_code", "model", "created_at", "starred"),
            where, args, null, null, "created_at DESC, id DESC", limit.toString(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getInt(7) != 0))
                }
            }
        }

    companion object {
        private const val VERSION = 2

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context).also { instance = it } }
    }
}
