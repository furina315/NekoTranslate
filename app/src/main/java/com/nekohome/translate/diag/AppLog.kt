package com.nekohome.translate.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's one logging entry point: every call goes to logcat (same tag as before, so
 * `adb logcat -s NekoTts NekoImg` keeps working) and to a persistent ring log in
 * `filesDir/logs` (2 × 512 KB, kept across crashes / process death) that 设置 → 诊断 → 导出日志
 * packs up.
 *
 * Never pass source or translated text: log [LogPrivacy.len] instead.
 *
 * File writes run on one background thread, so calling from the main thread is fine. An uncaught
 * exception is written synchronously (after the queued lines) before the previous handler runs.
 */
object AppLog {

    const val TAG_APP = "NekoApp"
    const val TAG_MODEL = "NekoModel"
    const val TAG_CRASH = "NekoCrash"

    @Volatile
    private var ring: RingLog? = null

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "neko-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Synchronized
    fun init(context: Context) {
        if (ring != null) return
        val app = context.applicationContext
        ring = RingLog(logDir(app))
        installCrashHandler()
    }

    fun logDir(context: Context): File = File(context.applicationContext.filesDir, "logs")

    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Log.ERROR, tag, msg, t)

    private fun log(priority: Int, tag: String, msg: String, t: Throwable?) {
        when (priority) {
            Log.DEBUG -> if (t != null) Log.d(tag, msg, t) else Log.d(tag, msg)
            Log.INFO -> if (t != null) Log.i(tag, msg, t) else Log.i(tag, msg)
            Log.WARN -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            else -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
        val r = ring ?: return
        val line = LogFormat.line(System.currentTimeMillis(), levelChar(priority), tag, Thread.currentThread().name, msg, t)
        runCatching { writer.execute { runCatching { r.append(line) } } }
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        else -> 'E'
    }

    /** Persistent log text (older file first); runs on the caller's thread (call off the main thread). */
    fun readPersistent(): String {
        flush()
        return ring?.readAll().orEmpty()
    }

    fun persistentBytes(): Long = ring?.totalBytes() ?: 0L

    /** 「清空日志」. */
    fun clear() {
        flush()
        ring?.clear()
    }

    /** Waits (briefly) until queued lines are on disk. */
    fun flush(timeoutMs: Long = 1000) {
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching { writer.execute { latch.countDown() } }.onFailure { return }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Log.e(TAG_CRASH, "uncaught exception in thread ${thread.name}", error)
                flush(500)
                ring?.append(
                    LogFormat.line(System.currentTimeMillis(), 'E', TAG_CRASH, thread.name, "FATAL: uncaught exception", error),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
