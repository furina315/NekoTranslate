package com.nekohome.translate.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.Language
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.data.ModelRepository
import com.nekohome.translate.engine.GenerationEvent
import com.nekohome.translate.engine.LoadedModel
import com.nekohome.translate.engine.MemoryStats
import com.nekohome.translate.engine.TranslationEngine
import com.nekohome.translate.llama.LoadParams
import com.nekohome.translate.llama.StopReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class TranslateUiState(
    val models: List<LocalModel> = emptyList(),
    val selectedModel: LocalModel? = null,
    val profile: ModelProfile = BuiltInProfiles.CUSTOM,
    val customTemplate: String = BuiltInProfiles.CUSTOM.promptTemplate,
    val loaded: LoadedModel? = null,
    val isLoading: Boolean = false,
    val importProgress: Float? = null, // null = not importing, -1 = indeterminate
    val sourceCode: String = Language.AUTO_CODE,
    val targetCode: String = "en",
    val input: String = "",
    val output: String = "",
    val isTranslating: Boolean = false,
    val loadStats: String = "",
    val genStats: String = "",
    val message: String? = null,
    val systemInfo: String = "",
    val nCtx: Int = 4096,
    val nThreads: Int = LoadParams.defaultThreads(),
) {
    val sourceLanguage: Language get() = profile.findLanguage(sourceCode) ?: Language.AUTO
    val targetLanguage: Language get() = profile.findLanguage(targetCode) ?: profile.languages.first()
    val canTranslate: Boolean get() = loaded != null && !isLoading && !isTranslating && input.isNotBlank()
}

class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ModelRepository(app)
    private val engine = TranslationEngine(app)

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var translateJob: Job? = null

    init {
        refreshModels()
        viewModelScope.launch {
            val info = runCatching { engine.systemInfo() }.getOrElse { "native init failed: ${it.message}" }
            _state.update { it.copy(systemInfo = info) }
        }
    }

    fun refreshModels(select: LocalModel? = null) {
        viewModelScope.launch {
            val models = repository.listModels()
            _state.update { st ->
                val keep = select ?: st.selectedModel?.let { sel -> models.firstOrNull { it.file == sel.file } }
                val chosen = keep ?: models.firstOrNull()
                st.copy(models = models).withModel(chosen)
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importProgress = -1f, message = null) }
            try {
                val model = repository.import(uri) { p -> _state.update { it.copy(importProgress = p) } }
                _state.update { it.copy(importProgress = null, message = "已导入 ${model.fileName}（${model.profile.displayName}）") }
                refreshModels(select = model)
            } catch (t: Throwable) {
                _state.update { it.copy(importProgress = null, message = "导入失败：${t.message}") }
            }
        }
    }

    fun selectModel(model: LocalModel) {
        _state.update { it.withModel(model) }
    }

    fun deleteSelectedModel() {
        val model = _state.value.selectedModel ?: return
        viewModelScope.launch {
            if (_state.value.loaded?.path == model.file.absolutePath) {
                engine.unload()
                _state.update { it.copy(loaded = null, loadStats = "") }
            }
            repository.delete(model)
            _state.update { it.copy(selectedModel = null, message = "已删除 ${model.fileName}") }
            refreshModels()
        }
    }

    fun selectProfile(profile: ModelProfile) {
        _state.update { it.copy(profile = profile).normalizedLanguages() }
    }

    fun setCustomTemplate(t: String) = _state.update { it.copy(customTemplate = t) }
    fun setSource(code: String) = _state.update { it.copy(sourceCode = code) }
    fun setTarget(code: String) = _state.update { it.copy(targetCode = code) }
    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun setThreads(n: Int) = _state.update { it.copy(nThreads = n.coerceIn(1, 8)) }
    fun setContext(n: Int) = _state.update { it.copy(nCtx = n) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun swapLanguages() {
        _state.update { st ->
            if (st.sourceCode == Language.AUTO_CODE) {
                st.copy(sourceCode = st.targetCode, targetCode = defaultTarget(st.profile, st.targetCode))
            } else {
                st.copy(sourceCode = st.targetCode, targetCode = st.sourceCode)
            }
        }
    }

    fun loadSelectedModel() {
        val st = _state.value
        val model = st.selectedModel ?: return
        if (st.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null, loadStats = "加载中…") }
            try {
                val params = LoadParams(nCtx = st.nCtx, nThreads = st.nThreads)
                val loaded = engine.load(model.file, params)
                val rss = MemoryStats.residentMb()
                _state.update {
                    it.copy(
                        loaded = loaded,
                        isLoading = false,
                        loadStats = String.format(
                            Locale.US,
                            "加载 %.2f s · %s · %s · ctx %d · %d 线程%s",
                            loaded.loadMs / 1000.0,
                            loaded.info.architecture,
                            loaded.info.fileType,
                            loaded.info.nCtx,
                            params.nThreads,
                            rss?.let { mb -> " · RSS $mb MB" } ?: "",
                        ),
                    )
                }
            } catch (t: Throwable) {
                val hint = if (model.architecture == "hunyuan-dense") {
                    "（Hy-MT2 的 1.25bit/2bit GGUF 需要非主线 STQ 内核，无法加载，请改用 Q4_K_M 等常规量化）"
                } else ""
                _state.update { it.copy(isLoading = false, loaded = null, loadStats = "", message = "加载失败：${t.message}$hint") }
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            _state.update { it.copy(loaded = null, loadStats = "") }
        }
    }

    fun translate() {
        val st = _state.value
        if (!st.canTranslate) return
        val profile = if (st.profile.isCustom) st.profile.copy(promptTemplate = st.customTemplate) else st.profile
        val userPrompt = profile.buildUserPrompt(st.sourceLanguage, st.targetLanguage, st.input)
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, output = "", genStats = "翻译中…", message = null) }
            try {
                engine.translate(profile, userPrompt).collect { ev ->
                    when (ev) {
                        is GenerationEvent.Partial -> _state.update { it.copy(output = ev.text) }
                        is GenerationEvent.Done -> {
                            val s = ev.stats
                            val rss = MemoryStats.residentMb()
                            val reason = when (s.stopReason) {
                                StopReason.EOG -> ""
                                StopReason.MAX_TOKENS -> " · 达到最大长度"
                                StopReason.CONTEXT_FULL -> " · 上下文已满"
                                StopReason.CANCELLED -> " · 已停止"
                                StopReason.ERROR -> " · 出错"
                            }
                            _state.update {
                                it.copy(
                                    output = ev.text,
                                    isTranslating = false,
                                    message = s.errorMessage(),
                                    genStats = String.format(
                                        Locale.US,
                                        "提示 %d tok / %.0f ms (%.1f t/s) · 生成 %d tok (%.1f t/s)%s%s",
                                        s.promptTokens, s.promptMs, s.promptTokensPerSecond,
                                        s.generatedTokens, s.tokensPerSecond,
                                        rss?.let { mb -> " · RSS $mb MB" } ?: "",
                                        reason,
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isTranslating = false, genStats = "", message = "翻译失败：${t.message}") }
            } finally {
                _state.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun stop() {
        engine.cancel()
    }

    override fun onCleared() {
        engine.cancel()
        super.onCleared()
    }

    private fun TranslateUiState.withModel(model: LocalModel?): TranslateUiState {
        if (model == null) return copy(selectedModel = null)
        if (model.file == selectedModel?.file) return copy(selectedModel = model)
        return copy(selectedModel = model, profile = model.profile).normalizedLanguages()
    }

    /** Keeps the language selection valid for the current profile. */
    private fun TranslateUiState.normalizedLanguages(): TranslateUiState {
        val src = if (sourceCode == Language.AUTO_CODE || profile.languages.any { it.code == sourceCode }) {
            sourceCode
        } else Language.AUTO_CODE
        val tgt = if (profile.languages.any { it.code == targetCode }) targetCode else defaultTarget(profile, targetCode)
        return copy(sourceCode = src, targetCode = tgt)
    }

    private fun defaultTarget(profile: ModelProfile, avoid: String): String {
        val preferred = if (avoid == "zh") "en" else "zh"
        return profile.languages.firstOrNull { it.code == preferred }?.code ?: profile.languages.first().code
    }
}
