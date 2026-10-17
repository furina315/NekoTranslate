package com.nekohome.translate.ui

import com.nekohome.translate.ui.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun sizes() {
        assertEquals("1.25 GB", Format.size((1.25 * (1L shl 30)).toLong()))
        assertEquals("512 MB", Format.size(512L shl 20))
        assertEquals("4 KB", Format.size(4096))
    }

    @Test
    fun decimals() {
        assertEquals("0.7", Format.decimal(0.7f))
        assertEquals("1.05", Format.decimal(1.05f))
        assertEquals("0", Format.decimal(0f))
        assertEquals("10", Format.decimal(10f))
    }
}
