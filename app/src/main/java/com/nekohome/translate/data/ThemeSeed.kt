package com.nekohome.translate.data

/**
 * Preset seed colors for the app theme when Material You dynamic color is off (or on
 * Android < 12). A full light/dark Material 3 scheme is generated from each seed with the
 * HCT tonal-palette algorithm (see `ui/theme/SeedSchemes`).
 *
 * [key] is what gets persisted, so never rename existing keys. Labels live in strings.xml.
 * [neutral] seeds use the low-chroma "Neutral" scheme variant (for the brown-grey swatch);
 * everything else uses Tonal Spot, the variant Android itself uses for wallpaper colors.
 */
enum class ThemeSeed(val key: String, val argb: Int, val neutral: Boolean = false) {
    SAKURA("sakura", 0xFFEC6A9C.toInt()),
    CRIMSON("crimson", 0xFFD32F2F.toInt()),
    ORANGE("orange", 0xFFF4511E.toInt()),
    AMBER("amber", 0xFFFFA000.toInt()),
    LIME("lime", 0xFF7CB342.toInt()),
    GREEN("green", 0xFF43A047.toInt()),
    TEAL("teal", 0xFF00897B.toInt()),
    SKY("sky", 0xFF039BE5.toInt()),
    BLUE("blue", 0xFF1E88E5.toInt()),
    INDIGO("indigo", 0xFF3F51B5.toInt()),
    VIOLET("violet", 0xFF8E24AA.toInt()),
    TAUPE("taupe", 0xFF8D6E63.toInt(), neutral = true),
    ;

    companion object {
        /** Matches the app's original fixed indigo theme. */
        val DEFAULT = INDIGO

        fun fromKey(key: String?): ThemeSeed = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
