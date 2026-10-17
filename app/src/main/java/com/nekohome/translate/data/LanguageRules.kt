package com.nekohome.translate.data

/** Keeps source/target choices valid for whatever profile is active. */
object LanguageRules {

    fun resolveSource(profile: ModelProfile, code: String): Language =
        profile.findLanguage(code) ?: Language.AUTO

    fun resolveTarget(profile: ModelProfile, code: String): Language =
        profile.findLanguage(code)?.takeUnless { it.isAuto } ?: fallbackTarget(profile, code)

    /** Chinese, or English when Chinese is the language to avoid; else the profile's first. */
    fun fallbackTarget(profile: ModelProfile, avoid: String): Language {
        val preferred = if (avoid == "zh") "en" else "zh"
        return profile.languages.firstOrNull { it.code == preferred } ?: profile.languages.first()
    }

    /** Swapped (source, target) codes. An auto source becomes the old target. */
    fun swap(profile: ModelProfile, sourceCode: String, targetCode: String): Pair<String, String> {
        val src = resolveSource(profile, sourceCode)
        val tgt = resolveTarget(profile, targetCode)
        return if (src.isAuto) tgt.code to fallbackTarget(profile, tgt.code).code else tgt.code to src.code
    }
}
