package com.nekohome.translate.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.R
import com.nekohome.translate.data.IncomingText
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.GpuGuard
import com.nekohome.translate.data.GlossaryEntry
import com.nekohome.translate.data.TranslationPlanner
import com.nekohome.translate.data.HistoryEntry
import com.nekohome.translate.data.HistoryRules
import com.nekohome.translate.data.HistoryStore
import com.nekohome.translate.data.GpuState
import com.nekohome.translate.data.Language
import com.nekohome.translate.data.LanguageRules
import com.nekohome.translate.data.ImportResult
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.data.MmprojNames
import com.nekohome.translate.data.ModelParams
import com.nekohome.translate.data.ModelParamsStore
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.data.ModelRepository
import com.nekohome.translate.data.SettingsRepository
import com.nekohome.translate.engine.GenerationEvent
import com.nekohome.translate.engine.ImageLoader
import com.nekohome.translate.engine.ImagePrep
import com.nekohome.translate.engine.ImageStage
import com.nekohome.translate.engine.LoadedModel
import com.nekohome.translate.engine.MemoryStats
import com.nekohome.translate.engine.ModelSession
import com.nekohome.translate.engine.TranslationEngine
import com.nekohome.translate.engine.throttleLatest
import androidx.compose.runtime.Immutable
import com.nekohome.translate.llama.GpuMode
import com.nekohome.translate.llama.LlamaSession
import com.nekohome.translate.llama.RgbImage
import com.nekohome.translate.llama.StopReason
import com.nekohome.translate.tts.SpeakNotice
import com.nekohome.translate.tts.SpeakProblem
import com.nekohome.translate.tts.SpeakTarget
import com.nekohome.translate.tts.Speaker
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.TtsEngineList
import com.nekohome.translate.tts.TtsLocales
import com.nekohome.translate.tts.TtsVoicePlan
import com.nekohome.translate.tts.queryTtsEngines
import com.nekohome.translate.tts.localeDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import com.nekohome.translate.diag.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Result of the last finished generation (shown subtly under the output). */
@Immutable
data class GenStats(
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val stopReason: StopReason,
)

/** A button shown on a snackbar message. */
enum class MessageAction { OPEN_TTS_SETTINGS }

/**
 * Everything the three tabs show, except the streaming translation text, which lives in
 * [MainViewModel.output] so per-token updates don't invalidate the whole screen.
 */
@Immutable
data class MainUiState(
    /** 长文本分段翻译进度：第 segmentIndex / segmentCount 段（0 = 不在分段）。 */
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
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
    /** Mirrors `output.isNotEmpty()`; only flips at the start / end of a translation. */
    val hasOutput: Boolean = false,
    val isTranslating: Boolean = false,
    val stats: GenStats? = null,
    val message: String? = null,
    /** Optional snackbar action for [message]. */
    val messageAction: MessageAction? = null,
    val systemInfo: String = "",
    /** Vision projectors on disk (file name -> size in bytes). */
    val mmprojFiles: Map<String, Long> = emptyMap(),
    /** Model file name an mmproj is being imported for (progress shown on that card). */
    val mmprojImportFor: String? = null,
    /** Thumbnail of the image to translate; the pixels themselves live in the ViewModel. */
    val imageThumb: ImageBitmap? = null,
    val isPreparingImage: Boolean = false,
    /** Image translation progress before the first token (shown in the output card), or null. */
    val imageStage: ImageStage? = null,
    /** SystemClock.elapsedRealtime() when [imageStage] began. */
    val imageStageSinceMs: Long = 0L,
    /** "GPU" / "CPU" for [imageStage] when GPU acceleration is on, else null. */
    val imageStageBackend: String? = null,
    /** Experimental GPU acceleration: device support and self-test state. */
    val gpu: GpuState = GpuState(),
    /** The engine has no Chinese voice: show the install-an-engine bottom sheet. */
    val chineseVoiceSheet: Boolean = false,
    /** Bumped when text / an image arrives from outside (选中文字菜单、分享); AppRoot jumps to 翻译. */
    val incomingSeq: Int = 0,
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

    val hasImage: Boolean get() = imageThumb != null

    val canTranslate: Boolean
        get() = loaded != null && !isLoading && !isTranslating && !isPreparingImage && (hasImage || input.isNotBlank())

    /** The model's associated vision projector file name, if it is still on disk. */
    fun mmprojFor(model: LocalModel): String? = paramsFor(model).mmproj?.takeIf { it in mmprojFiles }

    /** Image translation is offered only when the loaded model has a vision projector. */
    val imageSupported: Boolean get() = loadedModel?.let { mmprojFor(it) } != null

    /** True when the loaded model's context/threads differ from its saved parameters. */
    fun needsReload(model: LocalModel): Boolean {
        val l = loaded ?: return false
        if (l.path != model.file.absolutePath) return false
        return ModelParams.needsReload(l.params, paramsFor(model).loadParams(gpu.usable))
    }
}

/**
 * The one ViewModel behind all three tabs (activity-scoped). The loaded model itself lives in the
 * process-wide [ModelSession], shared with the quick-translate card (选中文字 / 分享).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ModelRepository(app)
    private val paramsStore = ModelParamsStore(app)
    private val settingsRepository = SettingsRepository(app)
    private val session = ModelSession.get(app)
    private val engine: TranslationEngine = session.engine
    private val speaker = Speaker(app, ::onSpeakProblem, ::onSpeakNotice)

    /** Engine package 朗读 is bound to (null = system default); decides whether the Chinese voice sheet may show. */
    @Volatile
    private var ttsEnginePackage: String? = null
    private val history = HistoryStore.get(app)

    /** Mirrors [com.nekohome.translate.data.AppSettings.historyEnabled]. */
    @Volatile
    private var historyEnabled = true
    private var glossary: List<GlossaryEntry> = emptyList()

    /** Which text box is being read aloud by the system TTS, if any. */
    val speaking: StateFlow<SpeakTarget?> = speaker.speaking
    val highlight: StateFlow<com.nekohome.translate.tts.SpeakHighlight?> = speaker.highlight

    /** Installed TTS engines (null until the first query); re-queried on every resume. */
    private val _ttsEngines = MutableStateFlow<TtsEngineList?>(null)
    val ttsEngines: StateFlow<TtsEngineList?> = _ttsEngines.asStateFlow()

    /** Mirrors [com.nekohome.translate.data.AppSettings.chineseVoiceHintDismissed]. */
    @Volatile
    private var chineseVoiceHintDismissed = false

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    /**
     * Translation text, streamed separately from [state]: only the output text composable reads
     * it, so a new token recomposes that one Text instead of every tab. Updates are throttled to
     * ~30 fps (see [throttleLatest]).
     */
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private fun setOutput(text: String) {
        _output.value = text
        val has = text.isNotEmpty()
        if (_state.value.hasOutput != has) _state.update { it.copy(hasOutput = has) }
    }

    private var translateJob: Job? = null

    /** loadModel() is running (vs. a load started by the quick-translate card). */
    @Volatile
    private var ownLoad = false
    private var imageJob: Job? = null

    /** Pixels of the picked image (thumbnail in [MainUiState.imageThumb]). */
    private var pendingImage: RgbImage? = null
    private val saveJobs = HashMap<String, Job>()
    private val paramsReady = viewModelScope.async { paramsStore.loadAll() }

    /**
     * One DataStore collection (and one glossary parse per change) shared by every settings
     * observer below, instead of eight independent collectors each mapping the whole preferences.
     */
    private val settings = settingsRepository.settings.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    init {
        viewModelScope.launch {
            val saved = paramsReady.await()
            _state.update { it.copy(params = saved + it.params) }
        }
        refreshModels()
        viewModelScope.launch {
            val info = runCatching { engine.systemInfo() }.getOrElse { "native init failed: ${it.message}" }
            _state.update { it.copy(systemInfo = info) }
            val gpu = runCatching { session.gpuState() }.getOrElse { GpuState(device = "") }
            _state.update { it.copy(gpu = gpu) }
        }
        // The loaded model is shared: the quick-translate card may load one (or be loading one).
        viewModelScope.launch {
            combine(session.loaded, session.loadingPath) { l, p -> l to p }.collect { (l, p) ->
                _state.update { st ->
                    var next = st
                    if (st.loaded != l) next = next.copy(loaded = l, loadInfo = l?.let { loadInfoFor(it) } ?: "", stats = null)
                    if (p != null && st.loadingPath != p) next = next.copy(loadingPath = p)
                    // A load started elsewhere (the quick card) finished; our own load clears it itself.
                    if (p == null && st.loadingPath != null && !ownLoad) next = next.copy(loadingPath = null)
                    next
                }
            }
        }
        viewModelScope.launch {
            _state.map { it.targetCode }.distinctUntilChanged().collect { session.lastTargetCode.value = it }
        }
        viewModelScope.launch {
            settings.map { it.chineseVoiceHintDismissed }.distinctUntilChanged().collect {
                chineseVoiceHintDismissed = it
            }
        }
        viewModelScope.launch {
            settings.map { it.historyEnabled }.distinctUntilChanged().collect { historyEnabled = it }
        }
        viewModelScope.launch {
            settings.map { if (it.glossaryEnabled) it.glossary else emptyList() }
                .distinctUntilChanged().collect { glossary = it }
        }
        refreshTtsEngines()
        viewModelScope.launch {
            settings.map { it.ttsRate to it.ttsPitch }.distinctUntilChanged()
                .collect { (r, p) -> speaker.setTuning(r, p) }
        }
        viewModelScope.launch {
            // Bind the chosen engine; an uninstalled choice falls back to the system default, and
            // a changed system default rebinds while following it.
            combine(
                settings.map { it.ttsEngine }.distinctUntilChanged(),
                _ttsEngines.filterNotNull(),
            ) { saved, list ->
                val pkg = TtsEngineChoice.resolve(saved, list.engines)
                pkg to TtsEngineChoice.bindingKey(pkg, list.systemDefault)
            }.distinctUntilChanged().collect { (pkg, key) ->
                ttsEnginePackage = pkg
                speaker.setEngine(pkg, key)
            }
        }
        viewModelScope.launch {
            settings.map { it.defaultTargetCode }.distinctUntilChanged().collect { code ->
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
            val mmproj = listMmproj()
            _state.update { st ->
                val paths = models.map { it.file.absolutePath }
                val chosen = listOf(selectPath, st.selectedPath, st.loaded?.path).firstOrNull { it != null && it in paths }
                    ?: paths.firstOrNull()
                st.copy(models = models, selectedPath = chosen, mmprojFiles = mmproj)
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_state.value.importProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(importProgress = -1f, message = null) }
            try {
                when (val result = repository.import(uri) { p -> _state.update { it.copy(importProgress = p) } }) {
                    is ImportResult.Model -> {
                        val model = result.model
                        _state.update {
                            it.copy(importProgress = null, message = str(R.string.msg_imported, model.fileName, model.profile.displayName))
                        }
                        refreshModels(selectPath = model.file.absolutePath)
                    }
                    is ImportResult.Projector -> {
                        _state.update { it.copy(importProgress = null) }
                        adoptImportedMmproj(result.file)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(AppLog.TAG_MODEL, "model import failed", t)
                _state.update { it.copy(importProgress = null, message = str(R.string.msg_import_failed, t.message ?: "")) }
            }
        }
    }

    fun selectModel(model: LocalModel) = _state.update { it.copy(selectedPath = model.file.absolutePath) }

    fun loadModel(model: LocalModel) {
        val st = _state.value
        if (st.isLoading || st.isTranslating) return
        ownLoad = true
        viewModelScope.launch {
            try {
                loadModelInternal(model)
            } finally {
                ownLoad = false
            }
        }
    }

    private suspend fun loadModelInternal(model: LocalModel) {
        run {
            paramsReady.await()
            val params = _state.value.paramsFor(model).loadParams(_state.value.gpu.usable)
            _state.update {
                it.copy(loadingPath = model.file.absolutePath, selectedPath = model.file.absolutePath, message = null)
            }
            clearImageInternal()
            try {
                val loaded = session.load(model.file, params)
                _state.update {
                    it.copy(
                        loaded = loaded,
                        loadingPath = null,
                        stats = null,
                        loadInfo = loadInfoFor(loaded),
                        message = str(R.string.msg_loaded, model.file.nameWithoutExtension),
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val hint = if (model.architecture == "hunyuan-dense") str(R.string.msg_load_failed_hy_hint) else ""
                _state.update {
                    it.copy(loadingPath = null, loaded = null, loadInfo = "", message = str(R.string.msg_load_failed, t.message ?: "") + hint)
                }
            }
        }
    }

    /** 「加载耗时 · 上下文 · 线程 · RSS · GPU」 line under the loaded model. */
    private fun loadInfoFor(loaded: LoadedModel): String {
        val rss = MemoryStats.residentMb()
        return str(
            R.string.models_load_info,
            String.format(Locale.US, "%.2f", loaded.loadMs / 1000.0),
            loaded.info.nCtx,
            loaded.params.nThreads,
            (rss?.let { mb -> " · RSS $mb MB" } ?: "") + gpuLoadInfo(loaded),
        )
    }

    fun unloadModel() {
        if (_state.value.isTranslating) return
        stopSpeaking()
        viewModelScope.launch {
            session.unload()
            clearImageInternal()
            _state.update { it.copy(loaded = null, loadInfo = "", stats = null) }
        }
    }

    fun deleteModel(model: LocalModel) {
        val st = _state.value
        if (st.isLoading || st.isTranslating) return
        viewModelScope.launch {
            if (_state.value.isLoaded(model)) {
                session.unload()
                clearImageInternal()
                _state.update { it.copy(loaded = null, loadInfo = "", stats = null) }
            }
            val projector = _state.value.paramsFor(model).mmproj
            repository.delete(model)
            session.forgetModel(model.fileName)
            if (projector != null) deleteMmprojIfUnused(projector, exceptModel = model.fileName)
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

    // ------------------------------------------------------------------ vision projector (mmproj)

    private suspend fun listMmproj(): Map<String, Long> = withContext(Dispatchers.IO) {
        repository.mmprojDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .associate { it.name to it.length() }
    }

    private suspend fun refreshMmprojFiles() {
        val files = listMmproj()
        _state.update { it.copy(mmprojFiles = files) }
    }

    /** Imports an mmproj GGUF picked on [model]'s card and associates it with that model. */
    fun attachMmproj(model: LocalModel, uri: Uri) {
        val st = _state.value
        if (st.importProgress != null || st.isTranslating) return
        viewModelScope.launch {
            _state.update { it.copy(importProgress = -1f, mmprojImportFor = model.fileName, message = null) }
            try {
                val file = repository.importMmproj(uri) { p -> _state.update { it.copy(importProgress = p) } }
                _state.update { it.copy(importProgress = null, mmprojImportFor = null) }
                associateMmproj(model, file)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(AppLog.TAG_MODEL, "mmproj import failed", t)
                _state.update {
                    it.copy(importProgress = null, mmprojImportFor = null, message = str(R.string.msg_mmproj_import_failed, t.message ?: ""))
                }
            }
        }
    }

    /** An mmproj came in through the normal model import: pair it with its model by file name. */
    private suspend fun adoptImportedMmproj(file: File) {
        val st = _state.value
        val names = st.models.map { it.fileName }
        val target = MmprojNames.bestModelFor(file.name, names, preferred = st.selectedModel?.fileName)
            ?.let { name -> st.models.firstOrNull { it.fileName == name } }
        if (target == null) {
            file.delete()
            _state.update { it.copy(message = str(R.string.msg_mmproj_no_model, file.name)) }
            return
        }
        associateMmproj(target, file)
    }

    private suspend fun associateMmproj(model: LocalModel, file: File) {
        val previous = _state.value.paramsFor(model).mmproj
        updateParams(model) { it.copy(mmproj = file.name) }
        flushParams(model)
        if (_state.value.isLoaded(model)) engine.releaseMmproj() // loaded lazily on the next image
        if (previous != null && previous != file.name) deleteMmprojIfUnused(previous, exceptModel = null)
        refreshMmprojFiles()
        _state.update {
            it.copy(selectedPath = model.file.absolutePath, message = str(R.string.msg_mmproj_attached, model.file.nameWithoutExtension))
        }
    }

    /** Removes [model]'s vision projector association (and the file, unless another model uses it). */
    fun removeMmproj(model: LocalModel) {
        val st = _state.value
        if (st.isTranslating || st.isPreparingImage) return
        val name = st.paramsFor(model).mmproj ?: return
        viewModelScope.launch {
            updateParams(model) { it.copy(mmproj = null) }
            flushParams(model)
            if (_state.value.isLoaded(model)) {
                engine.releaseMmproj()
                clearImageInternal()
            }
            deleteMmprojIfUnused(name, exceptModel = null)
            refreshMmprojFiles()
            _state.update { it.copy(message = str(R.string.msg_mmproj_removed)) }
        }
    }

    private suspend fun deleteMmprojIfUnused(name: String, exceptModel: String?) {
        val usedElsewhere = _state.value.params.any { (model, p) -> model != exceptModel && p.mmproj == name }
        if (!usedElsewhere) withContext(Dispatchers.IO) { repository.deleteMmproj(name) }
        refreshMmprojFiles()
    }

    /** Writes [model]'s parameters now instead of after the debounce (structural changes). */
    private suspend fun flushParams(model: LocalModel) {
        val key = model.fileName
        saveJobs.remove(key)?.cancel()
        val params = _state.value.paramsFor(model)
        withContext(NonCancellable) { paramsStore.save(key, params) }
    }

    // ------------------------------------------------------------------ image translation

    /** Content URI for the system camera to write a photo to (TakePicture contract). */
    fun cameraCaptureUri(): Uri {
        val app = getApplication<Application>()
        val file = cameraFile().apply {
            parentFile?.mkdirs()
            delete()
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    private fun cameraFile(): File = File(getApplication<Application>().cacheDir, "camera/capture.jpg")

    fun onCameraResult(success: Boolean, uri: Uri?) {
        if (success && uri != null) pickImage(uri, fromCamera = true) else cameraFile().delete()
    }

    /**
     * Decodes [uri] to the size the vision encoder needs (off the main thread), shows its
     * thumbnail and starts translating it.
     */
    fun pickImage(uri: Uri, fromCamera: Boolean = false) {
        val st = _state.value
        val loaded = st.loaded
        val model = st.loadedModel
        if (loaded == null || model == null || !st.imageSupported || st.isTranslating) {
            if (fromCamera) cameraFile().delete()
            return
        }
        stopSpeaking()
        val sampling = st.paramsFor(model).sampling(st.profileFor(model))
        val budget = ImagePrep.imageTokenBudget(loaded.params.nCtx, sampling.maxTokens)
        imageJob?.cancel()
        imageJob = viewModelScope.launch {
            _state.update { it.copy(isPreparingImage = true, message = null) }
            try {
                val prepared = ImageLoader.load(getApplication(), uri, budget.toLong() * ImagePrep.PIXELS_PER_TOKEN)
                pendingImage = prepared.image
                setOutput("")
                _state.update {
                    it.copy(imageThumb = prepared.thumbnail.asImageBitmap(), isPreparingImage = false, stats = null)
                }
                translate()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "image decode failed", t)
                _state.update { it.copy(message = str(R.string.msg_image_failed, t.message ?: t.javaClass.simpleName)) }
            } finally {
                _state.update { it.copy(isPreparingImage = false) }
                if (fromCamera) withContext(NonCancellable + Dispatchers.IO) { cameraFile().delete() }
            }
        }
    }

    fun clearImage() {
        if (_state.value.isTranslating) return
        clearImageInternal()
        setOutput("")
        _state.update { it.copy(stats = null) }
    }

    private fun clearImageInternal() {
        imageJob?.cancel()
        imageJob = null
        pendingImage = null
        if (_state.value.imageThumb != null || _state.value.isPreparingImage) {
            _state.update { it.copy(imageThumb = null, isPreparingImage = false) }
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

    // ------------------------------------------------------------------ experimental GPU

    /**
     * Sets [model]'s GPU mode. Turning it on for the first time on this device (or after an app
     * update) runs the GPU-vs-CPU self-test first; the mode is only saved when it passes.
     */
    fun setGpuMode(model: LocalModel, mode: GpuMode) {
        val st = _state.value
        if (mode == GpuMode.OFF) {
            updateParams(model) { it.copy(gpuMode = null) }
            return
        }
        val gpu = st.gpu
        if (!gpu.supported || gpu.failure != null || gpu.checking) return
        if (gpu.verified) {
            updateParams(model) { it.copy(gpuMode = mode) }
            return
        }
        if (st.isLoading || st.isTranslating) return
        viewModelScope.launch {
            _state.update { it.copy(gpu = it.gpu.copy(checking = true), message = null) }
            val result = try {
                engine.gpuSelfTest()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "OpenCL self-test threw", t)
                gpu.copy(failure = t.message ?: t.javaClass.simpleName)
            }
            session.rememberGpu(result)
            _state.update { it.copy(gpu = result.copy(checking = false)) }
            if (result.usable) {
                updateParams(model) { it.copy(gpuMode = mode) }
                _state.update { it.copy(message = str(R.string.msg_gpu_passed)) }
            } else {
                _state.update { it.copy(message = str(R.string.msg_gpu_failed, gpuFailureText(result.failure))) }
            }
        }
    }

    /** Clears a failed GPU check so the next switch to a GPU mode tests again. */
    fun retryGpuCheck() {
        if (_state.value.gpu.checking) return
        viewModelScope.launch {
            val gpu = runCatching { engine.gpuReset() }.getOrNull() ?: return@launch
            session.rememberGpu(gpu)
            _state.update { it.copy(gpu = gpu) }
        }
    }

    fun gpuFailureText(failure: String?): String =
        if (failure == GpuGuard.CRASH) str(R.string.models_gpu_failed_crash) else failure.orEmpty()

    private fun gpuLoadInfo(loaded: LoadedModel): String = when (loaded.params.gpuMode) {
        GpuMode.OFF -> ""
        GpuMode.ALL -> if (loaded.textOnGpu) str(R.string.models_load_info_gpu, str(R.string.models_gpu_all))
            else str(R.string.models_load_info_gpu_fallback)
        GpuMode.ENCODER -> str(R.string.models_load_info_gpu, str(R.string.models_gpu_encoder))
    }

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

    fun setSource(code: String) {
        stopSpeaking()
        _state.update { it.copy(sourceCode = code) }
    }
    fun setTarget(code: String) {
        stopSpeaking()
        _state.update { it.copy(targetCode = code) }
    }
    fun setInput(text: String) {
        if (speaker.speaking.value == SpeakTarget.SOURCE && text != _state.value.input) stopSpeaking()
        _state.update { it.copy(input = text) }
    }
    fun clearInput() {
        stopSpeaking()
        clearImageInternal()
        setOutput("")
        _state.update { it.copy(input = "", stats = null) }
    }
    /**
     * Text from outside (分享 / the quick-translate card's 「在 App 中打开」): fill the input and
     * translate when a model is ready. A [translated] result from the card is shown as is (with
     * its [targetCode]) instead of translating again.
     */
    fun receiveText(raw: String?, translated: String? = null, targetCode: String? = null) {
        val text = IncomingText.clean(raw) ?: return
        if (_state.value.isTranslating) stop()
        translateJob?.cancel()
        stopSpeaking()
        clearImageInternal()
        setOutput("")
        _state.update {
            it.copy(
                input = text,
                stats = null,
                incomingSeq = it.incomingSeq + 1,
                targetCode = targetCode ?: it.targetCode,
                sourceCode = if (targetCode != null && it.sourceCode == targetCode) Language.AUTO_CODE else it.sourceCode,
            )
        }
        val done = translated?.takeIf { it.isNotBlank() }
        when {
            done != null -> setOutput(done)
            _state.value.canTranslate -> translate()
            _state.value.isLoading -> Unit // the shared model is loading; the user taps 翻译 when it is ready
            else -> _state.update { it.copy(message = str(R.string.msg_incoming_need_model)) }
        }
    }

    /** A shared image: translate it when the model supports images, else say why not. */
    fun receiveImage(uri: Uri) {
        _state.update { it.copy(incomingSeq = it.incomingSeq + 1) }
        val st = _state.value
        if (st.loaded == null) {
            _state.update { it.copy(message = str(R.string.msg_incoming_need_model)) }
        } else if (!st.imageSupported) {
            _state.update { it.copy(message = str(R.string.msg_incoming_no_image)) }
        } else {
            pickImage(uri)
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null, messageAction = null) }

    // ------------------------------------------------------------------ read aloud (system TTS)

    /** Reads the input aloud (again to stop). An auto-detected source is guessed from its script. */
    fun toggleSpeakInput() {
        val st = _state.value
        if (st.hasImage) return
        val code = st.sourceLanguage.takeUnless { it.isAuto }?.code
            ?: TtsLocales.guessCode(st.input)
        speaker.toggle(SpeakTarget.SOURCE, st.input, code)
    }

    /** Reads the translation aloud (again to stop). */
    fun toggleSpeakOutput() {
        val st = _state.value
        if (st.isTranslating) return
        speaker.toggle(SpeakTarget.OUTPUT, _output.value, st.targetLanguage.code)
    }

    fun stopSpeaking() {
        if (speaker.speaking.value != null) speaker.stop()
    }

    /** Re-reads the installed TTS engines (on start and every resume: an engine may have been installed). */
    fun refreshTtsEngines() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { queryTtsEngines(getApplication()) }
            if (list != _ttsEngines.value) _ttsEngines.value = list
        }
    }

    fun dismissChineseVoiceSheet() = _state.update { it.copy(chineseVoiceSheet = false) }

    /** 「不再提示」: later Chinese voice problems fall back to the plain snackbar. */
    fun neverShowChineseVoiceSheet() {
        chineseVoiceHintDismissed = true
        dismissChineseVoiceSheet()
        viewModelScope.launch { settingsRepository.setChineseVoiceHintDismissed(true) }
    }

    private fun onSpeakProblem(problem: SpeakProblem) {
        if (problem is SpeakProblem.MissingVoice && problem.chinese &&
            TtsVoicePlan.mayShowChineseVoiceSheet(explicitEngine = ttsEnginePackage != null, hintDismissed = chineseVoiceHintDismissed)
        ) {
            _state.update { it.copy(chineseVoiceSheet = true) }
            return
        }
        val (text, action) = when (problem) {
            SpeakProblem.NoEngine -> str(R.string.tts_no_engine) to MessageAction.OPEN_TTS_SETTINGS
            is SpeakProblem.EngineInitFailed -> {
                val label = _ttsEngines.value?.find(problem.packageName)?.label ?: problem.packageName
                str(R.string.tts_engine_init_failed, label) to MessageAction.OPEN_TTS_SETTINGS
            }
            SpeakProblem.NoResponse -> str(R.string.tts_no_response) to null
            is SpeakProblem.MissingVoice -> {
                val name = _state.value.activeProfile.findLanguage(problem.code)?.takeUnless { it.isAuto }?.nameZh
                    ?: localeDisplayName(problem.code)
                str(if (problem.missingData) R.string.tts_missing_data else R.string.tts_unsupported, name) to
                    MessageAction.OPEN_TTS_SETTINGS
            }
            is SpeakProblem.Failed -> str(R.string.tts_failed_code, problem.errorCode) to null
        }
        _state.update { it.copy(message = text, messageAction = action) }
    }

    private fun onSpeakNotice(notice: SpeakNotice) {
        val text = when (notice) {
            SpeakNotice.ENGINE_LOADING -> str(R.string.tts_engine_loading)
            SpeakNotice.ENGINE_SLOW -> str(R.string.tts_engine_slow)
            SpeakNotice.ENGINE_GAVE_UP -> str(R.string.tts_engine_gave_up)
        }
        _state.update { it.copy(message = text, messageAction = null) }
    }

    fun swapLanguages() {
        stopSpeaking()
        val st = _state.value
        val out = _output.value
        val (src, tgt) = LanguageRules.swap(st.activeProfile, st.sourceCode, st.targetCode)
        // Carry the previous translation over as the new input, like mainstream translators
        // (not in image mode: the image stays the source).
        val newInput = if (out.isNotBlank() && !st.isTranslating && !st.hasImage) out else st.input
        if (newInput != st.input) setOutput("")
        _state.update { it.copy(sourceCode = src, targetCode = tgt, input = newInput) }
    }

    fun translate() {
        val st = _state.value
        if (!st.canTranslate) return
        val model = st.loadedModel ?: return
        stopSpeaking()
        val profile = st.profileFor(model)
        val sampling = st.paramsFor(model).sampling(profile)
        val image = pendingImage?.takeIf { st.hasImage }
        val mmproj = st.mmprojFor(model)?.let { repository.mmprojFile(it) }
        if (image != null && mmproj == null) return
        val events = if (image != null && mmproj != null) {
            val loaded = st.loaded ?: return
            val prompt = profile.buildImagePrompt(st.sourceLanguage, st.targetLanguage, LlamaSession.MEDIA_MARKER)
            val budget = ImagePrep.imageTokenBudget(loaded.params.nCtx, sampling.maxTokens)
            engine.translateImage(profile, mmproj, budget, prompt, image, sampling)
        } else {
            val nCtx = st.loaded?.params?.nCtx ?: 0
            val plan = TranslationPlanner.plan(
                profile, st.sourceLanguage, st.targetLanguage, st.input, nCtx, sampling.maxTokens, glossary,
            )
            engine.translateSegments(profile, plan, sampling)
        }
        translateJob?.cancel()
        val queued = session.busy.value != null
        translateJob = viewModelScope.launch {
            setOutput("")
            _state.update {
                it.copy(
                    isTranslating = true, stats = null, imageStage = null, messageAction = null,
                    message = if (queued) str(R.string.msg_queued_behind_quick) else null,
                )
            }
            try {
                session.serialized(ModelSession.OWNER_MAIN, events).throttleLatest(OUTPUT_FRAME_MS).collect { ev ->
                    when (ev) {
                        is GenerationEvent.Stage -> _state.update {
                            if (it.imageStage == ev.stage) it
                            else it.copy(imageStage = ev.stage, imageStageSinceMs = ev.atMs, imageStageBackend = ev.backend)
                        }
                        is GenerationEvent.Partial -> {
                            setOutput(ev.text)
                            if (_state.value.segmentCount != ev.segmentCount || _state.value.segmentIndex != ev.segment) {
                                _state.update { it.copy(segmentIndex = ev.segment, segmentCount = ev.segmentCount) }
                            }
                            if (_state.value.imageStage != null) _state.update { it.copy(imageStage = null) }
                        }
                        is GenerationEvent.Done -> {
                            setOutput(ev.text)
                            val histSource = if (image != null) HistoryRules.IMAGE_SOURCE_PLACEHOLDER else st.input
                            if (historyEnabled && HistoryRules.isSavable(ev.stats.stopReason, histSource, ev.text)) {
                                saveHistory(st, histSource, model.file.nameWithoutExtension, ev.text)
                            }
                            _state.update {
                                it.copy(
                                    isTranslating = false,
                                    imageStage = null,
                                    segmentIndex = 0, segmentCount = 0,
                                    message = ev.stats.errorMessage(),
                                    stats = GenStats(ev.stats.generatedTokens, ev.stats.tokensPerSecond, ev.stats.stopReason),
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "translation failed", t)
                _state.update { it.copy(isTranslating = false, message = str(R.string.msg_translate_failed, t.message ?: "")) }
            } finally {
                _state.update { it.copy(isTranslating = false, imageStage = null, segmentIndex = 0, segmentCount = 0) }
            }
        }
    }

    fun stop() {
        if (session.busy.value == ModelSession.OWNER_MAIN) session.cancel(ModelSession.OWNER_MAIN)
        else translateJob?.cancel() // still queued behind the quick-translate card
    }

    // ------------------------------------------------------------------ history

    private fun saveHistory(st: MainUiState, source: String, modelName: String, translation: String) {
        val entry = HistoryEntry(
            id = 0,
            sourceText = source.trim(),
            targetText = translation.trim(),
            sourceCode = st.sourceLanguage.code,
            targetCode = st.targetLanguage.code,
            modelName = modelName,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            runCatching { history.add(entry) }.onFailure { AppLog.w(TAG, "saving history failed", it) }
        }
    }

    /** Puts a history entry back on the translate page: text, translation and language pair. */
    fun restoreFromHistory(entry: HistoryEntry) {
        val st = _state.value
        if (st.isTranslating || st.isPreparingImage) return
        stopSpeaking()
        clearImageInternal()
        setOutput(entry.targetText)
        _state.update {
            it.copy(
                input = if (entry.sourceText == HistoryRules.IMAGE_SOURCE_PLACEHOLDER) "" else entry.sourceText,
                sourceCode = entry.sourceCode,
                targetCode = entry.targetCode,
                stats = null,
                message = if (it.loaded == null) str(R.string.msg_history_restored_no_model) else null,
                messageAction = null,
            )
        }
    }

    override fun onCleared() {
        speaker.shutdown()
        // The model stays loaded in the shared session (the quick-translate card may use it).
        session.cancel(ModelSession.OWNER_MAIN)
        super.onCleared()
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 300L
        const val TAG = "NekoTranslate"

        /** Max UI update rate for streamed output (~30 fps); the final text is always delivered. */
        const val OUTPUT_FRAME_MS = 33L
    }
}
