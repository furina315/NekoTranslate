package com.nekohome.translate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/** App-level settings (not tied to a model). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Preset seed used when dynamic color is off or unsupported. */
    val themeSeed: ThemeSeed = ThemeSeed.DEFAULT,
    val defaultTargetCode: String = "en",
    val keepScreenOn: Boolean = true,
    val showStats: Boolean = true,
    /** TTS engine package for 朗读; empty = 跟随系统默认. */
    val ttsEngine: String = "",
    /** 「不再提示」 on the missing-Chinese-voice sheet. */
    val chineseVoiceHintDismissed: Boolean = false,
    /** Save finished text translations to 翻译历史. */
    val historyEnabled: Boolean = true,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                themeMode = ThemeMode.fromKey(p[THEME]),
                dynamicColor = p[DYNAMIC_COLOR] ?: d.dynamicColor,
                themeSeed = ThemeSeed.fromKey(p[THEME_SEED]),
                defaultTargetCode = p[DEFAULT_TARGET] ?: d.defaultTargetCode,
                keepScreenOn = p[KEEP_SCREEN_ON] ?: d.keepScreenOn,
                showStats = p[SHOW_STATS] ?: d.showStats,
                ttsEngine = p[TTS_ENGINE] ?: d.ttsEngine,
                chineseVoiceHintDismissed = p[ZH_VOICE_HINT_DISMISSED] ?: d.chineseVoiceHintDismissed,
                historyEnabled = p[HISTORY_ENABLED] ?: d.historyEnabled,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = store.edit { it[DYNAMIC_COLOR] = on }
    suspend fun setThemeSeed(seed: ThemeSeed) = store.edit { it[THEME_SEED] = seed.key }
    suspend fun setDefaultTarget(code: String) = store.edit { it[DEFAULT_TARGET] = code }
    suspend fun setKeepScreenOn(on: Boolean) = store.edit { it[KEEP_SCREEN_ON] = on }
    suspend fun setShowStats(on: Boolean) = store.edit { it[SHOW_STATS] = on }
    suspend fun setTtsEngine(packageName: String) = store.edit { it[TTS_ENGINE] = packageName }
    suspend fun setChineseVoiceHintDismissed(dismissed: Boolean) =
        store.edit { it[ZH_VOICE_HINT_DISMISSED] = dismissed }

    suspend fun setHistoryEnabled(on: Boolean) = store.edit { it[HISTORY_ENABLED] = on }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_SEED = stringPreferencesKey("theme_seed")
        val DEFAULT_TARGET = stringPreferencesKey("default_target")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_STATS = booleanPreferencesKey("show_stats")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val ZH_VOICE_HINT_DISMISSED = booleanPreferencesKey("zh_voice_hint_dismissed")
    }
}
