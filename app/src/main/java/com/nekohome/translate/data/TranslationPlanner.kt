package com.nekohome.translate.data

/** 一段的最终提示词（未套聊天模板）与它和上一段之间的分隔。 */
data class PlannedSegment(val prompt: String, val separator: String)

object TranslationPlanner {
    /**
     * 把 [text] 切成若干段，每段只带本段出现的术语。[glossary] 为空 = 不用术语表。
     * 短文本只有一段，提示词与没有术语表时完全一致。
     */
    fun plan(
        profile: ModelProfile,
        source: Language,
        target: Language,
        text: String,
        nCtx: Int,
        maxTokens: Int,
        glossary: List<GlossaryEntry>,
    ): List<PlannedSegment> {
        val reserved = if (glossary.isEmpty()) 0 else GlossaryRules.MAX_CHARS_PER_PROMPT + 80
        val limit = TextSegmenter.segmentLimit(nCtx, maxTokens, reserved)
        val segments = TextSegmenter.split(text, limit)
        if (segments.isEmpty()) return listOf(PlannedSegment(profile.buildUserPrompt(source, target, text), ""))
        return segments.map { seg ->
            val terms = GlossaryRules.match(seg.text, glossary)
            PlannedSegment(profile.buildUserPrompt(source, target, seg.text, terms), seg.separator)
        }
    }
}
