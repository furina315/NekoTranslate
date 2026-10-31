package com.nekohome.translate.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RingLogTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ringlog").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun line(i: Int) = "line %04d\n".format(i) // 10 bytes

    @Test
    fun appendsAndReadsBackInOrder() {
        val log = RingLog(dir, maxBytes = 1000)
        repeat(5) { log.append(line(it)) }
        assertEquals((0 until 5).joinToString("") { line(it) }, log.readAll())
        assertEquals(50L, log.totalBytes())
        assertEquals(listOf("app.log"), log.files().map { it.name })
    }

    @Test
    fun rotatesIntoOneBackupAndStaysBounded() {
        val log = RingLog(dir, maxBytes = 100)
        repeat(35) { log.append(line(it)) }
        // 10 lines per file: current holds 30..34, backup 20..29; 0..19 are gone.
        assertEquals(listOf("app.log.1", "app.log"), log.files().map { it.name })
        assertEquals((20 until 35).joinToString("") { line(it) }, log.readAll())
        assertTrue(log.files().all { it.length() <= 100 })
        assertTrue(log.totalBytes() <= 200)
    }

    @Test
    fun survivesReopen() {
        RingLog(dir, maxBytes = 100).apply { repeat(9) { append(line(it)) } }
        // A new instance (process restart) continues the size accounting of the existing file.
        val again = RingLog(dir, maxBytes = 100)
        again.append(line(9))
        assertEquals(listOf("app.log"), again.files().map { it.name })
        again.append(line(10))
        assertEquals(listOf("app.log.1", "app.log"), again.files().map { it.name })
        assertEquals((0..10).joinToString("") { line(it) }, again.readAll())
    }

    @Test
    fun oversizedLineIsCutToTheBudget() {
        val log = RingLog(dir, maxBytes = 64)
        log.append("x".repeat(200) + "END\n")
        assertTrue(log.totalBytes() <= 64)
        assertTrue(log.readAll().endsWith("END\n"))
    }

    @Test
    fun clearRemovesBothFiles() {
        val log = RingLog(dir, maxBytes = 50)
        repeat(12) { log.append(line(it)) }
        log.clear()
        assertEquals("", log.readAll())
        assertEquals(0L, log.totalBytes())
        assertFalse(File(dir, "app.log.1").exists())
        log.append(line(1))
        assertEquals(line(1), log.readAll())
    }

    @Test
    fun utf8IsCountedInBytes() {
        val log = RingLog(dir, maxBytes = 30)
        log.append("模型加载失败\n") // 6 × 3 + 1 = 19 bytes
        log.append("模型加载失败\n") // would make 38 > 30 → rotate
        assertEquals(listOf("app.log.1", "app.log"), log.files().map { it.name })
        assertEquals("模型加载失败\n模型加载失败\n", log.readAll())
    }
}
