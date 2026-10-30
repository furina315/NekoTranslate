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
    /**
     * Instruction for translating the text in an image (needs an mmproj), with {source_lang} /
     * {target_lang} in [promptLocale]; `null` = the built-in default for [promptLocale].
     */
    val imageTemplate: String? = null,
    /** Like [imageTemplate] for an auto-detected source. */
    val imageAutoSourceTemplate: String? = null,
    val chatFormat: ChatFormat,
    val sampling: SamplingParams,
    /** Extra safety stops (the model's EOG tokens already end generation). */
    val stopStrings: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val notes: String = "",
    val isCustom: Boolean = false,
    /** Model was trained on the 【约束要求】 prompt (Index-Translate): glossary terms go in as hard constraints. */
    val supportsConstraints: Boolean = false,
) {
    fun findLanguage(code: String): Language? =
        if (code == Language.AUTO_CODE) Language.AUTO else languages.firstOrNull { it.code == code }

    /** Builds the user message (before chat formatting). */
    fun buildUserPrompt(source: Language, target: Language, text: String, terms: List<GlossaryEntry> = emptyList()): String {
        val trimmed = text.trim()
        if (terms.isNotEmpty() && supportsConstraints && promptLocale == PromptLocale.ZH) {
            return buildConstraintPrompt(source, target, trimmed, terms)
        }
        val useEnglish = englishTemplate != null && !usesChinesePrompt(source, target, trimmed)
        val (template, locale) = when {
            useEnglish -> englishTemplate to PromptLocale.EN
            source.isAuto && autoSourceTemplate != null -> autoSourceTemplate to promptLocale
            else -> promptTemplate to promptLocale
        }
        fun name(l: Language) = if (locale == PromptLocale.ZH) l.nameZh else l.nameEn
        val base = template
            .replace("{source_lang}", if (source.isAuto) "" else name(source))
            .replace("{target_lang}", name(target))
            .replace("{text}", trimmed)
        if (terms.isEmpty()) return base
        val pairs = terms.joinToString("；") { "${it.source} → ${it.target}" }
        return if (locale == PromptLocale.EN) "$base\n\nUse these term translations strictly: $pairs"
        else "$base\n\n请严格使用以下术语译法：$pairs"
    }

    /**
     * Index-Translate's official constrained prompt (translate.py instTrans): source text plus a
     * 【约束要求】 list with one 【硬性要求】专名/术语对照 line.
     */
    private fun buildConstraintPrompt(source: Language, target: Language, text: String, terms: List<GlossaryEntry>): String {
        val head = if (source.isAuto) "请将以下文本翻译成${target.nameZh}" else "请将以下${source.nameZh}文本翻译成${target.nameZh}"
        val pairs = terms.joinToString("、") { "${it.source}→${it.target}" }
        return head + "，并且严格遵循所有约束要求。\n\n【源文】\n" + text +
            "\n\n【约束要求】\n1. 【硬性要求】专名/术语对照: " + pairs + "\n\n只输出译文，不要有任何额外说明。"
    }

    /**
     * Builds the user message for translating the text in an image: the instruction followed by
     * [mediaMarker] (where the image goes, see LlamaSession.MEDIA_MARKER). Same order as the
     * official client (translate.py sends content = [text, image_url]; the Qwen3.5 chat template
     * renders the parts in order, the image as <|vision_start|>…<|vision_end|>).
     */
    fun buildImagePrompt(source: Language, target: Language, mediaMarker: String): String {
        val zh = promptLocale == PromptLocale.ZH
        val template = if (source.isAuto) {
            imageAutoSourceTemplate ?: imageTemplate?.takeIf { "{source_lang}" !in it }
                ?: if (zh) DEFAULT_IMAGE_AUTO_TEMPLATE else DEFAULT_IMAGE_AUTO_TEMPLATE_EN
        } else {
            imageTemplate ?: if (zh) DEFAULT_IMAGE_TEMPLATE else DEFAULT_IMAGE_TEMPLATE_EN
        }
        fun name(l: Language) = if (promptLocale == PromptLocale.ZH) l.nameZh else l.nameEn
        val instruction = template
            .replace("{source_lang}", if (source.isAuto) "" else name(source))
            .replace("{target_lang}", name(target))
        return instruction + mediaMarker
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

    companion object {
        /**
         * Image prompt of Index-Translate's official client (inference/llm/translate.py, --image),
         * with and without a source language.
         */
        const val DEFAULT_IMAGE_TEMPLATE = "请将图片中的{source_lang}文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。"
        const val DEFAULT_IMAGE_AUTO_TEMPLATE = "请将图片中的文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。"

        /** The docs' English image prompt, for profiles whose prompts use English language names. */
        const val DEFAULT_IMAGE_TEMPLATE_EN = "Translate the {source_lang} text in the image into {target_lang}, outputting translation only."
        const val DEFAULT_IMAGE_AUTO_TEMPLATE_EN = "Translate the text in the image into {target_lang}, outputting translation only."
    }
}
