package com.nekohome.translate

import android.app.Application
import android.os.Build
import com.nekohome.translate.diag.AppLog
import com.nekohome.translate.diag.NativeCrashCollector

/**
 * Starts the persistent log (and its crash handler) before any Activity or ViewModel runs, then
 * kicks off the (non-blocking) import of earlier native crashes.
 */
class NekoTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(
            AppLog.TAG_APP,
            "process start: ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        // Android 12+: imports tombstones of earlier native crashes on a background thread.
        NativeCrashCollector.start(this)
    }
}
