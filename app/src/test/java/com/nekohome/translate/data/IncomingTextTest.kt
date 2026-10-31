package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingTextTest {
    @Test fun blankIsNull() { assertNull(IncomingText.clean("  \n ")); assertNull(IncomingText.clean(null)) }
    @Test fun trims() { assertEquals("hi", IncomingText.clean("  hi\n")) }
    @Test fun caps() { assertEquals(IncomingText.MAX_CHARS, IncomingText.clean("a".repeat(IncomingText.MAX_CHARS + 10_000))!!.length) }
}
