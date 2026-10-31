package com.nekohome.translate.diag

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Line format of the persistent log, close to `logcat -v threadtime`. Pure JVM. */
object LogFormat {

    /** `10-31 14:02:11.123  I/NekoImg [llama-engine]: message` (+ stack trace lines). */
    fun line(
        timeMs: Long,
        level: Char,
        tag: String,
        thread: String,
        message: String,
        error: Throwable? = null,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = buildString {
        append(timestamp(timeMs, zone)).append("  ").append(level).append('/').append(tag)
        append(" [").append(thread).append("]: ").append(message.trimEnd())
        append('\n')
        if (error != null) append(stackTrace(error))
    }

    fun timestamp(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }.format(Date(timeMs))

    fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString().let { if (it.endsWith('\n')) it else it + '\n' }
    }
}

/**
 * Privacy rules for logs. Source / translated text never goes into a log: callers log [len]
 * instead. [scrub] is applied once more to everything that is exported (our own lines and the
 * logcat dump), as a safety net for content URIs and file names that may reveal what was opened.
 */
object LogPrivacy {

    /** What a log line may say about a piece of user text: only its length. */
    fun len(text: CharSequence?): String = if (text == null) "null" else "${text.length} chars"

    private val contentUri = Regex("""content://[^\s"'<>)\]]+""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    fun scrub(text: String): String =
        text.replace(contentUri, "content://<redacted>").replace(email, "<email>")
}
