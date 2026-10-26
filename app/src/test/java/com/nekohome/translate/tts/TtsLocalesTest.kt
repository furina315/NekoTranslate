package com.nekohome.translate.tts

import com.nekohome.translate.data.BuiltInProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsLocalesTest {

    @Test
    fun mapsChineseVariants() {
        assertEquals(Locale.forLanguageTag("zh-CN"), TtsLocales.localeFor("zh"))
        assertEquals("TW", TtsLocales.localeFor("zh-Hant").country)
        assertEquals("yue", TtsLocales.localeFor("yue").language)
        assertEquals(Locale.forLanguageTag("zh-HK"), TtsLocales.fallbackFor(TtsLocales.localeFor("yue")))
    }

    @Test
    fun fallbackDropsRegionThenGivesUp() {
        assertEquals(Locale.forLanguageTag("en"), TtsLocales.fallbackFor(Locale.forLanguageTag("en-US")))
        assertNull(TtsLocales.fallbackFor(Locale.forLanguageTag("de")))
    }

    @Test
    fun everyBuiltInLanguageHasAValidLocale() {
        val codes = listOf(BuiltInProfiles.INDEX_TRANSLATE, BuiltInProfiles.CUSTOM)
            .flatMap { it.languages }.map { it.code }.filter { it != "auto" }
        for (code in codes) {
            val locale = TtsLocales.localeFor(code)
            assertTrue("$code -> $locale", locale.language.isNotEmpty())
        }
    }

    @Test
    fun guessesScript() {
        assertEquals("zh", TtsLocales.guessCode("今天天气很好"))
        assertEquals("ja", TtsLocales.guessCode("今日は天気がいいですね"))
        assertEquals("ko", TtsLocales.guessCode("오늘 날씨가 좋네요"))
        assertEquals("ru", TtsLocales.guessCode("Сегодня хорошая погода"))
        assertEquals("ar", TtsLocales.guessCode("الطقس جميل اليوم"))
        assertEquals("th", TtsLocales.guessCode("วันนี้อากาศดี"))
        assertEquals("en", TtsLocales.guessCode("The weather is nice today"))
        assertEquals("en", TtsLocales.guessCode("123 !!"))
        assertEquals("fr", TtsLocales.guessCode("Bonjour", latinDefault = "fr"))
    }

    @Test
    fun chunksAtSentenceBoundaries() {
        val text = "第一句话。第二句话！第三句话？" + "很长".repeat(10)
        val chunks = TtsLocales.chunk(text, 12)
        assertTrue(chunks.all { it.length <= 12 })
        assertEquals("第一句话。第二句话！", chunks.first())
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun chunksShortTextAsIs() {
        assertEquals(listOf("hello world"), TtsLocales.chunk("  hello world \n", 4000))
        assertEquals(emptyList<String>(), TtsLocales.chunk("   ", 10))
    }

    @Test
    fun chunksAtSpacesWithoutPunctuation() {
        val chunks = TtsLocales.chunk("aaaa bbbb cccc dddd", 10)
        assertEquals(listOf("aaaa bbbb", "cccc dddd"), chunks)
    }
}
