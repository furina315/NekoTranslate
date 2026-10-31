package com.nekohome.translate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.data.GlossaryEntry
import com.nekohome.translate.data.GlossaryRules
import com.nekohome.translate.data.SettingsRepository
import com.nekohome.translate.data.ThemeMode
import com.nekohome.translate.data.ThemeSeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nekohome.translate.diag.AppLog
import com.nekohome.translate.diag.LogExporter
import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { repository.setThemeMode(mode) } }
    fun setDynamicColor(on: Boolean) { viewModelScope.launch { repository.setDynamicColor(on) } }
    fun setThemeSeed(seed: ThemeSeed) { viewModelScope.launch { repository.setThemeSeed(seed) } }
    fun setDefaultTarget(code: String) { viewModelScope.launch { repository.setDefaultTarget(code) } }
    fun setKeepScreenOn(on: Boolean) { viewModelScope.launch { repository.setKeepScreenOn(on) } }
    fun setShowStats(on: Boolean) { viewModelScope.launch { repository.setShowStats(on) } }
    fun setHistoryEnabled(on: Boolean) { viewModelScope.launch { repository.setHistoryEnabled(on) } }
    fun setGlossaryEnabled(on: Boolean) { viewModelScope.launch { repository.setGlossaryEnabled(on) } }

    /** [index] = -1 adds; otherwise replaces that entry. Invalid entries are ignored. */
    fun saveGlossaryEntry(index: Int, entry: GlossaryEntry) {
        if (!GlossaryRules.isValid(entry)) return
        val list = settings.value.glossary.toMutableList()
        val e = GlossaryRules.normalize(entry)
        if (index in list.indices) list[index] = e
        else if (list.size < GlossaryRules.MAX_ENTRIES) list.add(0, e)
        viewModelScope.launch { repository.setGlossary(list) }
    }

    fun deleteGlossaryEntry(index: Int) {
        val list = settings.value.glossary.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        viewModelScope.launch { repository.setGlossary(list) }
    }

    fun restoreGlossaryEntry(index: Int, entry: GlossaryEntry) {
        val list = settings.value.glossary.toMutableList()
        list.add(index.coerceIn(0, list.size), entry)
        viewModelScope.launch { repository.setGlossary(list) }
    }

    fun setTtsRate(v: Float) { viewModelScope.launch { repository.setTtsRate(v) } }
    fun setTtsPitch(v: Float) { viewModelScope.launch { repository.setTtsPitch(v) } }
    fun setTtsEngine(packageName: String) { viewModelScope.launch { repository.setTtsEngine(packageName) } }
    fun setChineseVoiceHintDismissed(dismissed: Boolean) {
        viewModelScope.launch { repository.setChineseVoiceHintDismissed(dismissed) }
    }

    // ------------------------------------------------------------------ 诊断

    /** Full export text (header + persistent log + logcat). */
    suspend fun buildLogReport(): String = LogExporter.buildReport(getApplication(), settings.value)

    /** Export written to the share cache (for ACTION_SEND through the FileProvider). */
    suspend fun writeLogShareFile(): File = LogExporter.writeShareFile(getApplication(), buildLogReport())

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        AppLog.clear()
        AppLog.i(AppLog.TAG_APP, "logs cleared by the user")
    }
}
