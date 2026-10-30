@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.glossary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.data.GlossaryEntry
import com.nekohome.translate.data.GlossaryRules
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/** 术语表管理：开关、条目列表（点按编辑、删除可撤销）、右上角添加。 */
@Composable
fun GlossaryScreen(settings: AppSettings, vm: SettingsViewModel, onBack: () -> Unit) {
    // editing: -2 = closed, -1 = new entry, >= 0 = index being edited
    var editing by rememberSaveable { mutableStateOf(-2) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.glossary_deleted)
    val undoText = stringResource(R.string.glossary_undo)
    val entries = settings.glossary

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.glossary_back))
                    }
                },
                title = { Text(stringResource(R.string.glossary_title)) },
                actions = {
                    IconButton(onClick = { editing = -1 }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.glossary_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.glossary_enable)) },
                supportingContent = { Text(stringResource(R.string.glossary_enable_desc)) },
                trailingContent = { Switch(checked = settings.glossaryEnabled, onCheckedChange = vm::setGlossaryEnabled) },
            )
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.MenuBook, contentDescription = null,
                        modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.glossary_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.glossary_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(entries, key = { i, e -> "${e.source}\u0000$i" }) { index, entry ->
                        Card(
                            onClick = { editing = index },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.source, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "→ " + entry.target,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    vm.deleteGlossaryEntry(index)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val r = snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short)
                                        if (r == SnackbarResult.ActionPerformed) vm.restoreGlossaryEntry(index, entry)
                                    }
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.glossary_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editing != -2) {
        val initial = entries.getOrNull(editing)
        EntryDialog(
            initial = initial,
            onDismiss = { editing = -2 },
            onSave = { vm.saveGlossaryEntry(editing, it); editing = -2 },
        )
    }
}

@Composable
private fun EntryDialog(initial: GlossaryEntry?, onDismiss: () -> Unit, onSave: (GlossaryEntry) -> Unit) {
    var source by rememberSaveable { mutableStateOf(initial?.source.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(initial?.target.orEmpty()) }
    val entry = GlossaryEntry(source, target)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.glossary_new_title else R.string.glossary_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = source, onValueChange = { source = it.take(GlossaryRules.MAX_TERM_LENGTH) },
                    label = { Text(stringResource(R.string.glossary_source_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it.take(GlossaryRules.MAX_TERM_LENGTH) },
                    label = { Text(stringResource(R.string.glossary_target_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default,
                )
                Text(
                    stringResource(R.string.glossary_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(entry) }, enabled = GlossaryRules.isValid(entry)) {
                Text(stringResource(R.string.glossary_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.glossary_cancel)) } },
    )
}
