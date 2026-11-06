package com.nekohome.translate.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogPrivacyTest {

    @Test
    fun lenNeverContainsTheText() {
        val secret = "我的银行卡密码是 123456"
        val logged = LogPrivacy.len(secret)
        assertEquals("${secret.length} chars", logged)
        assertFalse(logged.contains("密码"))
        assertEquals("null", LogPrivacy.len(null))
    }

    @Test
    fun scrubRemovesContentUrisAndEmails() {
        val raw = "decode content://com.android.providers.media.documents/document/image%3A1234 failed; " +
            "user hoshino.test@example.com wrote; uri=(content://media/external/images/1)"
        val out = LogPrivacy.scrub(raw)
        assertFalse(out.contains("image%3A1234"))
        assertFalse(out.contains("example.com"))
        assertTrue(out.contains("content://<redacted>"))
        assertTrue(out.contains("<email>"))
        assertTrue(out.endsWith("(content://<redacted>)"))
    }

    @Test
    fun scrubKeepsOrdinaryDiagnostics() {
        val raw = "11-01 08:00:00.000  1234  1260 I NekoModel: model Index-Translate-2B.Q4_K_M.gguf loaded in 812 ms"
        assertEquals(raw, LogPrivacy.scrub(raw))
    }

    @Test
    fun lineFormatLooksLikeThreadtime() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-10-31 06:02:11.123 UTC
        val line = LogFormat.line(1_793_426_531_123L, 'W', "NekoTts", "main", "onError: errorCode=-3  \n", zone = utc)
        assertEquals("10-31 06:02:11.123  W/NekoTts [main]: onError: errorCode=-3\n", line)
    }

    @Test
    fun lineIncludesStackTrace() {
        val line = LogFormat.line(0L, 'E', "NekoCrash", "main", "FATAL", IllegalStateException("boom"), TimeZone.getTimeZone("UTC"))
        val rows = line.lines()
        assertEquals("01-01 00:00:00.000  E/NekoCrash [main]: FATAL", rows[0])
        assertEquals("java.lang.IllegalStateException: boom", rows[1])
        assertTrue(rows[2].trimStart().startsWith("at "))
        assertTrue(line.endsWith("\n"))
    }
}
