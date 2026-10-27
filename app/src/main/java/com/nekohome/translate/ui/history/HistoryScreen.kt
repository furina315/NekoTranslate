@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.history

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekohome.translate.R
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.HistoryEntry
import com.nekohome.translate.data.HistoryRules
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface Row {
    data class Header(val day: LocalDate, val offset: Long) : Row
    data class Item(val entry: HistoryEntry) : Row
}

/**
 * 翻译历史: newest first, grouped by day. Tap an entry to put it back on the translate page,
 * swipe it left to delete (with undo), search from the top bar, clear everything from the menu icon.
 */
@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    historyEnabled: Boolean,
    onBack: () -> Unit,
    onPick: (HistoryEntry) -> Unit,
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.history_deleted)
    val undoText = stringResource(R.string.history_undo)

    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(entries) {
        val now = System.currentTimeMillis()
        buildList {
            for ((day, group) in HistoryRules.groupByDay(entries.orEmpty(), zone)) {
                add(Row.Header(day, HistoryRules.dayOffset(group.first().createdAt, now, zone)))
                group.forEach { add(Row.Item(it)) }
            }
        }
    }

    val closeSearch = {
        searching = false
        vm.setQuery("")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (searching) closeSearch() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.history_back))
                    }
                },
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text(stringResource(R.string.history_search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(stringResource(R.string.history_title))
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.history_search_clear))
                            }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.history_search))
                        }
                        IconButton(onClick = { confirmClear = true }, enabled = rows.isNotEmpty()) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.history_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                entries == null -> Unit
                rows.isEmpty() -> EmptyState(searching = query.isNotBlank(), historyEnabled = historyEnabled)
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        rows,
                        key = { r -> when (r) { is Row.Header -> "h${r.day}"; is Row.Item -> r.entry.id } },
                        contentType = { r -> if (r is Row.Header) 0 else 1 },
                    ) { row ->
                        val animate = if (LocalReduceMotion.current) Modifier else Modifier.animateItem(
                            fadeInSpec = Springs.smooth(), placementSpec = Springs.gentle(), fadeOutSpec = Springs.smooth(),
                        )
                        when (row) {
                            is Row.Header -> DayHeader(row, Modifier.then(animate))
                            is Row.Item -> HistoryItem(
                                entry = row.entry,
                                zone = zone,
                                onClick = { onPick(row.entry) },
                                onDelete = {
                                    vm.delete(row.entry)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val r = snackbar.showSnackbar(
                                            message = deletedText,
                                            actionLabel = undoText,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (r == SnackbarResult.ActionPerformed) vm.undoDelete(row.entry)
                                    }
                                },
                                modifier = animate,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    closeSearch()
                    vm.clearAll()
                }) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.history_cancel)) } },
        )
    }
}

@Composable
private fun EmptyState(searching: Boolean, historyEnabled: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (searching) Icons.Outlined.SearchOff else Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (searching) R.string.history_no_match else R.string.history_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (historyEnabled) R.string.history_empty_body else R.string.history_empty_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayHeader(row: Row.Header, modifier: Modifier = Modifier) {
    val text = when (row.offset) {
        0L -> stringResource(R.string.history_today)
        1L -> stringResource(R.string.history_yesterday)
        else -> {
            val sameYear = row.day.year == LocalDate.now().year
            row.day.format(DateTimeFormatter.ofPattern(if (sameYear) "M月d日" else "yyyy年M月d日"))
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    zone: ZoneId,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) currentOnDelete() },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 0.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.history_delete),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
    ) {
        val src = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            interactionSource = src,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().bounceClick(src, pressedScale = 0.98f),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${languageName(entry.sourceCode)} → ${languageName(entry.targetCode)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Instant.ofEpochMilli(entry.createdAt).atZone(zone).format(TIME_FORMAT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    entry.sourceText.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.targetText.trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.modelName.isNotBlank()) {
                    Text(
                        entry.modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun languageName(code: String): String =
    BuiltInProfiles.CUSTOM.findLanguage(code)?.nameZh ?: code
