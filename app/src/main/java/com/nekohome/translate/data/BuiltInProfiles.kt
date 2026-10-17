package com.nekohome.translate.data

import com.nekohome.translate.llama.SamplingParams

/**
 * Built-in model profiles. Prompt formats and sampling values are copied from the official
 * model cards / reference clients (URLs in [ModelProfile.sourceUrls]); see README for details.
 */
object BuiltInProfiles {

    // ---------------------------------------------------------------------------------------------
    // Index-Translate (bilibili Index team), 2B / 9B, Qwen3.5 architecture (GGUF arch "qwen35").
    // ---------------------------------------------------------------------------------------------

    /**
     * Language code -> Chinese name, exactly as LANG_NAMES in the official client
     * inference/llm/translate.py (the training-side prompt builder). English names are only for the UI.
     * The model covers ~150 languages; the client only maps these codes.
     */
    private val indexLanguages = listOf(
        Language("zh", "中文", "Chinese"),
        Language("en", "英语", "English"),
        Language("ja", "日语", "Japanese"),
        Language("ko", "韩语", "Korean"),
        Language("de", "德语", "German"),
        Language("fr", "法语", "French"),
        Language("es", "西班牙语", "Spanish"),
        Language("pt", "葡萄牙语", "Portuguese"),
        Language("ru", "俄语", "Russian"),
        Language("ar", "阿拉伯语", "Arabic"),
        Language("it", "意大利语", "Italian"),
        Language("nl", "荷兰语", "Dutch"),
        Language("pl", "波兰语", "Polish"),
        Language("ro", "罗马尼亚语", "Romanian"),
        Language("sv", "瑞典语", "Swedish"),
        Language("tr", "土耳其语", "Turkish"),
        Language("hi", "印地语", "Hindi"),
        Language("vi", "越南语", "Vietnamese"),
        Language("th", "泰语", "Thai"),
        Language("id", "印尼语", "Indonesian"),
        Language("ms", "马来语", "Malay"),
        Language("fil", "菲律宾语", "Filipino"),
        Language("uk", "乌克兰语", "Ukrainian"),      // client code: ukr_cyrl
        Language("fa", "波斯语", "Persian"),          // client code: fas_arab
        Language("cs", "捷克语", "Czech"),            // client code: ces_latn
        Language("el", "希腊语", "Greek"),            // client code: ell_grek
        Language("da", "丹麦语", "Danish"),           // client code: dan_latn
        Language("hu", "匈牙利语", "Hungarian"),      // client code: hun_latn
        Language("fi", "芬兰语", "Finnish"),          // client code: fin_latn
        Language("nb", "书面挪威语", "Norwegian Bokmål"), // client code: nob_latn
        Language("sk", "斯洛伐克语", "Slovak"),       // client code: slk_latn
        Language("bg", "保加利亚语", "Bulgarian"),    // client code: bul_cyrl
    )

    /**
     * Qwen3.5 chat template rendered for one user turn with add_generation_prompt=true and
     * enable_thinking=false (the official client always disables thinking). From the model's
     * tokenizer_config.json chat_template:
     *   '<|im_start|>user\n' + content + '<|im_end|>\n' + '<|im_start|>assistant\n' + '<think>\n\n</think>\n\n'
     */
    private const val QWEN35_NO_THINK =
        "<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    val INDEX_TRANSLATE = ModelProfile(
        id = "index-translate",
        displayName = "Index-Translate (2B / 9B)",
        matchRule = MatchRule(
            architectures = setOf("qwen35"),
            namePattern = Regex("index[-_ ]?translate", RegexOption.IGNORE_CASE),
        ),
        // Plain (unconstrained) prompt from translate.py trans_prompt(), source language given:
        promptTemplate = "请将以下{source_lang}文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。\n\n{text}",
        promptLocale = PromptLocale.ZH,
        // ... and with --source auto (source-language name omitted):
        autoSourceTemplate = "请将以下文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。\n\n{text}",
        languages = indexLanguages,
        chatFormat = ChatFormat.Wrapper(QWEN35_NO_THINK),
        // Client defaults: greedy (temperature=0), max_tokens=1024; top-p/top-k/penalties not set
        // by the client (server defaults), so they are disabled here.
        sampling = SamplingParams(
            temperature = 0f,
            topP = 1f,
            topK = 0,
            minP = 0f,
            repeatPenalty = 1f,
            maxTokens = 1024,
        ),
        stopStrings = listOf("<|im_end|>"),
        sourceUrls = listOf(
            "https://huggingface.co/IndexTeam/Index-Translate-2B",
            "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF",
            "https://github.com/bilibili/Index-Translate/blob/main/inference/llm/translate.py",
        ),
        notes = "Thinking disabled (empty <think></think> block). Any emitted <think> block is stripped.",
    )

    // ---------------------------------------------------------------------------------------------
    // Hy-MT2 (Tencent Hunyuan), 1.8B / 7B, GGUF arch "hunyuan-dense".
    // ---------------------------------------------------------------------------------------------

    /** Supported-language table from the Hy-MT2 model card (Chinese names are used in Chinese prompts). */
    private val hyLanguages = listOf(
        Language("zh", "中文", "Chinese"),
        Language("en", "英语", "English"),
        Language("fr", "法语", "French"),
        Language("pt", "葡萄牙语", "Portuguese"),
        Language("es", "西班牙语", "Spanish"),
        Language("ja", "日语", "Japanese"),
        Language("tr", "土耳其语", "Turkish"),
        Language("ru", "俄语", "Russian"),
        Language("ar", "阿拉伯语", "Arabic"),
        Language("ko", "韩语", "Korean"),
        Language("th", "泰语", "Thai"),
        Language("it", "意大利语", "Italian"),
        Language("de", "德语", "German"),
        Language("vi", "越南语", "Vietnamese"),
        Language("ms", "马来语", "Malay"),
        Language("id", "印尼语", "Indonesian"),
        Language("tl", "菲律宾语", "Filipino"),
        Language("hi", "印地语", "Hindi"),
        Language("zh-Hant", "繁体中文", "Traditional Chinese"),
        Language("pl", "波兰语", "Polish"),
        Language("cs", "捷克语", "Czech"),
        Language("nl", "荷兰语", "Dutch"),
        Language("km", "高棉语", "Khmer"),
        Language("my", "缅甸语", "Burmese"),
        Language("fa", "波斯语", "Persian"),
        Language("gu", "古吉拉特语", "Gujarati"),
        Language("ur", "乌尔都语", "Urdu"),
        Language("te", "泰卢固语", "Telugu"),
        Language("mr", "马拉地语", "Marathi"),
        Language("he", "希伯来语", "Hebrew"),
        Language("bn", "孟加拉语", "Bengali"),
        Language("ta", "泰米尔语", "Tamil"),
        Language("uk", "乌克兰语", "Ukrainian"),
        Language("bo", "藏语", "Tibetan"),
        Language("kk", "哈萨克语", "Kazakh"),
        Language("mn", "蒙古语", "Mongolian"),
        Language("ug", "维吾尔语", "Uyghur"),
        Language("yue", "粤语", "Cantonese"),
    )

    /**
     * Hy-MT2 chat_template.jinja rendered for one user turn, no system prompt
     * ("our models do not have a default system_prompt"), add_generation_prompt=true:
     *   '<｜hy_begin▁of▁sentence｜>' + '<｜hy_User｜>' + content + '<｜hy_Assistant｜>'
     * Written with escapes: ｜ = U+FF5C, ▁ = U+2581.
     *
     * Not delegated to llama_chat_apply_template on purpose: at llama.cpp b11707 its non-Jinja
     * detector classifies this template (it contains both hy_begin▁of▁sentence and hy_Assistant)
     * as HUNYUAN_VL and renders "<bos>{content}<｜hy_User｜>" (verified with Hy-MT2-1.8B-Q4_K_M),
     * which is wrong for Hy-MT2.
     */
    private const val HY_MT2_WRAPPER =
        "<\uFF5Chy_begin\u2581of\u2581sentence\uFF5C><\uFF5Chy_User\uFF5C>{prompt}<\uFF5Chy_Assistant\uFF5C>"

    val HY_MT2 = ModelProfile(
        id = "hy-mt2",
        displayName = "Hy-MT2 (1.8B / 7B)",
        matchRule = MatchRule(
            architectures = setOf("hunyuan-dense"),
            namePattern = Regex("hy[-_ ]?mt|hunyuan[-_ ]?mt", RegexOption.IGNORE_CASE),
            // A renamed Hy-MT2 file is still hunyuan-dense; other hunyuan-dense chat models
            // follow this prompt fine, so claim the architecture when nothing else matched.
            architectureFallback = true,
        ),
        // "Default Translation" row of the model card's prompt table (markdown bold removed).
        // Hy-MT2's prompt only names the target language.
        promptTemplate = "将以下文本翻译为{target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{text}",
        promptLocale = PromptLocale.ZH,
        englishTemplate = "Translate the following text into {target_lang}. Note that you should only output " +
            "the translated result without any additional explanation:\n\n{text}",
        languages = hyLanguages,
        chatFormat = ChatFormat.Wrapper(HY_MT2_WRAPPER),
        // Model card, "For 1.8B and 7B": temperature 0.7, top_p 0.6, top_k 20,
        // repetition_penalty 1.05, max_tokens 4096. (generation_config.json ships top_p 0.8;
        // the README value is used.) max_tokens is further capped by the context window at runtime.
        sampling = SamplingParams(
            temperature = 0.7f,
            topP = 0.6f,
            topK = 20,
            minP = 0f,
            repeatPenalty = 1.05f,
            maxTokens = 4096,
        ),
        stopStrings = listOf("<\uFF5Chy_place\u2581holder\u2581no\u25812\uFF5C>"),
        sourceUrls = listOf(
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF",
            "https://github.com/Tencent-Hunyuan/Hy-MT2",
            "https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/chat_template.jinja",
        ),
        notes = "1.25-bit / 2-bit Hy-MT2 GGUFs need the STQ kernel (llama.cpp PR #22836) and will not load on mainline llama.cpp.",
    )

    // ---------------------------------------------------------------------------------------------
    // Custom
    // ---------------------------------------------------------------------------------------------

    private val customLanguages: List<Language> =
        (indexLanguages + hyLanguages).distinctBy { it.code }

    val CUSTOM = ModelProfile(
        id = "custom",
        displayName = "Custom",
        matchRule = MatchRule(),
        promptTemplate = "Translate the following {source_lang} text into {target_lang}. " +
            "Output only the translation.\n\n{text}",
        promptLocale = PromptLocale.EN,
        autoSourceTemplate = "Translate the following text into {target_lang}. Output only the translation.\n\n{text}",
        languages = customLanguages,
        chatFormat = ChatFormat.GgufTemplate,
        sampling = SamplingParams(temperature = 0.2f, topP = 0.9f, topK = 40, repeatPenalty = 1.05f, maxTokens = 1024),
        notes = "User-supplied template. Uses the GGUF chat template via llama_chat_apply_template, falling back to raw text.",
        isCustom = true,
    )

    val ALL: List<ModelProfile> = listOf(INDEX_TRANSLATE, HY_MT2, CUSTOM)

    fun byId(id: String?): ModelProfile? = ALL.firstOrNull { it.id == id }

    /**
     * First built-in profile whose rule matches (architecture + name/file name), then the first
     * profile that claims the architecture as a fallback; [CUSTOM] when none does.
     */
    fun match(architecture: String?, generalName: String?, fileName: String): ModelProfile {
        val builtIns = ALL.filterNot { it.isCustom }
        return builtIns.firstOrNull { it.matchRule.matches(architecture, generalName, fileName) }
            ?: builtIns.firstOrNull { it.matchRule.architectureFallback && it.matchRule.matchesArchitecture(architecture) }
            ?: CUSTOM
    }
}
