package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmprojTest {

    @Test
    fun detectsMmprojFileNames() {
        assertTrue(MmprojNames.looksLikeMmproj("Index-Translate-2B.mmproj-Q8_0.gguf"))
        assertTrue(MmprojNames.looksLikeMmproj("mmproj-Qwen3-VL-2B-Instruct-F16.gguf"))
        assertTrue(MmprojNames.looksLikeMmproj("foo.MMPROJ.gguf"))
        assertFalse(MmprojNames.looksLikeMmproj("Index-Translate-2B.Q4_K_M.gguf"))
    }

    @Test
    fun familyStemStripsMmprojAndQuant() {
        assertEquals("index-translate-2b", MmprojNames.familyStem("Index-Translate-2B.mmproj-Q8_0.gguf"))
        assertEquals("index-translate-2b", MmprojNames.familyStem("Index-Translate-2B.mmproj-f16.gguf"))
        assertEquals("index-translate-2b", MmprojNames.familyStem("Index-Translate-2B.Q4_K_M.gguf"))
        assertEquals("index-translate-2b", MmprojNames.familyStem("Index-Translate-2B.IQ4_XS.gguf"))
        assertEquals("qwen3-vl-2b-instruct", MmprojNames.familyStem("mmproj-Qwen3-VL-2B-Instruct-F16.gguf"))
        assertEquals("qwen3-vl-2b-instruct", MmprojNames.familyStem("Qwen3-VL-2B-Instruct-Q4_K_M.gguf"))
    }

    @Test
    fun pairsMmprojWithItsModel() {
        val models = listOf("Hy-MT2-1.8B.Q4_K_M.gguf", "Index-Translate-2B.Q4_K_M.gguf", "Index-Translate-9B.Q4_K_M.gguf")
        assertEquals("Index-Translate-2B.Q4_K_M.gguf", MmprojNames.bestModelFor("Index-Translate-2B.mmproj-Q8_0.gguf", models))
        assertEquals("Index-Translate-9B.Q4_K_M.gguf", MmprojNames.bestModelFor("Index-Translate-9B.mmproj-Q8_0.gguf", models))
        assertNull(MmprojNames.bestModelFor("mmproj-Qwen3-VL-2B-Instruct-F16.gguf", models))
    }

    @Test
    fun ambiguousMatchUsesPreferredModel() {
        val models = listOf("Index-Translate-2B.Q4_K_M.gguf", "Index-Translate-2B.Q8_0.gguf")
        val mmproj = "Index-Translate-2B.mmproj-Q8_0.gguf"
        assertEquals(2, MmprojNames.candidatesFor(mmproj, models).size)
        assertNull(MmprojNames.bestModelFor(mmproj, models))
        assertEquals("Index-Translate-2B.Q8_0.gguf", MmprojNames.bestModelFor(mmproj, models, preferred = "Index-Translate-2B.Q8_0.gguf"))
        // A preferred model that is not a candidate is not forced.
        assertNull(MmprojNames.bestModelFor(mmproj, models, preferred = "Hy-MT2-1.8B.Q4_K_M.gguf"))
        // Renamed copy: prefix match.
        assertEquals("index-translate-2b-mine.Q4_K_M.gguf", MmprojNames.bestModelFor(mmproj, listOf("index-translate-2b-mine.Q4_K_M.gguf")))
    }

    @Test
    fun mmprojIsPersistedWithModelParams() {
        val p = ModelParams(temperature = 0.3f, mmproj = "Index-Translate-2B.mmproj-Q8_0.gguf")
        val flat = ModelParamsCodec.encode("Index-Translate-2B.Q4_K_M.gguf", p)
        assertEquals("Index-Translate-2B.mmproj-Q8_0.gguf", flat["Index-Translate-2B.Q4_K_M.gguf::mmproj"])
        val back = ModelParamsCodec.decodeAll(flat)["Index-Translate-2B.Q4_K_M.gguf"]
        assertEquals(p, back)
        // Absent / blank -> null.
        assertNull(ModelParamsCodec.decodeAll(mapOf("m.gguf::mmproj" to " "))["m.gguf"]?.mmproj)
        assertFalse(ModelParamsCodec.encode("m.gguf", ModelParams()).keys.any { it.endsWith("::mmproj") })
    }

    @Test
    fun resetKeepsMmprojAndItIsNotAValueOverride() {
        val p = ModelParams(mmproj = "x.mmproj.gguf", topK = 3)
        assertEquals("x.mmproj.gguf", p.resetToDefaults().mmproj)
        assertFalse(ModelParams(mmproj = "x.mmproj.gguf").hasValueOverrides)
        assertTrue(p.hasValueOverrides)
    }

    @Test
    fun indexImagePromptMatchesOfficialClient() {
        val index = BuiltInProfiles.INDEX_TRANSLATE
        val zh = index.findLanguage("zh")!!
        val ja = index.findLanguage("ja")!!
        val marker = "<__media__>"
        assertEquals(
            "请将图片中的文本翻译为中文，直接输出翻译结果，不要进行任何解释。<__media__>",
            index.buildImagePrompt(Language.AUTO, zh, marker),
        )
        assertEquals(
            "请将图片中的日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。<__media__>",
            index.buildImagePrompt(ja, zh, marker),
        )
    }

    @Test
    fun englishProfilesGetTheEnglishImagePrompt() {
        val custom = BuiltInProfiles.CUSTOM
        val en = custom.findLanguage("en") ?: custom.languages.first { it.nameEn == "English" }
        val p = custom.buildImagePrompt(Language.AUTO, en, "<__media__>")
        assertEquals("Translate the text in the image into English, outputting translation only.<__media__>", p)
    }
}
