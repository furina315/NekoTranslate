@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.ui.MainUiState
import com.nekohome.translate.ui.MainViewModel
import com.nekohome.translate.ui.common.Format

@Composable
fun ModelsScreen(state: MainUiState, vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importModel(uri)
    }
    val launchImport = { picker.launch(arrayOf("*/*")) }
    var pendingDelete by remember { mutableStateOf<LocalModel?>(null) }
    var showRuntimeInfo by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                actions = {
                    IconButton(onClick = launchImport, enabled = state.importProgress == null) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.models_import))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.importProgress?.let { p -> item(key = "import") { ImportProgress(p) } }

            if (state.models.isEmpty()) {
                item(key = "empty") { EmptyModels(onImport = launchImport, enabled = state.importProgress == null) }
            }

            items(state.models, key = { it.file.absolutePath }) { model ->
                ModelCard(
                    model = model,
                    state = state,
                    onSelect = { vm.selectModel(model) },
                    onLoad = { vm.loadModel(model) },
                    onUnload = vm::unloadModel,
                    onDelete = { pendingDelete = model },
                )
            }

            val selected = state.selectedModel
            if (selected != null) {
                item(key = "params-header") {
                    Text(
                        stringResource(R.string.models_params_title, selected.file.nameWithoutExtension),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item(key = "params") { ParamsPanel(model = selected, state = state, vm = vm) }
            } else if (state.models.isNotEmpty()) {
                item(key = "params-hint") {
                    Text(
                        stringResource(R.string.models_params_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.systemInfo.isNotEmpty()) {
                item(key = "runtime") {
                    Column {
                        TextButton(onClick = { showRuntimeInfo = !showRuntimeInfo }) {
                            Text(stringResource(R.string.models_runtime_info))
                        }
                        if (showRuntimeInfo) {
                            Text(
                                state.systemInfo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(stringResource(R.string.models_delete_body, model.fileName, Format.size(model.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(model)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.models_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.models_cancel)) }
            },
        )
    }
}

@Composable
private fun ImportProgress(p: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p < 0f) {
            Text(stringResource(R.string.models_importing_indeterminate), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.models_importing, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyModels(onImport: () -> Unit, enabled: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 48.dp),
    ) {
        Icon(
            Icons.Outlined.Memory,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.models_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.models_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport, enabled = enabled) {
            Icon(Icons.Outlined.FileOpen, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.models_import))
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    state: MainUiState,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    val isLoaded = state.isLoaded(model)
    val isSelected = state.selectedPath == model.file.absolutePath
    val isLoadingThis = state.loadingPath == model.file.absolutePath
    val busy = state.isLoading || state.isTranslating
    val params = state.paramsFor(model)
    val profile = state.profileFor(model)

    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
        colors = if (isLoaded) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            CardDefaults.outlinedCardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.file.nameWithoutExtension,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isLoaded) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.models_loaded_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(Format.size(model.sizeBytes), model.fileType, model.architecture).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (params.profileId == null) {
                    stringResource(R.string.models_profile_auto_line, profile.displayName)
                } else {
                    stringResource(R.string.models_profile_manual_line, profile.displayName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLoaded && state.loadInfo.isNotEmpty()) {
                Text(
                    state.loadInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.models_delete))
                }
                Spacer(Modifier.weight(1f))
                when {
                    isLoadingThis -> OutlinedButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.models_loading))
                    }
                    isLoaded -> OutlinedButton(onClick = onUnload, enabled = !state.isTranslating) {
                        Text(stringResource(R.string.models_unload))
                    }
                    else -> Button(onClick = onLoad, enabled = !busy) {
                        Text(stringResource(R.string.models_load))
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
