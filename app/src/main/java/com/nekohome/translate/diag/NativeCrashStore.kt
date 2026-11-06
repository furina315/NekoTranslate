package com.nekohome.translate.diag

import java.io.File
import java.util.Properties

/**
 * Native crash records on disk (`filesDir/diag/tombstones/`): per crash `<timestamp>.meta`
 * (exit info as properties) and, when the system provided one, `<timestamp>.pb` with the raw
 * tombstone bytes exactly as received. Only the newest [maxRecords] are kept.
 *
 * The already-processed exit timestamp watermark lives in `.watermark`, so a crash is never
 * imported twice, and clearing the list does not bring old crashes back.
 *
 * Thread-safe; pure JVM (unit-tested).
 */
class NativeCrashStore(private val dir: File, private val maxRecords: Int = 5) {

    @Synchronized
    fun watermark(): Long = runCatching { File(dir, WATERMARK).readText().trim().toLong() }.getOrDefault(0L)

    @Synchronized
    fun setWatermark(timestampMs: Long) {
        dir.mkdirs()
        File(dir, WATERMARK).writeText(timestampMs.toString())
    }

    /** Saves one crash (replacing a record with the same timestamp), then trims to [maxRecords]. */
    @Synchronized
    fun save(timestampMs: Long, pid: Int, processName: String, description: String, raw: ByteArray?, truncated: Boolean = false) {
        dir.mkdirs()
        if (raw != null) File(dir, "$timestampMs$RAW").writeBytes(raw) else File(dir, "$timestampMs$RAW").delete()
        val p = Properties().apply {
            setProperty("timestamp", timestampMs.toString())
            setProperty("pid", pid.toString())
            setProperty("process", processName)
            setProperty("description", description)
            if (truncated) setProperty("truncated", "true")
        }
        File(dir, "$timestampMs$META").outputStream().use { p.store(it, null) }
        trim()
    }

    private fun ids(): List<Long> =
        dir.listFiles()?.mapNotNull { f -> f.name.removeSuffix(META).takeIf { f.name.endsWith(META) }?.toLongOrNull() }
            ?.sortedDescending().orEmpty()

    private fun trim() {
        ids().drop(maxRecords).forEach { delete(it) }
        // Orphaned raw files (meta lost) are removed too.
        val keep = ids().toSet()
        dir.listFiles()?.filter { it.name.endsWith(RAW) && it.name.removeSuffix(RAW).toLongOrNull() !in keep }?.forEach { it.delete() }
    }

    private fun delete(id: Long) {
        File(dir, "$id$META").delete()
        File(dir, "$id$RAW").delete()
    }

    /** Newest first, each tombstone parsed (a parse failure is reported on the record, the file is kept). */
    @Synchronized
    fun list(): List<NativeCrashRecord> = ids().map { id ->
        val p = Properties()
        runCatching { File(dir, "$id$META").inputStream().use { p.load(it) } }
        val raw = File(dir, "$id$RAW").takeIf { it.isFile }
        var tomb: Tombstone? = null
        var err: String? = null
        if (raw != null) {
            try {
                tomb = TombstoneParser.parse(raw.readBytes())
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            if (p.getProperty("truncated") == "true" && err != null) err += "（文件过大已截断）"
        }
        NativeCrashRecord(
            timestampMs = id,
            pid = p.getProperty("pid")?.toIntOrNull() ?: tomb?.pid ?: 0,
            processName = p.getProperty("process").orEmpty().ifBlank { tomb?.processName.orEmpty() },
            description = p.getProperty("description").orEmpty(),
            tombstone = tomb,
            rawBytes = raw?.length(),
            parseError = err,
        )
    }

    /** Raw tombstone files, newest first. */
    @Synchronized
    fun rawFiles(): List<File> = ids().map { File(dir, "$it$RAW") }.filter { it.isFile }

    @Synchronized
    fun count(): Int = ids().size

    /** Deletes every record; the watermark stays so cleared crashes are not imported again. */
    @Synchronized
    fun clear() {
        ids().forEach { delete(it) }
        dir.listFiles()?.filter { it.name.endsWith(RAW) }?.forEach { it.delete() }
    }

    companion object {
        const val META = ".meta"
        const val RAW = ".pb"
        private const val WATERMARK = ".watermark"
    }
}
