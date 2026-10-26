package com.nekohome.translate.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsEngineChoiceTest {

    private val google = TtsEngineInfo("com.google.android.tts", "Google 语音服务", system = true)
    private val kokoro = TtsEngineInfo("com.k2fsa.sherpa.onnx.tts.engine", "sherpa-onnx TTS Engine")
    private val installed = listOf(google, kokoro)

    @Test
    fun defaultChoiceFollowsSystem() {
        assertNull(TtsEngineChoice.resolve(TtsEngineChoice.SYSTEM_DEFAULT, installed))
        assertNull(TtsEngineChoice.resolve(null, installed))
        assertNull(TtsEngineChoice.resolve("  ", installed))
    }

    @Test
    fun installedChoiceIsUsed() {
        assertEquals(kokoro.packageName, TtsEngineChoice.resolve(kokoro.packageName, installed))
    }

    @Test
    fun uninstalledChoiceFallsBackToSystemDefault() {
        assertNull(TtsEngineChoice.resolve(kokoro.packageName, listOf(google)))
        assertNull(TtsEngineChoice.resolve(kokoro.packageName, emptyList()))
    }

    @Test
    fun unknownInstalledListKeepsSavedChoice() {
        assertEquals(kokoro.packageName, TtsEngineChoice.resolve(kokoro.packageName, null))
    }

    @Test
    fun bindingKeyChangesWithSystemDefaultOnlyWhenFollowingIt() {
        assertNotEquals(
            TtsEngineChoice.bindingKey(null, google.packageName),
            TtsEngineChoice.bindingKey(null, kokoro.packageName),
        )
        assertEquals(
            TtsEngineChoice.bindingKey(kokoro.packageName, google.packageName),
            TtsEngineChoice.bindingKey(kokoro.packageName, kokoro.packageName),
        )
        assertNotEquals(TtsEngineChoice.bindingKey(null, null), TtsEngineChoice.bindingKey(kokoro.packageName, null))
    }

    @Test
    fun sortedPutsSystemEnginesFirstAndDropsDuplicates() {
        val other = TtsEngineInfo("a.tts", "Alpha TTS")
        val sorted = TtsEngineChoice.sorted(listOf(kokoro, other, google, kokoro))
        assertEquals(listOf(google, other, kokoro), sorted)
    }

    @Test
    fun chineseLocales() {
        for (tag in listOf("zh", "zh-CN", "zh-TW", "zh-HK", "zh-Hans-CN", "yue", "yue-HK", "cmn")) {
            assertTrue(tag, TtsEngineChoice.isChinese(Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("en-US", "ja-JP", "ko-KR", "vi", "und")) {
            assertFalse(tag, TtsEngineChoice.isChinese(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun chineseAppCodes() {
        for (code in listOf("zh", "zh-Hant", "yue", "zh-CN", "zh-TW", "zh-HK")) {
            assertTrue(code, TtsEngineChoice.isChineseCode(code))
        }
        for (code in listOf("en", "ja", "ko", "fr")) {
            assertFalse(code, TtsEngineChoice.isChineseCode(code))
        }
    }

    @Test
    fun lacksChineseVoiceOnlyForMissingOrUnsupportedChinese() {
        val zh = TtsLocales.localeFor("zh")
        assertTrue(TtsEngineChoice.lacksChineseVoice(zh, listOf(TextToSpeech.LANG_NOT_SUPPORTED)))
        assertTrue(TtsEngineChoice.lacksChineseVoice(zh, listOf(TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_MISSING_DATA)))
        // Cantonese: yue unsupported, zh-HK fallback missing data.
        val yue = TtsLocales.localeFor("yue")
        assertTrue(TtsEngineChoice.lacksChineseVoice(yue, listOf(TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_MISSING_DATA)))
        // Available but setLanguage failed: not a missing voice.
        assertFalse(TtsEngineChoice.lacksChineseVoice(zh, listOf(TextToSpeech.LANG_AVAILABLE)))
        assertFalse(TtsEngineChoice.lacksChineseVoice(zh, emptyList()))
        // Non-Chinese keeps the snackbar.
        assertFalse(TtsEngineChoice.lacksChineseVoice(TtsLocales.localeFor("ja"), listOf(TextToSpeech.LANG_MISSING_DATA)))
    }

    @Test
    fun arm64Detection() {
        assertTrue(TtsEngineChoice.isArm64(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertTrue(TtsEngineChoice.isArm64(listOf("x86_64", "arm64-v8a")))
        assertFalse(TtsEngineChoice.isArm64(listOf("armeabi-v7a", "armeabi")))
        assertFalse(TtsEngineChoice.isArm64(listOf("x86_64", "x86")))
        assertFalse(TtsEngineChoice.isArm64(emptyList()))
    }

    @Test
    fun kokoroLinksMatchTheVerifiedDownloads() {
        assertEquals(
            "https://hf-mirror.com/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.8/" +
                "sherpa-onnx-1.13.8-arm64-v8a-eng-tts-engine-kokoro-multi-lang-v1_1.apk",
            KokoroEngineLinks.MIRROR_APK,
        )
        assertEquals(
            "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.8/" +
                "sherpa-onnx-1.13.8-arm64-v8a-eng-tts-engine-kokoro-multi-lang-v1_1.apk",
            KokoroEngineLinks.HUGGINGFACE_APK,
        )
        assertEquals("https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine-cn.html", KokoroEngineLinks.ENGINE_LIST)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine",
            KokoroEngineLinks.SOURCE,
        )
    }
}
