package com.nekohome.translate.data

/**
 * A fixed whole-document prompt for some language directions. [render] returns `null` for a
 * direction it has no template for; the profile's ordinary prompt is used then.
 */
interface DocumentPrompt {
    fun render(source: Language, target: Language, text: String, terms: List<GlossaryEntry>): String?
}

/**
 * Index-NativeLong (published as IndexTeam/Index-Nailong-2B / -9B): the official document client
 * (bilibili/Index-Translate inference/llm/doc_translate.py) ships one fixed template per direction,
 * inference/llm/prompts/nailong_<direction>.txt, for zh-en / en-zh / zh-ja / ja-zh only. The whole
 * source replaces the template's placeholder line, exactly as render_prompt() does.
 *
 * Glossary terms are not part of the released templates. They are added as one more numbered
 * item in the same 【约束要求】 list, worded like Index-Translate's instTrans hard constraint
 * (`【硬性要求】专名/术语对照: a→b、c→d`, the format ModelProfile uses for Index-Translate).
 */
object NativeLongPrompts : DocumentPrompt {

    val DIRECTIONS = listOf("zh-en", "en-zh", "zh-ja", "ja-zh")

    /** Source-language word in each template's head line and placeholder (doc_translate.py SRC_WORD). */
    private val SRC_WORD = mapOf("zh-en" to "中文", "en-zh" to "英文", "zh-ja" to "中文", "ja-zh" to "日文")
    private val TARGET = mapOf("zh-en" to "英语", "en-zh" to "中文", "zh-ja" to "日语", "ja-zh" to "中文")

    private const val LAST_RULE = "3. 保持原文的章节、段落和内容顺序。"

    /** The official template for [direction], verbatim (including its trailing newline); null if none. */
    fun template(direction: String): String? {
        val src = SRC_WORD[direction] ?: return null
        val tgt = TARGET.getValue(direction)
        return "请将以下${src}小说翻译成${tgt}，并且严格遵循所有约束要求。\n\n" +
            "这是一段长篇连续文本，请完整处理全部内容，并在全文范围内保持译名和表达一致。\n\n" +
            "【约束要求】\n\n" +
            "1. 完整翻译所有内容，不要遗漏、概括、合并或添加内容。\n" +
            "2. 保持人名、地名、称谓和术语在全文中一致。\n" +
            LAST_RULE + "\n\n" +
            "只输出译文，不要有任何额外说明。\n\n" +
            "【源文】\n\n" +
            placeholder(direction) + "\n"
    }

    fun placeholder(direction: String): String = "（在这里放入需要翻译的完整${SRC_WORD.getValue(direction)}原文）"

    /**
     * The template direction for this pair, or null. An auto source is guessed from the text:
     * any kana means Japanese, otherwise more Han than Latin letters means Chinese, otherwise
     * English.
     */
    fun direction(source: Language, target: Language, text: String): String? {
        val src = if (source.isAuto) detectSource(text) else source.code
        val d = "$src-${target.code}"
        return d.takeIf { it in DIRECTIONS }
    }

    fun detectSource(text: String): String? {
        var kana = 0
        var han = 0
        var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return when {
            kana > 0 && kana * 50 >= han -> "ja"   // a little kana among many Han characters is still Japanese
            han > 0 && han >= latin / 4 -> "zh"    // one Han character carries roughly a Latin word's worth
            latin > 0 -> "en"
            else -> null
        }
    }

    override fun render(source: Language, target: Language, text: String, terms: List<GlossaryEntry>): String? {
        val d = direction(source, target, text) ?: return null
        var t = template(d) ?: return null
        if (terms.isNotEmpty()) {
            val pairs = terms.joinToString("、") { "${it.source}→${it.target}" }
            t = t.replace(LAST_RULE, "$LAST_RULE\n4. 【硬性要求】专名/术语对照: $pairs")
        }
        return t.replace(placeholder(d), text)
    }
}
