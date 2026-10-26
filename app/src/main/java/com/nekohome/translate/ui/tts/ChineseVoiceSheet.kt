@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nekohome.translate.ui.tts

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SettingsVoice
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.tts.KokoroEngineLinks
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.TtsEngineList
import com.nekohome.translate.tts.openSystemTtsSettings
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import kotlinx.coroutines.launch

/**
 * Shown when Chinese text can't be read because the current engine has no Chinese voice:
 * recommends the sherpa-onnx Kokoro multi-lang engine (direct APK links on arm64 only), and offers
 * the system TTS settings, an in-app engine picker and 「不再提示」.
 */
@Composable
fun ChineseVoiceSheet(
    engines: TtsEngineList?,
    selectedEngine: String,
    onSelectEngine: (String) -> Unit,
    onNeverShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val arm64 = remember { TtsEngineChoice.isArm64(Build.SUPPORTED_ABIS.toList()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    /** Animates the sheet away, then runs [after]. */
    fun hide(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.zh_voice_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (arm64) R.string.zh_voice_body else R.string.zh_voice_body_other_abi),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (arm64) {
                SheetButton(
                    text = stringResource(R.string.zh_voice_mirror),
                    icon = Icons.Outlined.Download,
                    style = ButtonStyle.FILLED,
                    onClick = { openLink(context, KokoroEngineLinks.MIRROR_APK) },
                )
                SheetButton(
                    text = stringResource(R.string.zh_voice_huggingface),
                    icon = Icons.Outlined.Download,
                    style = ButtonStyle.TONAL,
                    onClick = { openLink(context, KokoroEngineLinks.HUGGINGFACE_APK) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LinkButton(stringResource(R.string.zh_voice_engine_list), Icons.AutoMirrored.Outlined.OpenInNew) {
                    openLink(context, KokoroEngineLinks.ENGINE_LIST)
                }
                if (arm64) {
                    LinkButton(stringResource(R.string.zh_voice_source), Icons.Outlined.Code) {
                        openLink(context, KokoroEngineLinks.SOURCE)
                    }
                }
            }
            Text(
                stringResource(R.string.zh_voice_install_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            SheetButton(
                text = stringResource(R.string.zh_voice_open_system),
                icon = Icons.Outlined.SettingsVoice,
                style = ButtonStyle.OUTLINED,
                onClick = { openSystemTtsSettings(context) },
            )
            SheetButton(
                text = stringResource(R.string.zh_voice_pick_engine),
                icon = Icons.Outlined.Tune,
                style = ButtonStyle.OUTLINED,
                onClick = { picking = !picking },
            )
            AnimatedVisibility(
                visible = picking,
                enter = if (reduceMotion) EnterTransition.None else
                    expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
                exit = if (reduceMotion) ExitTransition.None else
                    shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
            ) {
                TtsEngineOptions(
                    list = engines,
                    selected = selectedEngine,
                    onSelect = { pkg ->
                        onSelectEngine(pkg)
                        hide(onDismiss)
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            TextButton(
                onClick = { hide(onNeverShow) },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.zh_voice_never)) }
        }
    }
}

private enum class ButtonStyle { FILLED, TONAL, OUTLINED }

@Composable
private fun SheetButton(text: String, icon: ImageVector, style: ButtonStyle, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .bounceClick(src, pressedScale = 0.96f)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
    when (style) {
        ButtonStyle.FILLED -> Button(onClick = onClick, interactionSource = src, modifier = modifier) { content() }
        ButtonStyle.TONAL -> FilledTonalButton(onClick = onClick, interactionSource = src, modifier = modifier) { content() }
        ButtonStyle.OUTLINED -> OutlinedButton(onClick = onClick, interactionSource = src, modifier = modifier) { content() }
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, interactionSource = src, modifier = Modifier.bounceClick(src)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
}
