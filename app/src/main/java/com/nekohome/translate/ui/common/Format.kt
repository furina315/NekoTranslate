package com.nekohome.translate.ui.common

import java.util.Locale

object Format {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /** Two decimals, trailing zeros kept short ("0.7", "1.05"). */
    fun decimal(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
