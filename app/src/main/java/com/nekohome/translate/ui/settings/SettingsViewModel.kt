package com.nekohome.translate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.data.SettingsRepository
import com.nekohome.translate.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { repository.setThemeMode(mode) } }
    fun setDynamicColor(on: Boolean) { viewModelScope.launch { repository.setDynamicColor(on) } }
    fun setDefaultTarget(code: String) { viewModelScope.launch { repository.setDefaultTarget(code) } }
    fun setKeepScreenOn(on: Boolean) { viewModelScope.launch { repository.setKeepScreenOn(on) } }
    fun setShowStats(on: Boolean) { viewModelScope.launch { repository.setShowStats(on) } }
}
