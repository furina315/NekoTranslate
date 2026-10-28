package com.nekohome.translate.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.data.HistoryEntry
import com.nekohome.translate.data.HistoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 翻译历史 list: the search query plus the matching entries, re-queried on every write. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HistoryStore.get(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _starredOnly = MutableStateFlow(false)
    val starredOnly: StateFlow<Boolean> = _starredOnly.asStateFlow()

    /** null until the first query finishes. */
    val entries: StateFlow<List<HistoryEntry>?> =
        combine(_query.debounce { if (it.isEmpty()) 0L else 200L }, _starredOnly, store.changes) { q, s, _ -> q to s }
            .mapLatest { (q, s) -> store.search(q, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(q: String) { _query.value = q }
    fun setStarredOnly(on: Boolean) { _starredOnly.value = on }
    fun toggleStar(entry: HistoryEntry) { viewModelScope.launch { store.setStarred(entry.id, !entry.starred) } }

    fun delete(entry: HistoryEntry) { viewModelScope.launch { store.delete(entry.id) } }
    fun undoDelete(entry: HistoryEntry) { viewModelScope.launch { store.restore(entry) } }
    fun clearAll() { viewModelScope.launch { store.clear() } }
}
