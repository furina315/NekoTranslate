@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.Language
import com.nekohome.translate.llama.StopReason
import com.nekohome.translate.ui.MainUiState
import com.nekohome.translate.ui.MainViewModel
import java.util.Locale

@Composable
fun TranslateScreen(
    state: MainUiState,
    vm: MainViewModel,
    showStats: Boolean,
    onOpenModels: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ModelChip(state, onOpenModels)
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        if (state.loaded == null) {
            NoModelState(
                state = state,
                onOpenModels = onOpenModels,
                onLoad = vm::loadModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LanguageBar(state, vm)
                InputCard(state, vm)
                if (state.output.isNotEmpty() || state.isTranslating) {
                    OutputCard(state, showStats)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ModelChip(state: MainUiState, onClick: () -> Unit) {
    val label = when {
        state.isLoading -> stringResource(R.string.translate_model_loading)
        state.loadedModel != null -> state.loadedModel!!.file.nameWithoutExtension
        else -> stringResource(R.string.translate_model_none)
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        },
        leadingIcon = {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(AssistChipDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Memory, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
            }
        },
    )
}

@Composable
private fun NoModelState(
    state: MainUiState,
    onOpenModels: () -> Unit,
    onLoad: (com.nekohome.translate.data.LocalModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Translate,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.translate_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.translate_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        val quick = state.selectedModel
        if (state.isLoading) {
            CircularProgressIndicator()
        } else if (quick != null) {
            Button(onClick = { onLoad(quick) }) {
                Text(
                    stringResource(R.string.translate_empty_load, quick.file.nameWithoutExtension),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenModels) { Text(stringResource(R.string.translate_empty_go_models)) }
        } else {
            Button(onClick = onOpenModels) { Text(stringResource(R.string.translate_empty_go_models)) }
        }
    }
}

@Composable
private fun LanguageBar(state: MainUiState, vm: MainViewModel) {
    val profile = state.activeProfile
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            LanguageButton(
                selected = state.sourceLanguage,
                options = listOf(Language.AUTO) + profile.languages,
                onSelect = { vm.setSource(it.code) },
                enabled = !state.isTranslating,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::swapLanguages, enabled = !state.isTranslating) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.translate_swap))
            }
            LanguageButton(
                selected = state.targetLanguage,
                options = profile.languages,
                onSelect = { vm.setTarget(it.code) },
                enabled = !state.isTranslating,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguageButton(
    selected: Language,
    options: List<Language>,
    onSelect: (Language) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                languageLabel(selected),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(if (lang.isAuto) languageLabel(lang) else "${lang.nameZh}  ·  ${lang.nameEn}")
                    },
                    trailingIcon = {
                        if (lang.code == selected.code) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(lang)
                    },
                )
            }
        }
    }
}

@Composable
private fun languageLabel(lang: Language): String =
    if (lang.isAuto) stringResource(R.string.translate_auto_detect) else lang.nameZh

@Composable
private fun InputCard(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        TextField(
            value = state.input,
            onValueChange = vm::setInput,
            placeholder = { Text(stringResource(R.string.translate_input_hint)) },
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 12.dp),
        ) {
            if (state.input.isEmpty()) {
                IconButton(onClick = { pasteText(context)?.let(vm::setInput) }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.translate_paste))
                }
            } else {
                IconButton(onClick = vm::clearInput, enabled = !state.isTranslating) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.translate_clear))
                }
                Text(
                    state.input.length.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.isTranslating) {
                FilledTonalButton(onClick = vm::stop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.translate_stop))
                }
            } else {
                Button(onClick = vm::translate, enabled = state.canTranslate) {
                    Icon(Icons.Filled.Translate, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.translate_action))
                }
            }
        }
    }
}

@Composable
private fun OutputCard(state: MainUiState, showStats: Boolean) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.translate_copied)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                state.targetLanguage.nameZh,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            if (state.isTranslating && state.output.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp, bottom = 16.dp))
            } else {
                SelectionContainer(Modifier.padding(end = 12.dp)) {
                    Text(
                        state.output,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                val stats = state.stats
                Text(
                    text = if (showStats && stats != null) statsLabel(stats) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        copyText(context, state.output)
                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = state.output.isNotEmpty() && !state.isTranslating,
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.translate_copy))
                }
            }
        }
    }
}

@Composable
private fun statsLabel(stats: com.nekohome.translate.ui.GenStats): String {
    val base = stringResource(
        R.string.translate_stats,
        stats.generatedTokens,
        String.format(Locale.US, "%.1f", stats.tokensPerSecond),
    )
    val reason = when (stats.stopReason) {
        StopReason.EOG -> null
        StopReason.MAX_TOKENS -> stringResource(R.string.translate_stop_max_tokens)
        StopReason.CONTEXT_FULL -> stringResource(R.string.translate_stop_context_full)
        StopReason.CANCELLED -> stringResource(R.string.translate_stop_cancelled)
        StopReason.ERROR -> stringResource(R.string.translate_stop_error)
    }
    return if (reason == null) base else "$base · $reason"
}

private fun clipboard(context: Context): ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

private fun copyText(context: Context, text: String) {
    clipboard(context)?.setPrimaryClip(ClipData.newPlainText("NekoTranslate", text))
}

private fun pasteText(context: Context): String? =
    clipboard(context)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
