@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SettingsVoice
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekohome.translate.BuildConfig
import com.nekohome.translate.R
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.ThemeMode
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.tts.TtsEngineList
import com.nekohome.translate.tts.openSystemTtsSettings
import com.nekohome.translate.ui.tts.TtsEngineDialog
import com.nekohome.translate.ui.tts.ttsEngineSummary

private enum class SettingsDialog { THEME, TARGET, TTS_ENGINE }

@Composable
fun SettingsScreen(settings: AppSettings, vm: SettingsViewModel, ttsEngines: TtsEngineList?) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Union of every built-in profile's languages.
    val languages = BuiltInProfiles.CUSTOM.languages
    val targetName = languages.firstOrNull { it.code == settings.defaultTargetCode }
        ?.let { "${it.nameZh} · ${it.nameEn}" } ?: settings.defaultTargetCode
    val githubUrl = stringResource(R.string.settings_github_url)
    val noBrowser = stringResource(R.string.settings_no_browser)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))
            SettingItem(
                icon = Icons.Outlined.DarkMode,
                title = stringResource(R.string.settings_theme),
                summary = themeLabel(settings.themeMode),
                onClick = { dialog = SettingsDialog.THEME },
            )
            SwitchItem(
                icon = Icons.Outlined.ColorLens,
                title = stringResource(R.string.settings_dynamic_color),
                summary = stringResource(
                    if (dynamicSupported) R.string.settings_dynamic_color_desc else R.string.settings_dynamic_color_unsupported,
                ),
                checked = settings.dynamicColor && dynamicSupported,
                enabled = dynamicSupported,
                onCheckedChange = vm::setDynamicColor,
            )
            // Preset palette: only meaningful while dynamic color is not in effect.
            val reduceMotion = LocalReduceMotion.current
            AnimatedVisibility(
                visible = !(settings.dynamicColor && dynamicSupported),
                enter = if (reduceMotion) EnterTransition.None else
                    expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
                exit = if (reduceMotion) ExitTransition.None else
                    shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
            ) {
                ThemeColorPicker(selected = settings.themeSeed, onSelect = vm::setThemeSeed)
            }

            SectionHeader(stringResource(R.string.settings_section_translate))
            SettingItem(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.settings_default_target),
                summary = targetName,
                onClick = { dialog = SettingsDialog.TARGET },
            )
            SwitchItem(
                icon = Icons.Outlined.Smartphone,
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_desc),
                checked = settings.keepScreenOn,
                onCheckedChange = vm::setKeepScreenOn,
            )
            SwitchItem(
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.settings_show_stats),
                summary = stringResource(R.string.settings_show_stats_desc),
                checked = settings.showStats,
                onCheckedChange = vm::setShowStats,
            )

            SectionHeader(stringResource(R.string.settings_section_tts))
            SettingItem(
                icon = Icons.Outlined.RecordVoiceOver,
                title = stringResource(R.string.settings_tts_engine),
                summary = ttsEngineSummary(settings.ttsEngine, ttsEngines),
                onClick = { dialog = SettingsDialog.TTS_ENGINE },
            )
            SettingItem(
                icon = Icons.Outlined.SettingsVoice,
                title = stringResource(R.string.settings_tts_system),
                summary = stringResource(R.string.settings_tts_system_desc),
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { openSystemTtsSettings(context) },
            )
            // Only after 「不再提示」 on the missing-Chinese-voice sheet: tap to show it again.
            val hintResetText = stringResource(R.string.settings_tts_zh_hint_reset)
            AnimatedVisibility(
                visible = settings.chineseVoiceHintDismissed,
                enter = if (reduceMotion) EnterTransition.None else
                    expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
                exit = if (reduceMotion) ExitTransition.None else
                    shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
            ) {
                SettingItem(
                    icon = Icons.Outlined.NotificationsOff,
                    title = stringResource(R.string.settings_tts_zh_hint),
                    summary = stringResource(R.string.settings_tts_zh_hint_off),
                    onClick = {
                        vm.setChineseVoiceHintDismissed(false)
                        Toast.makeText(context, hintResetText, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingItem(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.app_name),
                summary = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            )
            SettingItem(
                icon = Icons.Outlined.Memory,
                title = stringResource(R.string.settings_llama_version),
                summary = BuildConfig.LLAMA_CPP_VERSION,
            )
            SettingItem(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.settings_license),
                summary = stringResource(R.string.settings_license_value),
            )
            SettingItem(
                icon = Icons.Outlined.Code,
                title = stringResource(R.string.settings_github),
                summary = githubUrl,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { openUrl(context, githubUrl, noBrowser) },
            )
            SettingItem(
                icon = Icons.Outlined.Gavel,
                title = stringResource(R.string.settings_open_source),
                summary = stringResource(R.string.settings_open_source_desc),
            )
        }
    }

    when (dialog) {
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.toList(),
            selected = settings.themeMode,
            label = { themeLabel(it) },
            onSelect = vm::setThemeMode,
            onDismiss = { dialog = null },
        )
        SettingsDialog.TARGET -> ChoiceDialog(
            title = stringResource(R.string.settings_default_target),
            options = languages.map { it.code },
            selected = settings.defaultTargetCode,
            label = { code -> languages.first { it.code == code }.let { "${it.nameZh} · ${it.nameEn}" } },
            onSelect = vm::setDefaultTarget,
            onDismiss = { dialog = null },
        )
        SettingsDialog.TTS_ENGINE -> TtsEngineDialog(
            list = ttsEngines,
            selected = settings.ttsEngine,
            onSelect = vm::setTtsEngine,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    summary: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    onDismiss()
                                },
                            ),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) }
        },
    )
}

private fun openUrl(context: Context, url: String, errorText: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
    }
}
