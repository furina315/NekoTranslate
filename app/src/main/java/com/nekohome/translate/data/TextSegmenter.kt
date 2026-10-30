package com.nekohome.translate.data

/** 一段待翻译文本；[separator] 是它与上一段之间在原文里的空白（第一段为空）。 */
data class Segment(val text: String, val separator: String)

object TextSegmenter {
    const val MIN_LIMIT = 120
    const val MAX_LIMIT = 800
    private const val SENTENCE_END = "。！？!?；;…"
    private const val CLOSERS = "”’\"')）】》」』"

    /**
     * 每段原文最多多少字符。上下文要同时放下提示词、术语、原文和译文：
     * 译文按最坏 1.5 倍 token 估算，原文按 1 字符 ≈ 1 token（偏保守，CJK 最坏情况）。
     */
    fun segmentLimit(nCtx: Int, maxTokens: Int, reservedChars: Int = 0): Int {
        val ctx = if (nCtx > 0) nCtx else 4096
        val avail = ctx - 160 - reservedChars
        var limit = (avail / 2.5).toInt()
        if (maxTokens > 0) limit = minOf(limit, (maxTokens / 1.5).toInt())
        return limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
    }

    /** 不超过 [maxChars] 就不分段（返回单段）；否则按段落 → 句子 → 逗号/空格切分并贪心合并。 */
    fun split(text: String, maxChars: Int): List<Segment> {
        val src = text.trim()
        if (src.isEmpty()) return emptyList()
        if (src.length <= maxChars) return listOf(Segment(src, ""))

        val atoms = atoms(src, maxChars)
        val out = ArrayList<Segment>()
        val cur = StringBuilder()
        var curSep = ""
        for ((sep, atom) in atoms) {
            if (cur.isEmpty()) {
                curSep = if (out.isEmpty()) "" else sep
                cur.append(atom)
            } else if (cur.length + sep.length + atom.length <= maxChars) {
                cur.append(sep).append(atom)
            } else {
                out.add(Segment(cur.toString(), curSep))
                cur.setLength(0)
                curSep = sep
                cur.append(atom)
            }
        }
        if (cur.isNotEmpty()) out.add(Segment(cur.toString(), curSep))
        return out
    }

    /** (前导空白, 句子) 列表；超长句子再硬切。 */
    private fun atoms(s: String, max: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < s.length) {
            val ws = i
            while (i < s.length && s[i].isWhitespace()) i++
            val sep = s.substring(ws, i)
            if (i >= s.length) break
            val start = i
            while (i < s.length && s[i] != '\n') {
                val c = s[i]
                i++
                val end = c in SENTENCE_END || (c == '.' && (i >= s.length || s[i].isWhitespace()))
                if (end) {
                    while (i < s.length && (s[i] in SENTENCE_END || s[i] == '.' || s[i] in CLOSERS)) i++
                    break
                }
            }
            val sentence = s.substring(start, i).trimEnd()
            if (sentence.length <= max) {
                out.add(sep to sentence)
            } else {
                var first = true
                for ((hs, piece) in hardSplit(sentence, max)) {
                    out.add((if (first) sep else hs) to piece)
                    first = false
                }
            }
        }
        return out
    }

    private fun hardSplit(s: String, max: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var rest = s
        var sep = ""
        while (rest.length > max) {
            var cut = -1
            val lo = (max * 0.5).toInt()
            for (k in max downTo lo) {
                val c = rest[k - 1]
                if (c in "，,、：: ") { cut = k; break }
            }
            if (cut < 0) cut = max
            val piece = rest.substring(0, cut)
            val trimmed = piece.trimEnd()
            out.add(sep to trimmed)
            sep = if (piece.length != trimmed.length) " " else ""
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(sep to rest)
        return out
    }
}
