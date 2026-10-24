package com.nekohome.translate.data

import com.nekohome.translate.llama.LoadParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParamsTest {

    private val hy = BuiltInProfiles.HY_MT2

    @Test
    fun emptyParamsUseProfileDefaults() {
        val p = ModelParams()
        assertSame(hy, p.profile(hy))
        assertEquals(hy.sampling, p.sampling(hy))
        assertEquals(ModelParams.DEFAULT_CONTEXT, p.loadParams(defaultThreads = 4).nCtx)
        assertEquals(4, p.loadParams(defaultThreads = 4).nThreads)
        // Without an override, prefill / image encode get their own (performance core) default.
        assertEquals(8, p.loadParams(defaultThreads = 6, defaultBatchThreads = 8).nThreadsBatch)
    }

    @Test
    fun overridesApplyOnTopOfProfile() {
        val p = ModelParams(temperature = 0.2f, topK = 5, maxTokens = 512, nCtx = 8192, nThreads = 3)
        val s = p.sampling(hy)
        assertEquals(0.2f, s.temperature)
        assertEquals(5, s.topK)
        assertEquals(512, s.maxTokens)
        assertEquals(hy.sampling.topP, s.topP) // untouched
        // The user's thread count applies to generation and batch work alike.
        assertEquals(
            LoadParams(nCtx = 8192, nThreads = 3, nThreadsBatch = 3),
            p.loadParams(defaultThreads = 6, defaultBatchThreads = 8),
        )
    }

    @Test
    fun profileOverrideAndCustomTemplate() {
        assertSame(BuiltInProfiles.INDEX_TRANSLATE, ModelParams(profileId = "index-translate").profile(hy))
        val custom = ModelParams(profileId = "custom", customTemplate = "T {target_lang}: {text}").profile(hy)
        assertTrue(custom.isCustom)
        assertEquals("T {target_lang}: {text}", custom.promptTemplate)
        // The user template is also used for an auto-detected source.
        assertEquals("T English: hi", custom.buildUserPrompt(Language.AUTO, custom.findLanguage("en")!!, "hi"))
        // Unknown profile id falls back to the matched profile.
        assertSame(hy, ModelParams(profileId = "gone").profile(hy))
    }

    @Test
    fun resetKeepsProfileChoice() {
        val p = ModelParams(profileId = "custom", customTemplate = "x", temperature = 1f, nCtx = 2048)
        val r = p.resetToDefaults()
        assertEquals(ModelParams(profileId = "custom", customTemplate = "x"), r)
        assertTrue(p.hasValueOverrides)
        assertFalse(r.hasValueOverrides)
    }

    @Test
    fun reloadDetection() {
        assertFalse(ModelParams.needsReload(LoadParams(4096, 4), LoadParams(4096, 4)))
        assertTrue(ModelParams.needsReload(LoadParams(4096, 4), LoadParams(8192, 4)))
        assertTrue(ModelParams.needsReload(LoadParams(4096, 4), LoadParams(4096, 6)))
        assertTrue(ModelParams.needsReload(LoadParams(4096, 4, 8), LoadParams(4096, 4, 6)))
    }

    @Test
    fun codecRoundTrip() {
        val a = ModelParams(profileId = "hy-mt2", customTemplate = "line1\nline2 :: {text}", temperature = 0.7f, topP = 0.6f,
            topK = 20, repeatPenalty = 1.05f, maxTokens = 4096, nCtx = 8192, nThreads = 6)
        val b = ModelParams(temperature = 0f)
        val flat = ModelParamsCodec.encode("Hy-MT2::odd.gguf", a) + ModelParamsCodec.encode("b.gguf", b)
        val decoded = ModelParamsCodec.decodeAll(flat)
        assertEquals(a, decoded["Hy-MT2::odd.gguf"])
        assertEquals(b, decoded["b.gguf"])
        assertTrue(ModelParamsCodec.belongsTo("b.gguf::temperature", "b.gguf"))
        assertFalse(ModelParamsCodec.belongsTo("b.gguf::temperature", "a.gguf"))
        assertNull(decoded["missing"])
        assertTrue(ModelParamsCodec.encode("x", ModelParams()).isEmpty())
    }
}
