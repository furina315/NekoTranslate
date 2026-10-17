package com.nekohome.translate.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.nekohome.translate.R
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.ui.models.ModelsScreen
import com.nekohome.translate.ui.settings.SettingsScreen
import com.nekohome.translate.ui.settings.SettingsViewModel
import com.nekohome.translate.ui.translate.TranslateScreen

enum class AppTab(@StringRes val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    TRANSLATE(R.string.tab_translate, Icons.Filled.Translate, Icons.Outlined.Translate),
    MODELS(R.string.tab_models, Icons.Filled.Memory, Icons.Outlined.Memory),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * Bottom-navigation shell. Each tab's composition state (scroll position, dialogs, text field
 * focus) is kept in a [rememberSaveableStateHolder] keyed by tab, and all tabs share the
 * activity-scoped [MainViewModel], so the loaded model stays loaded across tab switches.
 */
@Composable
fun AppRoot(vm: MainViewModel, settingsVm: SettingsViewModel, settings: AppSettings) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(AppTab.TRANSLATE) }
    val tabStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.dismissMessage()
        }
    }

    BackHandler(enabled = tab != AppTab.TRANSLATE) { tab = AppTab.TRANSLATE }

    KeepScreenOn(settings.keepScreenOn && (state.isTranslating || state.isLoading))

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    val selected = t == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.TRANSLATE -> TranslateScreen(
                        state = state,
                        vm = vm,
                        showStats = settings.showStats,
                        onOpenModels = { tab = AppTab.MODELS },
                    )
                    AppTab.MODELS -> ModelsScreen(state = state, vm = vm)
                    AppTab.SETTINGS -> SettingsScreen(settings = settings, vm = settingsVm)
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
