package com.nekohome.translate.data

/** 术语表条目：原文 → 译文。 */
data class GlossaryEntry(val source: String, val target: String)

object GlossaryRules {
    /** 单次提示词最多带多少条术语 / 多少字符（原文+译文），避免撑爆上下文。 */
    const val MAX_TERMS_PER_PROMPT = 20
    const val MAX_CHARS_PER_PROMPT = 600
    const val MAX_ENTRIES = 500
    const val MAX_TERM_LENGTH = 80

    fun normalize(e: GlossaryEntry) = GlossaryEntry(e.source.trim(), e.target.trim())

    fun isValid(e: GlossaryEntry): Boolean {
        val n = normalize(e)
        return n.source.isNotEmpty() && n.target.isNotEmpty() &&
            n.source.length <= MAX_TERM_LENGTH && n.target.length <= MAX_TERM_LENGTH
    }

    /** 每行一条：原文 TAB 译文；反斜杠、TAB、换行用 \\ \t \n 转义。 */
    fun serialize(entries: List<GlossaryEntry>): String =
        entries.joinToString("\n") { escape(it.source) + "\t" + escape(it.target) }

    fun parse(raw: String?): List<GlossaryEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val e = GlossaryEntry(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)))
            e.takeIf { isValid(it) }
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /**
     * 只取 [text] 里实际出现的术语：大小写不敏感，原文重复的条目只留第一条，长词优先
     * （命中后把该段从文本里抹掉，所以被长词包含的短词不会重复注入），并按条数/字符数截断。
     * 返回顺序 = 原文长度降序。
     */
    fun match(
        text: String,
        entries: List<GlossaryEntry>,
        maxTerms: Int = MAX_TERMS_PER_PROMPT,
        maxChars: Int = MAX_CHARS_PER_PROMPT,
    ): List<GlossaryEntry> {
        if (text.isEmpty() || entries.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val candidates = entries.map(::normalize).filter { isValid(it) && seen.add(it.source.lowercase()) }
            .sortedByDescending { it.source.length }
        val work = StringBuilder(text.lowercase())
        val out = ArrayList<GlossaryEntry>()
        var chars = 0
        for (c in candidates) {
            if (out.size >= maxTerms) break
            val key = c.source.lowercase()
            val at = work.indexOf(key)
            if (at < 0) continue
            val cost = c.source.length + c.target.length
            if (chars + cost > maxChars) continue
            chars += cost
            out.add(c)
            var i = at
            while (i >= 0) {
                for (k in i until i + key.length) work.setCharAt(k, '\u0000')
                i = work.indexOf(key, i + key.length)
            }
        }
        return out
    }
}
