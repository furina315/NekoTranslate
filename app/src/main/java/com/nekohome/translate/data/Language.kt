package com.nekohome.translate.data

/**
 * A language as a model's prompt refers to it.
 *
 * @param code     stable id used in the UI and settings (BCP-47-ish; "auto" = detect)
 * @param nameZh   name used in Chinese prompts (e.g. "英语")
 * @param nameEn   name used in English prompts (e.g. "English")
 */
data class Language(
    val code: String,
    val nameZh: String,
    val nameEn: String,
) {
    val isAuto: Boolean get() = code == AUTO_CODE

    /** Chinese-family target/source (drives Hy-MT2's Chinese-vs-English prompt choice). */
    val isChineseFamily: Boolean get() = code == "zh" || code == "zh-Hant" || code == "yue"

    /** Label shown in the UI. */
    fun label(): String = if (isAuto) "自动检测 / Auto" else "$nameZh · $nameEn"

    companion object {
        const val AUTO_CODE = "auto"
        val AUTO = Language(AUTO_CODE, "自动检测", "Auto")
    }
}
