package com.nekohome.translate.diag

import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** One frame of the crashing thread's backtrace (tombstone.proto `BacktraceFrame`). */
data class TombstoneFrame(
    val relPc: Long,
    val pc: Long = 0L,
    val functionName: String = "",
    val functionOffset: Long = 0L,
    val fileName: String = "",
    val buildId: String = "",
)

data class TombstoneThread(val id: Int, val name: String, val frames: List<TombstoneFrame>)

/** The parts of an Android 12+ protobuf tombstone the diagnostics page and the export show. */
data class Tombstone(
    /** tombstone.proto `Architecture`: 0 arm32, 1 arm64, 2 x86, 3 x86_64, 4 riscv64; null if absent. */
    val arch: Int? = null,
    val buildFingerprint: String = "",
    val timestamp: String = "",
    val pid: Int = 0,
    val tid: Int = 0,
    val processName: String = "",
    val signalNumber: Int = 0,
    val signalName: String = "",
    val signalCode: Int = 0,
    val signalCodeName: String = "",
    val hasFaultAddress: Boolean = false,
    val faultAddress: Long = 0L,
    val abortMessage: String = "",
    /** `Cause.human_readable`, e.g. "null pointer dereference". */
    val causes: List<String> = emptyList(),
    /** threads[tid]; null when the tombstone carries no thread for the crashing tid. */
    val crashingThread: TombstoneThread? = null,
) {
    val is64Bit: Boolean get() = arch == null || arch == 1 || arch == 3 || arch == 4

    val archName: String get() = when (arch) {
        0 -> "arm"
        1 -> "arm64"
        2 -> "x86"
        3 -> "x86_64"
        4 -> "riscv64"
        null -> "?"
        else -> "arch$arch"
    }
}

/**
 * Minimal reader for AOSP `system/core/debuggerd/proto/tombstone.proto` (the format
 * `ApplicationExitInfo.getTraceInputStream()` returns for REASON_CRASH_NATIVE on Android 12+).
 * Field numbers used:
 * - Tombstone: arch 1, build_fingerprint 2, timestamp 4, pid 5, tid 6, command_line 9,
 *   signal_info 10, abort_message 14, causes 15, threads 16 (map<uint32, Thread>)
 * - Signal: number 1, name 2, code 3, code_name 4, has_fault_address 8, fault_address 9
 * - Cause: human_readable 1
 * - Thread: id 1, name 2, current_backtrace 4
 * - BacktraceFrame: rel_pc 1, pc 2, function_name 4, function_offset 5, file_name 6, build_id 8
 * Everything else (registers, memory dumps, log buffers, maps, fds) is skipped.
 *
 * Pure JVM.
 */
object TombstoneParser {

    /** Parses [bytes] (gzip is unwrapped first); throws [ProtoException] when it is not a tombstone. */
    fun parse(bytes: ByteArray): Tombstone {
        val data = if (isGzip(bytes)) gunzip(bytes) else bytes
        if (data.isEmpty()) throw ProtoException("empty")
        var t = Tombstone()
        val threads = HashMap<Int, TombstoneThread>()
        var sawSignal = false
        val r = ProtoReader(data)
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            when {
                field == 1 && wire == 0 -> t = t.copy(arch = r.readVarint().toInt())
                field == 2 && wire == 2 -> t = t.copy(buildFingerprint = r.readString())
                field == 4 && wire == 2 -> t = t.copy(timestamp = r.readString())
                field == 5 && wire == 0 -> t = t.copy(pid = r.readVarint().toInt())
                field == 6 && wire == 0 -> t = t.copy(tid = r.readVarint().toInt())
                field == 9 && wire == 2 -> {
                    val arg = r.readString()
                    if (t.processName.isEmpty()) t = t.copy(processName = arg)
                }
                field == 10 && wire == 2 -> { t = readSignal(r.readMessage(), t); sawSignal = true }
                field == 14 && wire == 2 -> t = t.copy(abortMessage = r.readString())
                field == 15 && wire == 2 -> readCause(r.readMessage())?.let { t = t.copy(causes = t.causes + it) }
                field == 16 && wire == 2 -> readThreadEntry(r.readMessage())?.let { (k, v) -> threads[k] = v }
                else -> r.skip(wire)
            }
        }
        if (!sawSignal && threads.isEmpty()) throw ProtoException("no signal_info and no threads: not a tombstone")
        return t.copy(crashingThread = threads[t.tid] ?: threads.values.singleOrNull())
    }

    private fun readSignal(r: ProtoReader, t: Tombstone): Tombstone {
        var s = t
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            when {
                field == 1 && wire == 0 -> s = s.copy(signalNumber = r.readVarint().toInt())
                field == 2 && wire == 2 -> s = s.copy(signalName = r.readString())
                field == 3 && wire == 0 -> s = s.copy(signalCode = r.readVarint().toInt())
                field == 4 && wire == 2 -> s = s.copy(signalCodeName = r.readString())
                field == 8 && wire == 0 -> s = s.copy(hasFaultAddress = r.readVarint() != 0L)
                field == 9 && wire == 0 -> s = s.copy(faultAddress = r.readVarint())
                else -> r.skip(wire)
            }
        }
        return s
    }

    private fun readCause(r: ProtoReader): String? {
        var text: String? = null
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            if (field == 1 && wire == 2) text = r.readString() else r.skip(wire)
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /** One map<uint32, Thread> entry: key 1, value 2. */
    private fun readThreadEntry(r: ProtoReader): Pair<Int, TombstoneThread>? {
        var key: Int? = null
        var thread: TombstoneThread? = null
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            when {
                field == 1 && wire == 0 -> key = r.readVarint().toInt()
                field == 2 && wire == 2 -> thread = readThread(r.readMessage())
                else -> r.skip(wire)
            }
        }
        val th = thread ?: return null
        return (key ?: th.id) to th
    }

    private fun readThread(r: ProtoReader): TombstoneThread {
        var id = 0
        var name = ""
        val frames = ArrayList<TombstoneFrame>()
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            when {
                field == 1 && wire == 0 -> id = r.readVarint().toInt()
                field == 2 && wire == 2 -> name = r.readString()
                field == 4 && wire == 2 -> frames += readFrame(r.readMessage())
                else -> r.skip(wire)
            }
        }
        return TombstoneThread(id, name, frames)
    }

    private fun readFrame(r: ProtoReader): TombstoneFrame {
        var f = TombstoneFrame(relPc = 0L)
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            f = when {
                field == 1 && wire == 0 -> f.copy(relPc = r.readVarint())
                field == 2 && wire == 0 -> f.copy(pc = r.readVarint())
                field == 4 && wire == 2 -> f.copy(functionName = r.readString())
                field == 5 && wire == 0 -> f.copy(functionOffset = r.readVarint())
                field == 6 && wire == 2 -> f.copy(fileName = r.readString())
                field == 8 && wire == 2 -> f.copy(buildId = r.readString())
                else -> { r.skip(wire); f }
            }
        }
        return f
    }

    fun isGzip(b: ByteArray) = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun gunzip(b: ByteArray): ByteArray = try {
        GZIPInputStream(b.inputStream()).use { it.readBytes() }
    } catch (e: Exception) {
        throw ProtoException("bad gzip: ${e.message}")
    }
}

/** One stored native crash: the exit record plus the parsed tombstone, if any. */
data class NativeCrashRecord(
    /** ApplicationExitInfo.getTimestamp() (ms since epoch); also the record's id. */
    val timestampMs: Long,
    val pid: Int,
    val processName: String,
    /** ApplicationExitInfo.getDescription(), may be empty. */
    val description: String,
    val tombstone: Tombstone?,
    /** Raw tombstone file kept on disk, or null when the system gave no trace. */
    val rawBytes: Long?,
    /** Set when a trace exists but could not be parsed (raw file kept). */
    val parseError: String?,
)

/** Text for the diagnostics list and the exported log. Pure JVM. */
object NativeCrashText {

    /** Libraries that are ours (shipped in the APK), matched on the file's base name. */
    private val appLibPrefixes = listOf("liblocaltranslate", "libllama", "libggml", "libmtmd", "libneko")

    /** "2026-11-06 22:41:03" in the device zone (diagnostics list). */
    fun displayTime(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(java.util.Date(timeMs))

    fun signalLabel(t: Tombstone): String {
        val name = t.signalName.ifBlank { if (t.signalNumber != 0) "signal ${t.signalNumber}" else "?" }
        return if (t.signalCodeName.isNotBlank()) "$name (${t.signalCodeName})" else name
    }

    fun faultAddress(t: Tombstone): String? =
        if (t.hasFaultAddress) "0x" + java.lang.Long.toHexString(t.faultAddress) else null

    fun baseName(path: String): String {
        // "/data/app/…/base.apk!/lib/arm64-v8a/libllama.so" and "/apex/…/libc.so" alike.
        val p = path.substringAfterLast('!')
        return p.substringAfterLast('/').ifBlank { path }
    }

    fun isAppFrame(f: TombstoneFrame): Boolean {
        val base = baseName(f.fileName)
        return appLibPrefixes.any { base.startsWith(it) }
    }

    /** First frame in one of our libraries, else the first frame with a symbol, else the first frame. */
    fun relevantFrame(t: Tombstone): TombstoneFrame? {
        val frames = t.crashingThread?.frames.orEmpty()
        return frames.firstOrNull(::isAppFrame)
            ?: frames.firstOrNull { it.functionName.isNotBlank() }
            ?: frames.firstOrNull()
    }

    /** "libllama.so · llama_decode+132" */
    fun frameSummary(f: TombstoneFrame): String {
        val lib = baseName(f.fileName).ifBlank { "?" }
        val fn = if (f.functionName.isNotBlank()) {
            f.functionName + if (f.functionOffset != 0L) "+${f.functionOffset}" else ""
        } else {
            "pc 0x" + java.lang.Long.toHexString(f.relPc)
        }
        return "$lib · $fn"
    }

    /** One debuggerd-style line: `#00 pc 00000000000a1b2c  /…/libllama.so (llama_decode+132) (BuildId: …)` */
    fun frameLine(index: Int, f: TombstoneFrame, is64Bit: Boolean): String = buildString {
        append('#').append(String.format(Locale.US, "%02d", index)).append(" pc ")
        val hex = java.lang.Long.toHexString(f.relPc)
        append(hex.padStart(if (is64Bit) 16 else 8, '0'))
        append("  ").append(f.fileName.ifBlank { "<unknown>" })
        if (f.functionName.isNotBlank()) {
            append(" (").append(f.functionName)
            if (f.functionOffset != 0L) append('+').append(f.functionOffset)
            append(')')
        }
        if (f.buildId.isNotBlank()) append(" (BuildId: ").append(f.buildId).append(')')
    }

    fun backtrace(t: Tombstone): String {
        val th = t.crashingThread ?: return ""
        return th.frames.mapIndexed { i, f -> frameLine(i, f, t.is64Bit) }.joinToString("\n")
    }

    /** Short status line for a record without a usable tombstone. */
    fun statusLine(r: NativeCrashRecord): String? = when {
        r.tombstone != null -> null
        r.parseError != null -> "墓碑解析失败（已保留原始文件）：${r.parseError}"
        else -> "系统未提供墓碑数据"
    }

    /** The 「原生崩溃」 section of an exported log; "" when there is nothing to report. */
    fun reportSection(
        records: List<NativeCrashRecord>,
        supported: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = buildString {
        append("\n== 原生崩溃 ==\n")
        if (!supported) {
            append("系统版本不支持（需要 Android 12 / API 31 及以上）\n")
            return@buildString
        }
        if (records.isEmpty()) {
            append("（无记录）\n")
            return@buildString
        }
        records.forEachIndexed { i, r ->
            append('[').append(i + 1).append("] ").append(LogFormat.timestamp(r.timestampMs, zone))
            append(" · pid ").append(r.pid)
            if (r.processName.isNotBlank()) append(" · ").append(r.processName)
            append('\n')
            if (r.description.isNotBlank()) append("  系统描述: ").append(r.description.replace('\n', ' ')).append('\n')
            statusLine(r)?.let { append("  ").append(it).append('\n') }
            val t = r.tombstone
            if (t != null) {
                append("  信号: ").append(signalLabel(t))
                faultAddress(t)?.let { append(" · 故障地址 ").append(it) }
                append('\n')
                if (t.abortMessage.isNotBlank()) append("  中止信息: ").append(t.abortMessage.trim().replace('\n', ' ')).append('\n')
                if (t.causes.isNotEmpty()) append("  原因: ").append(t.causes.joinToString("; ")).append('\n')
                relevantFrame(t)?.let { append("  相关帧: ").append(frameSummary(it)).append('\n') }
                t.crashingThread?.let { th -> append("  线程: ").append(th.id).append(' ').append(th.name).append('\n') }
                append("  架构: ").append(t.archName)
                if (t.buildFingerprint.isNotBlank()) append(" · ").append(t.buildFingerprint)
                append('\n')
                val bt = backtrace(t)
                if (bt.isNotBlank()) {
                    append("  backtrace:\n")
                    bt.lineSequence().forEach { append("    ").append(it).append('\n') }
                }
            }
            if (i != records.lastIndex) append('\n')
        }
    }
}
