package com.nekohome.translate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.annotation.StringRes
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekohome.translate.R
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.tts.openTtsSettings
import com.nekohome.translate.ui.models.ModelsScreen
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.bounceClick
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
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.TRANSLATE) }
    val tabStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }

    val context = LocalContext.current
    val ttsSettingsLabel = stringResource(R.string.tts_open_settings)
    LaunchedEffect(state.message, state.messageAction) {
        val msg = state.message
        if (msg != null) {
            val action = state.messageAction
            val result = snackbar.showSnackbar(
                message = msg,
                actionLabel = if (action == MessageAction.OPEN_TTS_SETTINGS) ttsSettingsLabel else null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && action == MessageAction.OPEN_TTS_SETTINGS) {
                openTtsSettings(context)
            }
            vm.dismissMessage()
        }
    }

    // Reading aloud belongs to the 翻译 tab: leaving it stops the voice.
    LaunchedEffect(tab) {
        if (tab != AppTab.TRANSLATE) vm.stopSpeaking()
    }

    BackHandler(enabled = tab != AppTab.TRANSLATE) { tab = AppTab.TRANSLATE }

    KeepScreenOn(settings.keepScreenOn && (state.isTranslating || state.isLoading))

    val reduceMotion = LocalReduceMotion.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    val selected = t == tab
                    val src = remember { MutableInteractionSource() }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        interactionSource = src,
                        icon = {
                            Icon(
                                if (selected) t.selectedIcon else t.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .bounceClick(src, pressedScale = 0.8f)
                                    .selectionPop(selected),
                            )
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    // Slide a fraction of the width toward the new tab's side of the bar. Whole-screen
                    // motion: stiff and almost critically damped (no visible overshoot), and the
                    // slide/fade run in the graphics layer only.
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    val slide = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    )
                    (slideInHorizontally(slide) { w -> dir * w / 8 } + fadeIn(spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(
                            slideOutHorizontally(slide) { w -> -dir * w / 8 } + fadeOut(spring(stiffness = Spring.StiffnessHigh)),
                        )
                        // Every tab fills the same area: skip size animation (and its extra
                        // measure passes) entirely.
                        .using(null)
                }
            },
            label = "tab",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { shown ->
            tabStates.SaveableStateProvider(shown.name) {
                when (shown) {
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

/** Icon "pops" (shrinks, then springs past full size) when its tab becomes selected. */
@Composable
private fun Modifier.selectionPop(selected: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) {
            first = false
        } else if (selected && !reduceMotion) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
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
