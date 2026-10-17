package com.nekohome.translate.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.R
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.Language
import com.nekohome.translate.data.LanguageRules
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.data.ModelParams
import com.nekohome.translate.data.ModelParamsStore
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.data.ModelRepository
import com.nekohome.translate.data.SettingsRepository
import com.nekohome.translate.engine.GenerationEvent
import com.nekohome.translate.engine.LoadedModel
import com.nekohome.translate.engine.MemoryStats
import com.nekohome.translate.engine.TranslationEngine
import com.nekohome.translate.llama.StopReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Result of the last finished generation (shown subtly under the output). */
data class GenStats(
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val stopReason: StopReason,
)

data class MainUiState(
    val models: List<LocalModel> = emptyList(),
    /** Model whose parameters the 模型 tab is editing. */
    val selectedPath: String? = null,
    /** Per-model overrides keyed by file name. */
    val params: Map<String, ModelParams> = emptyMap(),
    val loaded: LoadedModel? = null,
    /** Path of the model currently being loaded, if any. */
    val loadingPath: String? = null,
    val loadInfo: String = "",
    val importProgress: Float? = null, // null = not importing, -1 = indeterminate
    val sourceCode: String = Language.AUTO_CODE,
    val targetCode: String = "en",
    val input: String = "",
    val output: String = "",
    val isTranslating: Boolean = false,
    val stats: GenStats? = null,
    val message: String? = null,
    val systemInfo: String = "",
) {
    val selectedModel: LocalModel? get() = models.firstOrNull { it.file.absolutePath == selectedPath }
    val loadedModel: LocalModel? get() = loaded?.let { l -> models.firstOrNull { it.file.absolutePath == l.path } }
    val isLoading: Boolean get() = loadingPath != null

    fun paramsFor(model: LocalModel): ModelParams = params[model.fileName] ?: ModelParams()
    fun profileFor(model: LocalModel): ModelProfile = paramsFor(model).profile(model.profile)
    fun isLoaded(model: LocalModel): Boolean = loaded?.path == model.file.absolutePath

    /** Profile used for translation: the loaded model's effective profile. */
    val activeProfile: ModelProfile get() = loadedModel?.let { profileFor(it) } ?: BuiltInProfiles.CUSTOM
    val sourceLanguage: Language get() = LanguageRules.resolveSource(activeProfile, sourceCode)
    val targetLanguage: Language get() = LanguageRules.resolveTarget(activeProfile, targetCode)

    val canTranslate: Boolean get() = loaded != null && !isLoading && !isTranslating && input.isNotBlank()

    /** True when the loaded model's context/threads differ from its saved parameters. */
    fun needsReload(model: LocalModel): Boolean {
        val l = loaded ?: return false
        if (l.path != model.file.absolutePath) return false
        return ModelParams.needsReload(l.params, paramsFor(model).loadParams())
    }
}

/**
 * The one ViewModel behind all three tabs (activity-scoped), so the [TranslationEngine] and the
 * loaded model survive tab switches.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ModelRepository(app)
    private val paramsStore = ModelParamsStore(app)
    private val settingsRepository = SettingsRepository(app)
    private val engine = TranslationEngine(app)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var translateJob: Job? = null
    private val saveJobs = HashMap<String, Job>()
    private val paramsReady = viewModelScope.async { paramsStore.loadAll() }

    init {
        viewModelScope.launch {
            val saved = paramsReady.await()
            _state.update { it.copy(params = saved + it.params) }
        }
        refreshModels()
        viewModelScope.launch {
            val info = runCatching { engine.systemInfo() }.getOrElse { "native init failed: ${it.message}" }
            _state.update { it.copy(systemInfo = info) }
        }
        viewModelScope.launch {
            settingsRepository.settings.map { it.defaultTargetCode }.distinctUntilChanged().collect { code ->
                _state.update { st ->
                    st.copy(
                        targetCode = code,
                        sourceCode = if (st.sourceCode == code) Language.AUTO_CODE else st.sourceCode,
                    )
                }
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // ------------------------------------------------------------------ models

    fun refreshModels(selectPath: String? = null) {
        viewModelScope.launch {
            val models = repository.listModels()
            _state.update { st ->
                val paths = models.map { it.file.absolutePath }
                val chosen = listOf(selectPath, st.selectedPath, st.loaded?.path).firstOrNull { it != null && it in paths }
                    ?: paths.firstOrNull()
                st.copy(models = models, selectedPath = chosen)
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_state.value.importProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(importProgress = -1f, message = null) }
            try {
                val model = repository.import(uri) { p -> _state.update { it.copy(importProgress = p) } }
                _state.update {
                    it.copy(importProgress = null, message = str(R.string.msg_imported, model.fileName, model.profile.displayName))
                }
                refreshModels(selectPath = model.file.absolutePath)
            } catch (t: Throwable) {
                _state.update { it.copy(importProgress = null, message = str(R.string.msg_import_failed, t.message ?: "")) }
            }
        }
    }

    fun selectModel(model: LocalModel) = _state.update { it.copy(selectedPath = model.file.absolutePath) }

    fun loadModel(model: LocalModel) {
        val st = _state.value
        if (st.isLoading || st.isTranslating) return
        viewModelScope.launch {
            paramsReady.await()
            val params = _state.value.paramsFor(model).loadParams()
            _state.update {
                it.copy(loadingPath = model.file.absolutePath, selectedPath = model.file.absolutePath, message = null)
            }
            try {
                val loaded = engine.load(model.file, params)
                val rss = MemoryStats.residentMb()
                _state.update {
                    it.copy(
                        loaded = loaded,
                        loadingPath = null,
                        stats = null,
                        loadInfo = str(
                            R.string.models_load_info,
                            String.format(Locale.US, "%.2f", loaded.loadMs / 1000.0),
                            loaded.info.nCtx,
                            params.nThreads,
                            rss?.let { mb -> " · RSS $mb MB" } ?: "",
                        ),
                        message = str(R.string.msg_loaded, model.file.nameWithoutExtension),
                    )
                }
            } catch (t: Throwable) {
                val hint = if (model.architecture == "hunyuan-dense") str(R.string.msg_load_failed_hy_hint) else ""
                _state.update {
                    it.copy(loadingPath = null, loaded = null, loadInfo = "", message = str(R.string.msg_load_failed, t.message ?: "") + hint)
                }
            }
        }
    }

    fun unloadModel() {
        if (_state.value.isTranslating) return
        viewModelScope.launch {
            engine.unload()
            _state.update { it.copy(loaded = null, loadInfo = "", stats = null) }
        }
    }

    fun deleteModel(model: LocalModel) {
        val st = _state.value
        if (st.isLoading || st.isTranslating) return
        viewModelScope.launch {
            if (_state.value.isLoaded(model)) {
                engine.unload()
                _state.update { it.copy(loaded = null, loadInfo = "", stats = null) }
            }
            repository.delete(model)
            saveJobs.remove(model.fileName)?.cancel()
            paramsStore.remove(model.fileName)
            _state.update {
                it.copy(
                    params = it.params - model.fileName,
                    selectedPath = it.selectedPath.takeUnless { p -> p == model.file.absolutePath },
                    message = str(R.string.msg_deleted, model.fileName),
                )
            }
            refreshModels()
        }
    }

    // ------------------------------------------------------------------ parameters

    fun updateParams(model: LocalModel, transform: (ModelParams) -> ModelParams) {
        val key = model.fileName
        val updated = transform(_state.value.paramsFor(model))
        _state.update { it.copy(params = it.params + (key to updated)) }
        persist(key, updated)
    }

    fun setProfileOverride(model: LocalModel, profileId: String?) = updateParams(model) { it.copy(profileId = profileId) }
    fun setCustomTemplate(model: LocalModel, template: String?) = updateParams(model) { it.copy(customTemplate = template) }
    fun setTemperature(model: LocalModel, v: Float) = updateParams(model) { it.copy(temperature = round2(v)) }
    fun setTopP(model: LocalModel, v: Float) = updateParams(model) { it.copy(topP = round2(v)) }
    fun setTopK(model: LocalModel, v: Int) = updateParams(model) { it.copy(topK = v) }
    fun setRepeatPenalty(model: LocalModel, v: Float) = updateParams(model) { it.copy(repeatPenalty = round2(v)) }
    fun setMaxTokens(model: LocalModel, v: Int) = updateParams(model) { it.copy(maxTokens = v) }
    fun setContext(model: LocalModel, v: Int) = updateParams(model) { it.copy(nCtx = v) }
    fun setThreads(model: LocalModel, v: Int) = updateParams(model) { it.copy(nThreads = v) }
    fun resetParams(model: LocalModel) = updateParams(model) { it.resetToDefaults() }

    /** Debounced write: slider drags update memory every frame but hit disk once they settle. */
    private fun persist(key: String, params: ModelParams) {
        saveJobs[key]?.cancel()
        saveJobs[key] = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            withContext(NonCancellable) { paramsStore.save(key, params) }
        }
    }

    private fun round2(v: Float): Float = (v * 100f).roundToInt() / 100f

    // ------------------------------------------------------------------ translation

    fun setSource(code: String) = _state.update { it.copy(sourceCode = code) }
    fun setTarget(code: String) = _state.update { it.copy(targetCode = code) }
    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun clearInput() = _state.update { it.copy(input = "", output = "", stats = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun swapLanguages() {
        _state.update { st ->
            val (src, tgt) = LanguageRules.swap(st.activeProfile, st.sourceCode, st.targetCode)
            // Carry the previous translation over as the new input, like mainstream translators.
            val newInput = if (st.output.isNotBlank() && !st.isTranslating) st.output else st.input
            st.copy(sourceCode = src, targetCode = tgt, input = newInput, output = if (newInput != st.input) "" else st.output)
        }
    }

    fun translate() {
        val st = _state.value
        if (!st.canTranslate) return
        val model = st.loadedModel ?: return
        val profile = st.profileFor(model)
        val sampling = st.paramsFor(model).sampling(profile)
        val userPrompt = profile.buildUserPrompt(st.sourceLanguage, st.targetLanguage, st.input)
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, output = "", stats = null, message = null) }
            try {
                engine.translate(profile, userPrompt, sampling).collect { ev ->
                    when (ev) {
                        is GenerationEvent.Partial -> _state.update { it.copy(output = ev.text) }
                        is GenerationEvent.Done -> _state.update {
                            it.copy(
                                output = ev.text,
                                isTranslating = false,
                                message = ev.stats.errorMessage(),
                                stats = GenStats(ev.stats.generatedTokens, ev.stats.tokensPerSecond, ev.stats.stopReason),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(isTranslating = false, message = str(R.string.msg_translate_failed, t.message ?: "")) }
            } finally {
                _state.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun stop() = engine.cancel()

    override fun onCleared() {
        engine.cancel()
        super.onCleared()
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 300L
    }
}
