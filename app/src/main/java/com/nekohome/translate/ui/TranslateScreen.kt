package com.nekohome.translate.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.Language
import java.util.Locale

/**
 * Step-0 test screen: import/load a GGUF, pick languages, translate with streaming output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(vm: TranslateViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importModel(uri)
    }

    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.dismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- Model ----------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)

                    Dropdown(
                        label = state.selectedModel?.let { "${it.displayName} · ${it.fileType ?: "?"}" }
                            ?: if (state.models.isEmpty()) "尚未导入模型" else "选择模型",
                        items = state.models,
                        itemLabel = { "${it.fileName} (${formatSize(it.sizeBytes)})" },
                        onSelect = vm::selectModel,
                        enabled = state.models.isNotEmpty() && !state.isLoading && !state.isTranslating,
                    )

                    state.selectedModel?.let { m ->
                        Text(
                            "arch: ${m.architecture ?: "?"} · name: ${m.generalName ?: "?"} · " +
                                "ctx_train: ${m.contextLength ?: "?"} · 模板: ${if (m.hasChatTemplate) "有" else "无"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = state.importProgress == null,
                        ) { Text("导入 .gguf") }
                        val isSelectedLoaded = state.loaded != null &&
                            state.loaded?.path == state.selectedModel?.file?.absolutePath
                        if (isSelectedLoaded) {
                            FilledTonalButton(onClick = vm::unloadModel, enabled = !state.isTranslating) { Text("卸载") }
                        } else {
                            Button(
                                onClick = vm::loadSelectedModel,
                                enabled = state.selectedModel != null && !state.isLoading && !state.isTranslating,
                            ) { Text(if (state.isLoading) "加载中…" else "加载") }
                        }
                        TextButton(
                            onClick = vm::deleteSelectedModel,
                            enabled = state.selectedModel != null && !state.isLoading && !state.isTranslating,
                        ) { Text("删除") }
                    }

                    state.importProgress?.let { p ->
                        if (p < 0f) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("导入中 ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("线程 ${state.nThreads}", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.setThreads(state.nThreads - 1) }) { Text("−") }
                        TextButton(onClick = { vm.setThreads(state.nThreads + 1) }) { Text("+") }
                        Spacer(Modifier.width(8.dp))
                        Dropdown(
                            label = "ctx ${state.nCtx}",
                            items = listOf(2048, 4096, 8192, 16384),
                            itemLabel = { it.toString() },
                            onSelect = vm::setContext,
                            enabled = !state.isLoading,
                        )
                    }
                    Text("线程数 / ctx 在下次加载时生效", style = MaterialTheme.typography.labelSmall)

                    if (state.loadStats.isNotEmpty()) {
                        Text(state.loadStats, style = MaterialTheme.typography.bodySmall)
                    }

                    Dropdown(
                        label = "Profile: ${state.profile.displayName}",
                        items = BuiltInProfiles.ALL,
                        itemLabel = { it.displayName },
                        onSelect = vm::selectProfile,
                        enabled = !state.isTranslating,
                    )
                    if (state.profile.isCustom) {
                        OutlinedTextField(
                            value = state.customTemplate,
                            onValueChange = vm::setCustomTemplate,
                            label = { Text("Prompt 模板（{source_lang} {target_lang} {text}）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                }
            }

            // ---------------- Languages ----------------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    Dropdown(
                        label = state.sourceLanguage.label(),
                        items = listOf(Language.AUTO) + state.profile.languages,
                        itemLabel = { it.label() },
                        onSelect = { vm.setSource(it.code) },
                        enabled = !state.isTranslating,
                    )
                }
                TextButton(onClick = vm::swapLanguages, enabled = !state.isTranslating) { Text("⇄") }
                Box(Modifier.weight(1f)) {
                    Dropdown(
                        label = state.targetLanguage.label(),
                        items = state.profile.languages,
                        itemLabel = { it.label() },
                        onSelect = { vm.setTarget(it.code) },
                        enabled = !state.isTranslating,
                    )
                }
            }

            // ---------------- Input ----------------
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::setInput,
                label = { Text("原文") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                minLines = 4,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::translate, enabled = state.canTranslate) {
                    Text(if (state.isTranslating) "翻译中…" else "翻译")
                }
                OutlinedButton(onClick = vm::stop, enabled = state.isTranslating) { Text("停止") }
            }
            if (state.loaded == null) {
                Text("先导入并加载一个模型", style = MaterialTheme.typography.bodySmall)
            }

            // ---------------- Output ----------------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                SelectionContainer {
                    Text(
                        text = state.output.ifEmpty { if (state.isTranslating) "…" else "译文" },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (state.genStats.isNotEmpty()) {
                Text(state.genStats, style = MaterialTheme.typography.bodySmall)
            }
            if (state.systemInfo.isNotEmpty()) {
                Text(
                    state.systemInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Simple button + DropdownMenu (avoids the frequently-changing ExposedDropdownMenu API). */
@Composable
private fun <T> Dropdown(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
