package com.nekohome.translate.diag

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * The app's FileProvider (camera photos, exported logs). Identical to [FileProvider] except that
 * an exported `.log` reports `text/plain`: the platform MIME map has no ".log" entry, so the
 * stock provider would say application/octet-stream and some share targets would treat the log
 * as an opaque file.
 */
class LogFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".log", ignoreCase = true) == true) LogExporter.LOG_MIME else super.getType(uri)
}
