package com.nekohome.translate.ui.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nekohome.translate.R
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.TtsEngineList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Label for the engine actually in use for the [saved] choice (falls back to 跟随系统默认). */
@Composable
fun ttsEngineSummary(saved: String, list: TtsEngineList?): String {
    if (list != null && list.engines.isEmpty()) return stringResource(R.string.settings_tts_engine_none)
    val resolved = TtsEngineChoice.resolve(saved, list?.engines)
    if (resolved != null) return list?.find(resolved)?.label ?: resolved
    if (saved.isNotBlank() && list != null) return stringResource(R.string.settings_tts_engine_missing)
    return defaultEngineLabel(list)
}

@Composable
private fun defaultEngineLabel(list: TtsEngineList?): String {
    val name = list?.find(list.systemDefault)?.label
    return if (name != null) stringResource(R.string.settings_tts_engine_default_named, name)
    else stringResource(R.string.settings_tts_engine_default)
}

/**
 * Radio list: 「跟随系统默认」 then every installed engine with its app icon. [selected] is the
 * saved choice; an engine that is no longer installed shows the default as selected.
 */
@Composable
fun TtsEngineOptions(
    list: TtsEngineList?,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effective = TtsEngineChoice.resolve(selected, list?.engines) ?: TtsEngineChoice.SYSTEM_DEFAULT
    LazyColumn(modifier.heightIn(max = 420.dp).selectableGroup()) {
        item(key = "default") {
            EngineRow(
                label = defaultEngineLabel(list),
                packageName = null,
                selected = effective == TtsEngineChoice.SYSTEM_DEFAULT,
                onClick = { onSelect(TtsEngineChoice.SYSTEM_DEFAULT) },
            )
        }
        items(list?.engines.orEmpty(), key = { it.packageName }) { engine ->
            EngineRow(
                label = engine.label,
                packageName = engine.packageName,
                selected = effective == engine.packageName,
                onClick = { onSelect(engine.packageName) },
            )
        }
    }
}

@Composable
private fun EngineRow(label: String, packageName: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        EngineIcon(packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (packageName != null) {
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The engine app's launcher icon (loaded off the main thread), or a generic icon. */
@Composable
private fun EngineIcon(packageName: String?) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 28.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = packageName?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(28.dp))
    } else {
        Icon(
            if (packageName == null) Icons.Outlined.PhoneAndroid else Icons.Outlined.RecordVoiceOver,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp).padding(2.dp),
        )
    }
}

/** 设置 → 朗读 → 朗读引擎 picker. */
@Composable
fun TtsEngineDialog(
    list: TtsEngineList?,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tts_engine)) },
        text = {
            TtsEngineOptions(list, selected, onSelect = {
                onSelect(it)
                onDismiss()
            })
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) }
        },
    )
}

/** Opens [url] in a browser (toast when there is none). */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
    }
}
