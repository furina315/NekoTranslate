package com.nekohome.translate

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekohome.translate.data.ThemeMode
import com.nekohome.translate.ui.AppRoot
import com.nekohome.translate.ui.MainViewModel
import com.nekohome.translate.ui.settings.SettingsViewModel
import com.nekohome.translate.ui.theme.NekoTranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val settingsVm: SettingsViewModel = viewModel()
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Match system bar icon colors to the app theme, not just the system setting.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            NekoTranslateTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                seed = settings.themeSeed,
            ) {
                val vm: MainViewModel = viewModel()
                AppRoot(vm = vm, settingsVm = settingsVm, settings = settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** 选中文字菜单（PROCESS_TEXT）和系统分享（SEND 文本 / 图片）。 */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val vm = viewModels<MainViewModel>().value
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                vm.receiveText(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) {
                    val uri = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                    if (uri != null) vm.receiveImage(uri)
                } else {
                    vm.receiveText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                }
            }
        }
        // Handled: a recreate must not replay it.
        intent.action = null
    }

    private companion object {
        // Same scrims androidx.activity uses by default for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
