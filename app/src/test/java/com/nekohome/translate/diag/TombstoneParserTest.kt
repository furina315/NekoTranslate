package com.nekohome.translate.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/** Hand-built protobuf bytes for tombstone.proto, field numbers as in AOSP debuggerd. */
private class Pb {
    private val out = ByteArrayOutputStream()

    private fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, m: Pb) = bytes(field, m.toBytes())
    fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(b.size.toLong()); out.write(b) }
    fun fixed64(field: Int, v: Long) = apply { tag(field, 1); for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun fixed32(field: Int, v: Int) = apply { tag(field, 5); for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xff) }
    fun toBytes(): ByteArray = out.toByteArray()
}

private fun frame(relPc: Long, fn: String, off: Long, file: String, buildId: String = "") =
    Pb().int(1, relPc).int(2, relPc + 0x7000_0000L).int(3, 0x7fff_0000L).str(4, fn).int(5, off).str(6, file).int(7, 0).str(8, buildId)

private fun thread(id: Int, name: String, vararg frames: Pb) = Pb().int(1, id.toLong()).str(2, name).also { t ->
    // registers (3) and a memory dump (5) that the parser must skip
    t.msg(3, Pb().str(1, "x0").int(2, 0))
    frames.forEach { t.msg(4, it) }
    t.msg(5, Pb().str(1, "x0").bytes(4, ByteArray(32) { 0x41 }))
}

private fun threadEntry(key: Int, th: Pb) = Pb().int(1, key.toLong()).msg(2, th)

private const val LLAMA = "/data/app/~~abc==/com.nekohome.translate-xyz==/lib/arm64/libllama.so"

private fun sampleTombstone(): ByteArray = Pb()
    .int(1, 1) // ARM64
    .str(2, "Xiaomi/dada/dada:16/BP2A/1:user/release-keys")
    .str(3, "0")
    .str(4, "2026-11-06 22:41:03.123456789+0800")
    .int(5, 4321).int(6, 4400).int(7, 10234)
    .str(8, "u:r:untrusted_app:s0")
    .str(9, "com.nekohome.translate").str(9, "--flag")
    .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(5, 0).int(8, 1).int(9, 0x10L))
    .str(14, "")
    .msg(15, Pb().str(1, "null pointer dereference"))
    // a non-crashing thread first, then the crashing one
    .msg(16, threadEntry(4321, thread(4321, "nekohome.translate", frame(0x5a10, "__epoll_pwait", 8, "/apex/com.android.runtime/lib64/bionic/libc.so"))))
    .msg(16, threadEntry(4400, thread(4400, "llama-engine",
        frame(0x8c2c4, "memcpy", 36, "/apex/com.android.runtime/lib64/bionic/libc.so", "aa11"),
        frame(0x123abc, "llama_decode", 132, LLAMA, "bb22"),
        frame(0x4567, "", 0, "/data/app/~~abc==/com.nekohome.translate-xyz==/lib/arm64/liblocaltranslate_jni.so"),
    )))
    .msg(17, Pb().int(1, 0x1000).int(2, 0x2000).str(7, "/system/lib64/libc.so"))
    .msg(18, Pb().str(1, "main").msg(2, Pb().str(7, "a log line")))
    .int(22, 4096)
    .toBytes()

class TombstoneParserTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun parsesSignalFaultAbortAndCrashingThread() {
        val t = TombstoneParser.parse(sampleTombstone())
        assertEquals(1, t.arch)
        assertEquals("arm64", t.archName)
        assertTrue(t.is64Bit)
        assertEquals(4321, t.pid)
        assertEquals(4400, t.tid)
        assertEquals("com.nekohome.translate", t.processName)
        assertEquals(11, t.signalNumber)
        assertEquals("SIGSEGV", t.signalName)
        assertEquals(1, t.signalCode)
        assertEquals("SEGV_MAPERR", t.signalCodeName)
        assertTrue(t.hasFaultAddress)
        assertEquals(0x10L, t.faultAddress)
        assertEquals(listOf("null pointer dereference"), t.causes)
        val th = t.crashingThread!!
        assertEquals(4400, th.id)
        assertEquals("llama-engine", th.name)
        assertEquals(3, th.frames.size)
        assertEquals(TombstoneFrame(0x123abc, 0x123abc + 0x7000_0000L, "llama_decode", 132, LLAMA, "bb22"), th.frames[1])
    }

    @Test
    fun summaryPrefersOurLibraries() {
        val t = TombstoneParser.parse(sampleTombstone())
        assertEquals("SIGSEGV (SEGV_MAPERR)", NativeCrashText.signalLabel(t))
        assertEquals("0x10", NativeCrashText.faultAddress(t))
        val f = NativeCrashText.relevantFrame(t)!!
        assertEquals("llama_decode", f.functionName)
        assertEquals("libllama.so · llama_decode+132", NativeCrashText.frameSummary(f))
        assertEquals("liblocaltranslate_jni.so · pc 0x4567", NativeCrashText.frameSummary(t.crashingThread!!.frames[2]))
        assertEquals("#01 pc 0000000000123abc  $LLAMA (llama_decode+132) (BuildId: bb22)",
            NativeCrashText.backtrace(t).lines()[1])
        assertEquals("#00 pc 0008c2c4  /x/libc.so (memcpy+36)",
            NativeCrashText.frameLine(0, TombstoneFrame(0x8c2c4, functionName = "memcpy", functionOffset = 36, fileName = "/x/libc.so"), false))
    }

    @Test
    fun abortMessageNegativeIntsAndUnknownFields() {
        val bytes = Pb()
            .int(6, 77)
            .msg(10, Pb().int(1, 6).str(2, "SIGABRT").int(3, -6).str(4, "SI_TKILL"))
            .str(14, "GGML_ASSERT(n_tokens <= n_batch) failed")
            .fixed64(99, 0x1122334455667788L) // unknown fixed64
            .fixed32(98, 7) // unknown fixed32
            .msg(16, threadEntry(77, thread(77, "main")))
            .toBytes()
        val t = TombstoneParser.parse(bytes)
        assertEquals(-6, t.signalCode) // int32 encoded as a 10-byte varint
        assertEquals("SIGABRT (SI_TKILL)", NativeCrashText.signalLabel(t))
        assertNull(NativeCrashText.faultAddress(t))
        assertEquals("GGML_ASSERT(n_tokens <= n_batch) failed", t.abortMessage)
        assertNull(t.arch)
        assertEquals(0, t.crashingThread!!.frames.size)
        assertNull(NativeCrashText.relevantFrame(t))
    }

    @Test
    fun gzipIsUnwrapped() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(sampleTombstone()) } }.toByteArray()
        assertTrue(TombstoneParser.isGzip(gz))
        assertEquals("SIGSEGV", TombstoneParser.parse(gz).signalName)
    }

    @Test
    fun malformedInputThrows() {
        val good = sampleTombstone()
        for (bad in listOf(
            ByteArray(0),
            good.copyOf(good.size - 1), // truncated inside the last varint
            "*** *** *** *** *** *** *** ***\nBuild fingerprint: 'x'\n".toByteArray(), // legacy text tombstone
            Pb().int(1, 1).int(5, 3).toBytes(), // valid protobuf but no signal / threads
            byteArrayOf(0x0a, 0x7f, 0x01), // length beyond the end
        )) {
            try {
                TombstoneParser.parse(bad)
                fail("expected ProtoException for ${bad.size} bytes")
            } catch (e: ProtoException) {
                // expected
            }
        }
    }

    @Test
    fun reportSectionContainsSummaryAndBacktrace() {
        val rec = NativeCrashRecord(1_793_426_531_123L, 4321, "com.nekohome.translate", "crash", TombstoneParser.parse(sampleTombstone()), 1234, null)
        val broken = NativeCrashRecord(1_793_426_000_000L, 99, "", "", null, 10, "truncated varint")
        val none = NativeCrashRecord(1_793_425_000_000L, 98, "", "", null, null, null)
        val s = NativeCrashText.reportSection(listOf(rec, broken, none), supported = true, zone = shanghai)
        assertTrue(s.startsWith("\n== 原生崩溃 ==\n[1] 10-31 14:02:11.123 · pid 4321 · com.nekohome.translate\n"))
        assertTrue(s.contains("  信号: SIGSEGV (SEGV_MAPERR) · 故障地址 0x10\n"))
        assertTrue(s.contains("  原因: null pointer dereference\n"))
        assertTrue(s.contains("  相关帧: libllama.so · llama_decode+132\n"))
        assertTrue(s.contains("  线程: 4400 llama-engine\n"))
        assertTrue(s.contains("    #01 pc 0000000000123abc  $LLAMA (llama_decode+132) (BuildId: bb22)\n"))
        assertTrue(s.contains("墓碑解析失败（已保留原始文件）：truncated varint"))
        assertTrue(s.contains("系统未提供墓碑数据"))
        assertFalse(s.contains("a log line")) // log buffers are never exported
        assertEquals("\n== 原生崩溃 ==\n（无记录）\n", NativeCrashText.reportSection(emptyList(), true))
        assertTrue(NativeCrashText.reportSection(emptyList(), false).contains("系统版本不支持"))
        assertEquals("2026-10-31 14:02:11", NativeCrashText.displayTime(1_793_426_531_123L, shanghai))
    }

    @Test
    fun assembleInsertsNativeSectionAndScrubs() {
        val out = DiagnosticsReport.assemble("HEADER\n", "app\n", "cat\n", "\n== 原生崩溃 ==\n中止信息: content://x/y a@b.com\n")
        assertTrue(out.indexOf("== 原生崩溃 ==") in out.indexOf("HEADER") until out.indexOf("== 应用日志"))
        assertTrue(out.contains("content://<redacted>"))
        assertTrue(out.contains("<email>"))
        assertFalse(out.contains("a@b.com"))
    }
}

class NativeCrashStoreTest {

    private fun tmp(): File = Files.createTempDirectory("ncs").toFile()

    @Test
    fun saveListRotateAndClear() {
        val dir = tmp()
        val st = NativeCrashStore(dir, maxRecords = 3)
        val raw = sampleTombstone()
        for (ts in 1L..5L) st.save(ts * 1000, ts.toInt(), "p", "d$ts", raw)
        st.save(6000, 6, "p", "no trace", null)
        st.save(7000, 7, "p", "garbage", byteArrayOf(1, 2, 3), truncated = true)
        val list = st.list()
        assertEquals(listOf(7000L, 6000L, 5000L), list.map { it.timestampMs })
        assertNotNull(list[2].tombstone)
        assertEquals("SIGSEGV", list[2].tombstone!!.signalName)
        assertEquals(raw.size.toLong(), list[2].rawBytes)
        assertNull(list[1].tombstone); assertNull(list[1].rawBytes); assertNull(list[1].parseError)
        assertNull(list[0].tombstone); assertNotNull(list[0].parseError)
        assertTrue(list[0].parseError!!.endsWith("（文件过大已截断）"))
        // raw files kept byte-for-byte; older ones rotated away
        assertTrue(File(dir, "5000.pb").readBytes().contentEquals(raw))
        assertFalse(File(dir, "1000.pb").exists())
        assertFalse(File(dir, "1000.meta").exists())
        assertEquals(2, st.rawFiles().size)
        st.setWatermark(7000)
        st.clear()
        assertEquals(0, st.count())
        assertEquals(7000L, st.watermark()) // cleared crashes are not imported again
        assertEquals(0L, NativeCrashStore(tmp()).watermark())
        dir.deleteRecursively()
    }
}
