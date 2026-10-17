package com.nekohome.translate.engine

import java.io.File

/** Process memory from /proc/self/status (VmRSS = resident set incl. mmap'ed model pages touched). */
object MemoryStats {
    fun residentMb(): Long? = readKb("VmRSS")?.let { it / 1024 }
    fun peakMb(): Long? = readKb("VmHWM")?.let { it / 1024 }

    private fun readKb(field: String): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$field:") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()
        }
    }.getOrNull()
}
