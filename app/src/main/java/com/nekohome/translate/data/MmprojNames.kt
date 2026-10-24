package com.nekohome.translate.data

/**
 * File-name heuristics for vision projectors (mmproj GGUFs), used to recognise an mmproj picked
 * through the normal model import and to pair it with its model automatically.
 *
 * Common naming schemes:
 *  - `Index-Translate-2B.mmproj-Q8_0.gguf`  next to `Index-Translate-2B.Q4_K_M.gguf` (IndexTeam)
 *  - `mmproj-Qwen3-VL-2B-Instruct-F16.gguf` next to `Qwen3-VL-2B-Instruct-Q4_K_M.gguf` (ggml-org)
 *  - `Foo-mmproj-f16.gguf`, `foo.mmproj.gguf`
 */
object MmprojNames {

    private val MMPROJ = Regex("mmproj", RegexOption.IGNORE_CASE)

    /** Trailing quantisation tag such as `.Q4_K_M`, `-IQ4_XS`, `-f16`, `_bf16`, `-Q8_0`. */
    private val QUANT_SUFFIX = Regex(
        "[-_. ](?:i?q\\d[a-z0-9_]*|bf16|f16|f32|fp16|fp32)$",
        RegexOption.IGNORE_CASE,
    )

    private val SEPARATORS = Regex("[-_. ]+")

    /** True when the file name says it is a vision projector. */
    fun looksLikeMmproj(fileName: String): Boolean = MMPROJ.containsMatchIn(fileName)

    /**
     * Normalised model family of a model or mmproj file name: extension, `mmproj` token and
     * quantisation tag removed, lower case, separators unified to `-`.
     * `Index-Translate-2B.mmproj-Q8_0.gguf` and `Index-Translate-2B.Q4_K_M.gguf` both give
     * `index-translate-2b`.
     */
    fun familyStem(fileName: String): String {
        var name = fileName.trim().replace(Regex("\\.gguf$", RegexOption.IGNORE_CASE), "")
        val m = MMPROJ.find(name)
        if (m != null) {
            val before = name.substring(0, m.range.first).trimEnd('-', '_', '.', ' ')
            val after = name.substring(m.range.last + 1).trimStart('-', '_', '.', ' ')
            // "Foo.mmproj-Q8_0" -> "Foo"; "mmproj-Foo-F16" -> "Foo-F16" (quant stripped below).
            name = if (before.isNotEmpty()) before else after
        }
        while (true) {
            val stripped = QUANT_SUFFIX.replace(name, "")
            if (stripped == name || stripped.isEmpty()) break
            name = stripped
        }
        return name.lowercase().replace(SEPARATORS, "-").trim('-')
    }

    /**
     * Models an mmproj belongs to: those whose family stem equals the mmproj's (several quants
     * of one model), else those whose stem extends it (e.g. a renamed `Index-Translate-2B-mine`).
     */
    fun candidatesFor(mmprojFileName: String, modelFileNames: List<String>): List<String> {
        val stem = familyStem(mmprojFileName)
        if (stem.isEmpty()) return emptyList()
        val stems = modelFileNames.associateWith { familyStem(it) }
        val exact = stems.filterValues { it == stem }.keys.toList()
        if (exact.isNotEmpty()) return exact
        return stems.filterValues { it.startsWith("$stem-") }.keys.toList()
    }

    /**
     * The model to attach an mmproj to: the only candidate, or [preferred] (e.g. the model being
     * edited) when it is one of several candidates; null when there is no clear choice.
     */
    fun bestModelFor(mmprojFileName: String, modelFileNames: List<String>, preferred: String? = null): String? {
        val c = candidatesFor(mmprojFileName, modelFileNames)
        return when {
            c.size == 1 -> c.single()
            preferred != null && preferred in c -> preferred
            else -> null
        }
    }
}
