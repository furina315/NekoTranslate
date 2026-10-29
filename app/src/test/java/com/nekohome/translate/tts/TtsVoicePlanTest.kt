package com.nekohome.translate.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsVoicePlanTest {

    private fun tag(t: String) = Locale.forLanguageTag(t)

    @Test
    fun candidatesFallBackToBareLanguageAndThreeLetterCode() {
        assertEquals(listOf("zh-CN", "zh", "zho"), TtsVoicePlan.candidates(TtsLocales.localeFor("zh")).map { it.toLanguageTag() })
        assertEquals(listOf("en-US", "en", "eng"), TtsVoicePlan.candidates(TtsLocales.localeFor("en")).map { it.toLanguageTag() })
        // Cantonese: Hong Kong Chinese voices before the bare (rarely supported) yue.
        assertEquals(listOf("yue-HK", "zh-HK", "yue"), TtsVoicePlan.candidates(TtsLocales.localeFor("yue")).map { it.toLanguageTag() })
    }

    @Test
    fun threeLetterAndTwoLetterCodesAreTheSameLanguage() {
        assertTrue(TtsVoicePlan.sameLanguage(tag("zh-CN"), Locale.Builder().setLanguage("zho").build()))
        assertTrue(TtsVoicePlan.sameLanguage(Locale.Builder().setLanguage("eng").build(), tag("en-US")))
        assertFalse(TtsVoicePlan.sameLanguage(tag("ja"), tag("zh")))
    }

    @Test
    fun pickVoicePrefersRegionThenLanguageAndSkipsUnusableVoices() {
        val voices = listOf(
            VoiceInfo("en-us-x-net", tag("en-US"), needsNetwork = true),
            VoiceInfo("zh-tw", tag("zh-TW")),
            VoiceInfo("zh-cn-missing", tag("zh-CN"), notInstalled = true),
            VoiceInfo("zh-cn", tag("zh-CN")),
            VoiceInfo("en-us", tag("en-US")),
        )
        assertEquals("zh-cn", TtsVoicePlan.pickVoice(voices, tag("zh-CN"))?.name)
        assertEquals("zh-tw", TtsVoicePlan.pickVoice(voices.filter { it.name != "zh-cn" }, tag("zh-CN"))?.name)
        assertEquals("en-us", TtsVoicePlan.pickVoice(voices, tag("en-GB"))?.name)
        assertNull(TtsVoicePlan.pickVoice(voices, tag("ja-JP")))
    }

    @Test
    fun kokoroStyleThreeLetterVoiceMatchesChineseAndCantonese() {
        // sherpa-onnx multi-lang engines may report voices as bare ISO-3 locales.
        val voices = listOf(VoiceInfo("eng", Locale.Builder().setLanguage("eng").build()), VoiceInfo("zho", Locale.Builder().setLanguage("zho").build()))
        assertEquals("zho", TtsVoicePlan.pickVoice(voices, tag("zh-CN"))?.name)
        assertEquals("zho", TtsVoicePlan.pickVoice(voices, tag("yue-HK"))?.name)
        assertEquals("eng", TtsVoicePlan.pickVoice(voices, tag("en-US"))?.name)
    }

    @Test
    fun explicitEngineSpeaksWithItsDefaultVoiceInsteadOfBlocking() {
        val results = listOf(TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_NOT_SUPPORTED)
        assertEquals(NoMatchDecision.EngineDefault, TtsVoicePlan.onNoMatch(explicitEngine = true, wanted = tag("zh-CN"), availability = results))
    }

    @Test
    fun systemDefaultEngineWithoutChineseBlocksAndAsksForAVoice() {
        val d = TtsVoicePlan.onNoMatch(false, tag("zh-CN"), listOf(TextToSpeech.LANG_NOT_SUPPORTED))
        assertEquals(NoMatchDecision.Block(missingData = false, chinese = true), d)
        val missing = TtsVoicePlan.onNoMatch(false, tag("de-DE"), listOf(TextToSpeech.LANG_MISSING_DATA))
        assertEquals(NoMatchDecision.Block(missingData = true, chinese = false), missing)
    }

    @Test
    fun chineseVoiceSheetOnlyForTheSystemDefaultEngine() {
        assertTrue(TtsVoicePlan.mayShowChineseVoiceSheet(explicitEngine = false, hintDismissed = false))
        assertFalse(TtsVoicePlan.mayShowChineseVoiceSheet(explicitEngine = true, hintDismissed = false))
        assertFalse(TtsVoicePlan.mayShowChineseVoiceSheet(explicitEngine = false, hintDismissed = true))
    }
}
