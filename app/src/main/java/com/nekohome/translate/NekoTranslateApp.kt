package com.nekohome.translate

import android.app.Application
import android.os.Build
import com.nekohome.translate.diag.AppLog

/** Starts the persistent log (and its crash handler) before any Activity or ViewModel runs. */
class NekoTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(
            AppLog.TAG_APP,
            "process start: ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }
}
