package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRulesTest {

    private val idx = BuiltInProfiles.INDEX_TRANSLATE

    @Test
    fun swapConcreteLanguages() {
        assertEquals("en" to "zh", LanguageRules.swap(idx, "zh", "en"))
    }

    @Test
    fun swapFromAutoUsesOldTargetAsSource() {
        assertEquals("en" to "zh", LanguageRules.swap(idx, Language.AUTO_CODE, "en"))
        assertEquals("zh" to "en", LanguageRules.swap(idx, Language.AUTO_CODE, "zh"))
    }

    @Test
    fun unsupportedCodesFallBack() {
        // Index-Translate has no Cantonese: the source falls back to auto, the target to Chinese.
        assertEquals(Language.AUTO, LanguageRules.resolveSource(idx, "yue"))
        assertEquals("zh", LanguageRules.resolveTarget(idx, "yue").code)
        // Target can never be auto.
        assertEquals("zh", LanguageRules.resolveTarget(idx, Language.AUTO_CODE).code)
    }
}
