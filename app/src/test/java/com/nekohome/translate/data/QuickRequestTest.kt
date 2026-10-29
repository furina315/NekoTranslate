package com.nekohome.translate.data

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRequestTest {

    private fun processText(text: String?, readOnly: Boolean = false, forResult: Boolean = true) =
        IncomingText.quickRequest(Intent.ACTION_PROCESS_TEXT, "text/plain", text, null, readOnly, forResult)

    @Test
    fun editableSelectionStartedForResultCanBeReplaced() {
        assertEquals(QuickRequest("hello", replaceable = true), processText("  hello \n"))
    }

    @Test
    fun readOnlySelectionOrNoResultCallerCannotBeReplaced() {
        assertFalse(processText("hello", readOnly = true)!!.replaceable)
        assertFalse(processText("hello", forResult = false)!!.replaceable)
    }

    @Test
    fun sharedTextIsTranslatedButNeverReplaced() {
        val r = IncomingText.quickRequest(Intent.ACTION_SEND, "text/plain", null, "Bonjour", readOnly = false, forResult = true)
        assertEquals(QuickRequest("Bonjour", replaceable = false), r)
        assertFalse(IncomingText.canReplace(Intent.ACTION_SEND, readOnly = false, forResult = true))
    }

    @Test
    fun imagesBlankTextAndOtherActionsAreNotForTheCard() {
        assertNull(IncomingText.quickRequest(Intent.ACTION_SEND, "image/png", null, "x", false, false))
        assertNull(processText("   "))
        assertNull(processText(null))
        assertNull(IncomingText.quickRequest(Intent.ACTION_VIEW, "text/plain", "x", "x", false, true))
    }

    @Test
    fun longSelectionIsCapped() {
        val r = processText("a".repeat(IncomingText.MAX_CHARS + 50))!!
        assertEquals(IncomingText.MAX_CHARS, r.text.length)
        assertTrue(r.replaceable)
    }

    @Test
    fun replacementIsTrimmedTranslationOrNothing() {
        assertEquals("你好", IncomingText.replacement("  你好\n"))
        assertNull(IncomingText.replacement("  "))
        assertNull(IncomingText.replacement(null))
    }
}
