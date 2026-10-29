package com.nekohome.translate.ui.quick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekohome.translate.MainActivity
import com.nekohome.translate.R
import com.nekohome.translate.data.IncomingText
import com.nekohome.translate.data.ThemeMode
import com.nekohome.translate.ui.settings.SettingsViewModel
import com.nekohome.translate.ui.theme.NekoTranslateTheme

/**
 * 选中文字菜单（PROCESS_TEXT）/ 分享文本（SEND text/plain）: a translucent activity that shows a
 * bottom translation card over the app the text came from, like Google Translate's tap-to-translate,
 * instead of opening the main screen. It has no task affinity of its own and stays out of 最近任务;
 * closing it returns to the original app. The model is the process-wide one ([com.nekohome.translate.engine.ModelSession]).
 */
class QuickTranslateActivity : ComponentActivity() {

    private val vm: QuickTranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val request = IncomingText.quickRequest(
            action = intent?.action,
            mimeType = intent?.type,
            processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            sendText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT),
            readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false,
            forResult = callingActivity != null,
        )
        if (request == null) {
            Toast.makeText(this, R.string.quick_empty, Toast.LENGTH_SHORT).show()
            finishQuietly()
            return
        }
        vm.start(request) // no-op after a configuration change
        setContent {
            val settingsVm: SettingsViewModel = viewModel()
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            NekoTranslateTheme(darkTheme = dark, dynamicColor = settings.dynamicColor, seed = settings.themeSeed) {
                QuickTranslateScreen(
                    vm = vm,
                    onClosed = ::finishQuietly,
                    onCopy = ::copy,
                    onReplace = ::replace,
                    onOpenApp = ::openInApp,
                )
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NekoTranslate", text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.quick_copied, Toast.LENGTH_SHORT).show()
    }

    /** 「替换」: hand the translation back to the editable selection (PROCESS_TEXT result). */
    private fun replace(text: String): Boolean {
        val value = IncomingText.replacement(text) ?: return false
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, value))
        return true
    }

    private fun openInApp(source: String, translation: String?, targetCode: String?) {
        vm.dismiss()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_TRANSLATE_TEXT)
            .putExtra(Intent.EXTRA_TEXT, source)
            .putExtra(MainActivity.EXTRA_TRANSLATION, translation)
            .putExtra(MainActivity.EXTRA_TARGET, targetCode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishQuietly()
    }

    private fun finishQuietly() {
        vm.dismiss()
        finish()
        // The card animates itself out; no window animation on top of it.
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }
}
