package com.nekohome.translate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.modelParamsDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_params")

/** Per-model parameter overrides, keyed by the model's file name in filesDir/models. */
class ModelParamsStore(context: Context) {

    private val store = context.applicationContext.modelParamsDataStore

    suspend fun loadAll(): Map<String, ModelParams> {
        val prefs = runCatching { store.data.first() }.getOrNull() ?: return emptyMap()
        val flat = prefs.asMap().entries
            .mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }
            .toMap()
        return ModelParamsCodec.decodeAll(flat)
    }

    suspend fun save(modelKey: String, params: ModelParams) {
        store.edit { prefs ->
            prefs.asMap().keys
                .map { it.name }
                .filter { ModelParamsCodec.belongsTo(it, modelKey) }
                .forEach { prefs.remove(stringPreferencesKey(it)) }
            ModelParamsCodec.encode(modelKey, params).forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
        }
    }

    suspend fun remove(modelKey: String) = save(modelKey, ModelParams())
}
