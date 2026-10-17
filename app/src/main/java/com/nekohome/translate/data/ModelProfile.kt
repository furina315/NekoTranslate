package com.nekohome.translate.data

import com.nekohome.translate.llama.SamplingParams

/** Which language names a prompt template expects in {source_lang} / {target_lang}. */
enum class PromptLocale { ZH, EN }

/**
 * How the user prompt is wrapped into the model's chat format before tokenization.
 */
sealed interface ChatFormat {
    /**
     * A fixed wrapper transcribed from the model's official Jinja chat template.
     * `{prompt}` is replaced by the user message. Special tokens are written literally and
     * parsed by the tokenizer.
     */
    data class Wrapper(val template: String) : ChatFormat

    /** Use the GGUF's tokenizer.chat_template through llama_chat_apply_template (no Jinja). */
    data object GgufTemplate : ChatFormat

    /** Send the prompt as-is (completion-style). */
    data object Raw : ChatFormat
}

/**
 * Matches a GGUF to a profile. A model matches when its architecture is in [architectures]
 * (or the set is empty) AND [namePattern] matches general.name or the file name.
 *
 * Note: the official GGUFs carry no family name in metadata (Index-Translate-2B has
 * general.name = "2b", Hy-MT2-1.8B has "1.8B"), so in practice the file name decides.
 * [architectureFallback] lets a profile claim its architecture when no name matched anywhere.
 */
data class MatchRule(
    val architectures: Set<String> = emptySet(),
    val namePattern: Regex? = null,
    val architectureFallback: Boolean = false,
) {
    fun matchesArchitecture(architecture: String?): Boolean =
        architecture != null && architecture in architectures

    fun matches(architecture: String?, generalName: String?, fileName: String): Boolean {
        if (architectures.isNotEmpty() && (architecture == null || architecture !in architectures)) return false
        val p = namePattern ?: return true
        return (generalName != null && p.containsMatchIn(generalName)) || p.containsMatchIn(fileName)
    }
}

/**
 * Everything the app needs to drive one translation model family.
 *
 * Prompt templates use the placeholders {source_lang}, {target_lang} and {text}.
 */
data class ModelProfile(
    val id: String,
    val displayName: String,
    val matchRule: MatchRule,
    /** Main prompt template (language names in [promptLocale]). */
    val promptTemplate: String,
    val promptLocale: PromptLocale,
    /** Used instead of [promptTemplate] when the source language is "auto". */
    val autoSourceTemplate: String? = null,
    /**
     * Optional English-language prompt used when neither side is a Chinese-family language
     * (Hy-MT2 publishes both a Chinese and an English prompt).
     */
    val englishTemplate: String? = null,
    val languages: List<Language>,
    val chatFormat: ChatFormat,
    val sampling: SamplingParams,
    /** Extra safety stops (the model's EOG tokens already end generation). */
    val stopStrings: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val notes: String = "",
    val isCustom: Boolean = false,
) {
    fun findLanguage(code: String): Language? =
        if (code == Language.AUTO_CODE) Language.AUTO else languages.firstOrNull { it.code == code }

    /** Builds the user message (before chat formatting). */
    fun buildUserPrompt(source: Language, target: Language, text: String): String {
        val trimmed = text.trim()
        val useEnglish = englishTemplate != null && !usesChinesePrompt(source, target, trimmed)
        val (template, locale) = when {
            useEnglish -> englishTemplate to PromptLocale.EN
            source.isAuto && autoSourceTemplate != null -> autoSourceTemplate to promptLocale
            else -> promptTemplate to promptLocale
        }
        fun name(l: Language) = if (locale == PromptLocale.ZH) l.nameZh else l.nameEn
        return template
            .replace("{source_lang}", if (source.isAuto) "" else name(source))
            .replace("{target_lang}", name(target))
            .replace("{text}", trimmed)
    }

    /**
     * Hy-MT2 rule (inferred from the model card note "Chinese names in Chinese prompts, English
     * names in English prompts" and the HY-MT1.5 convention of ZH<=>XX = Chinese prompt,
     * XX<=>XX = English prompt): use the Chinese prompt when either side is Chinese.
     * With an auto source, fall back to checking the text for CJK ideographs.
     */
    private fun usesChinesePrompt(source: Language, target: Language, text: String): Boolean {
        if (target.isChineseFamily) return true
        if (!source.isAuto) return source.isChineseFamily
        return text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }
}
