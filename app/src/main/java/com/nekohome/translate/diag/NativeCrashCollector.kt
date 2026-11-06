package com.nekohome.translate.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Imports native crashes of earlier processes from the system (Android 12+ / API 31,
 * `ActivityManager.getHistoricalProcessExitReasons`). Runs once per process start on its own
 * low-priority thread, so app start is never blocked.
 *
 * - REASON_CRASH_NATIVE: the tombstone (`getTraceInputStream()`, protobuf) is saved as-is into
 *   [NativeCrashStore] (newest 5 kept) and parsed for the 设置 → 诊断 list and the log export.
 * - REASON_CRASH / REASON_ANR / REASON_LOW_MEMORY: a one-line summary goes to [AppLog].
 *
 * Below API 31 nothing is called ([supported] is false) and the UI says the system is too old.
 */
object NativeCrashCollector {

    private const val TAG = "NekoCrash"
    private const val MAX_EXIT_INFOS = 16
    private const val MAX_TRACE_BYTES = 4 * 1024 * 1024

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Bumped after every scan or clear; the diagnostics UI reloads the list when it changes. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile
    private var store: NativeCrashStore? = null

    fun store(context: Context): NativeCrashStore = store ?: synchronized(this) {
        store ?: NativeCrashStore(File(context.applicationContext.filesDir, "diag/tombstones")).also { store = it }
    }

    /** Starts the background import; a no-op below API 31. */
    fun start(context: Context) {
        if (!supported) return
        val app = context.applicationContext
        Thread({
            try {
                scan(app)
            } catch (t: Throwable) {
                AppLog.w(TAG, "native crash scan failed", t)
            } finally {
                _version.update { it + 1 }
            }
        }, "neko-crash-scan").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    fun clear(context: Context) {
        store(context).clear()
        _version.update { it + 1 }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun scan(app: Context) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val st = store(app)
        val watermark = st.watermark()
        val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXIT_INFOS)
            .filter { it.timestamp > watermark }
            .sortedBy { it.timestamp }
        if (infos.isEmpty()) return
        var imported = 0
        for (info in infos) {
            when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                    val (raw, truncated) = readTrace(info)
                    st.save(
                        timestampMs = info.timestamp,
                        pid = info.pid,
                        processName = info.processName.orEmpty(),
                        description = info.description.orEmpty(),
                        raw = raw,
                        truncated = truncated,
                    )
                    imported++
                    AppLog.e(
                        TAG,
                        "previous process died of a native crash: pid ${info.pid} at ${LogFormat.timestamp(info.timestamp)}" +
                            " (tombstone ${raw?.size ?: 0} B${if (truncated) ", truncated" else ""})",
                    )
                }
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                -> AppLog.w(TAG, "previous exit: ${reasonName(info.reason)} pid ${info.pid} at " +
                    "${LogFormat.timestamp(info.timestamp)}, importance ${info.importance}" +
                    (info.description?.takeIf { it.isNotBlank() }?.let { ", ${it.replace('\n', ' ').take(300)}" } ?: ""))
                else -> Unit
            }
        }
        st.setWatermark(infos.maxOf { it.timestamp })
        if (imported > 0) AppLog.i(TAG, "imported $imported native crash record(s)")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readTrace(info: ApplicationExitInfo): Pair<ByteArray?, Boolean> = try {
        info.traceInputStream?.use { readLimited(it) } ?: (null to false)
    } catch (e: Exception) {
        AppLog.w(TAG, "tombstone read failed", e)
        null to false
    }

    private fun readLimited(input: InputStream): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray() to false
            val room = MAX_TRACE_BYTES - out.size()
            if (n > room) {
                out.write(buf, 0, room)
                return out.toByteArray() to true
            }
            out.write(buf, 0, n)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        else -> "reason $reason"
    }

    /**
     * Debug builds only (设置 → 诊断 →「触发原生崩溃」): sends SIGSEGV to this process, so debuggerd
     * writes a real tombstone and the system records REASON_CRASH_NATIVE.
     */
    fun crashForTesting() {
        AppLog.w(TAG, "debug: raising SIGSEGV on purpose")
        AppLog.flush(500)
        android.os.Process.sendSignal(android.os.Process.myPid(), 11)
    }
}
