package com.nekohome.translate.diag

import java.io.File
import java.io.FileOutputStream

/**
 * Size-bounded log file that survives process death: lines are appended to `<name>` and, once it
 * would exceed [maxBytes], it becomes `<name>.1` (replacing the older one) and a fresh file starts.
 * At most [maxBytes] × 2 on disk. Every append is written straight through (no buffering), so a
 * crash or the system killing the process loses nothing that was already appended.
 *
 * Thread-safe. Pure JVM (unit-tested).
 */
class RingLog(private val dir: File, private val name: String = "app.log", private val maxBytes: Long = 512 * 1024L) {

    private val current = File(dir, name)
    private val previous = File(dir, "$name.1")

    /** Bytes in [current]; -1 until first used (avoids a stat per line). */
    private var size = -1L

    @Synchronized
    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (size < 0) {
            dir.mkdirs()
            size = if (current.isFile) current.length() else 0L
        }
        if (size > 0 && size + bytes.size > maxBytes) rotate()
        // A single line bigger than the whole budget is cut so the file stays bounded.
        val data = if (bytes.size > maxBytes) bytes.copyOfRange(bytes.size - maxBytes.toInt(), bytes.size) else bytes
        FileOutputStream(current, true).use { it.write(data) }
        size += data.size
    }

    private fun rotate() {
        previous.delete()
        if (!current.renameTo(previous)) current.delete()
        size = 0L
    }

    /** Older file first, then the current one (chronological). */
    @Synchronized
    fun readAll(): String = buildString {
        if (previous.isFile) append(previous.readText(Charsets.UTF_8))
        if (current.isFile) append(current.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun totalBytes(): Long = (if (previous.isFile) previous.length() else 0L) + (if (current.isFile) current.length() else 0L)

    @Synchronized
    fun clear() {
        previous.delete()
        current.delete()
        size = 0L
    }

    /** Files currently on disk, oldest first. */
    @Synchronized
    fun files(): List<File> = listOf(previous, current).filter { it.isFile }
}
