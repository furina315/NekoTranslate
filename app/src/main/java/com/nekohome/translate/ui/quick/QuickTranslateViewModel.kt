package com.nekohome.translate.ui.quick

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekohome.translate.R
import com.nekohome.translate.data.HistoryEntry
import com.nekohome.translate.data.HistoryRules
import com.nekohome.translate.data.HistoryStore
import com.nekohome.translate.data.Language
import com.nekohome.translate.data.LanguageRules
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.data.ModelParams
import com.nekohome.translate.data.ModelParamsStore
import com.nekohome.translate.data.ModelProfile
import com.nekohome.translate.data.ModelRepository
import com.nekohome.translate.data.QuickRequest
import com.nekohome.translate.data.SettingsRepository
import com.nekohome.translate.data.TranslationPlanner
import com.nekohome.translate.engine.AutoLoadDecision
import com.nekohome.translate.engine.AutoLoadPolicy
import com.nekohome.translate.engine.GenerationEvent
import com.nekohome.translate.engine.ModelSession
import com.nekohome.translate.engine.throttleLatest
import com.nekohome.translate.tts.SpeakNotice
import com.nekohome.translate.tts.SpeakProblem
import com.nekohome.translate.tts.SpeakTarget
import com.nekohome.translate.tts.Speaker
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.localeDisplayName
import com.nekohome.translate.tts.queryTtsEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.nekohome.translate.diag.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Where the quick-translate card is. */
enum class QuickPhase { PREPARING, WAITING_FOR_LOAD, LOADING_MODEL, QUEUED, TRANSLATING, DONE, NO_MODEL, FAILED }

@Immutable
data class QuickUiState(
    val source: String = "",
    /** Length of the text that arrived (> source.length when it was over IncomingText.MAX_CHARS). */
    val originalLength: Int = 0,
    val replaceable: Boolean = false,
    /** Long-text progress: segment [segment] of [segmentCount] (0 = not segmented / not running). */
    val segment: Int = 0,
    val segmentCount: Int = 0,
    val phase: QuickPhase = QuickPhase.PREPARING,
    /** Model being loaded / used (display name). */
    val modelName: String? = null,
    /** Seconds spent loading the model (shown while [phase] is a loading phase). */
    val loadSeconds: Int = 0,
    val targetCode: String = "en",
    /** Targets offered by the model's profile (no 「自动检测」). */
    val targets: List<Language> = emptyList(),
    /** Error / hint line shown in the card. */
    val message: String? = null,
) {
    val target: Language? get() = targets.firstOrNull { it.code == targetCode }
    val truncated: Boolean get() = originalLength > source.length
    val isBusy: Boolean
        get() = phase == QuickPhase.PREPARING || phase == QuickPhase.WAITING_FOR_LOAD ||
            phase == QuickPhase.LOADING_MODEL || phase == QuickPhase.QUEUED || phase == QuickPhase.TRANSLATING
}

/**
 * Behind [QuickTranslateActivity]: makes sure the shared model ([ModelSession]) is loaded —
 * reusing the one the main screen loaded, or auto-loading the last used model after a cold start —
 * then streams the translation. Finished translations go to 翻译历史 (when enabled).
 */
class QuickTranslateViewModel(app: Application) : AndroidViewModel(app) {

    private val session = ModelSession.get(app)
    private val repository = ModelRepository(app)
    private val paramsStore = ModelParamsStore(app)
    private val settingsRepository = SettingsRepository(app)
    private val history = HistoryStore.get(app)
    private val owner = "quick-" + SEQ.incrementAndGet()

    private val _state = MutableStateFlow(QuickUiState())
    val state: StateFlow<QuickUiState> = _state.asStateFlow()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    /** Speaker problems / notices shown in the card's hint line. */
    private val speaker = Speaker(app, ::onSpeakProblem, ::onSpeakNotice)
    val speaking: StateFlow<SpeakTarget?> = speaker.speaking

    private var started = false
    private var job: Job? = null
    private var model: LocalModel? = null
    private var params: ModelParams = ModelParams()
    private var ttsEnginePackage: String? = null

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun start(request: QuickRequest) {
        if (started) return
        started = true
        _state.update { it.copy(source = request.text, originalLength = request.originalLength, replaceable = request.replaceable) }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { queryTtsEngines(getApplication()) }
            val st = settingsRepository.settings.first()
            speaker.setTuning(st.ttsRate, st.ttsPitch)
            val saved = st.ttsEngine
            val pkg = TtsEngineChoice.resolve(saved, list.engines)
            ttsEnginePackage = pkg
            speaker.setEngine(pkg, TtsEngineChoice.bindingKey(pkg, list.systemDefault))
        }
        run()
    }

    /** (Re)runs model check + translation, e.g. after 「重试」 or a target change. */
    fun run() {
        job?.cancel()
        session.cancel(owner)
        speaker.stop()
        job = viewModelScope.launch {
            _output.value = ""
            _state.update { it.copy(phase = QuickPhase.PREPARING, message = null, segment = 0, segmentCount = 0) }
            try {
                if (!ensureModel()) return@launch
                translate()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "quick translation failed", t)
                _state.update { it.copy(phase = QuickPhase.FAILED, message = str(R.string.quick_translate_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    /** True once a model is loaded in the shared session (loading it when needed). */
    private suspend fun ensureModel(): Boolean {
        val models = repository.listModels()
        // Read once: used for the auto-load and for the loaded model's sampling parameters.
        val allParams = paramsStore.loadAll()
        val decision = AutoLoadPolicy.decide(
            loadedPath = session.loaded.value?.path,
            loadingPath = session.loadingPath.value,
            lastModel = session.lastModelName,
            available = models.map { it.fileName },
        )
        when (decision) {
            AutoLoadDecision.UseLoaded -> Unit
            AutoLoadDecision.WaitForLoading -> {
                val name = models.firstOrNull { it.file.absolutePath == session.loadingPath.value }?.file?.nameWithoutExtension
                _state.update { it.copy(phase = QuickPhase.WAITING_FOR_LOAD, modelName = name, loadSeconds = 0) }
                withTicker { session.loadingPath.first { it == null } }
            }
            is AutoLoadDecision.Load -> {
                val target = models.first { it.fileName == decision.fileName }
                _state.update { it.copy(phase = QuickPhase.LOADING_MODEL, modelName = target.file.nameWithoutExtension, loadSeconds = 0) }
                val p = allParams[target.fileName] ?: ModelParams()
                val gpuUsable = runCatching { session.gpuState().usable }.getOrDefault(false)
                try {
                    withTicker { session.load(target.file, p.loadParams(gpuUsable)) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.e(TAG, "quick card: auto-load of ${target.fileName} failed", t)
                    _state.update { it.copy(phase = QuickPhase.FAILED, message = str(R.string.quick_load_failed, t.message ?: "")) }
                    return false
                }
            }
            AutoLoadDecision.NoModel -> {
                _state.update { it.copy(phase = QuickPhase.NO_MODEL, message = str(R.string.quick_no_model)) }
                return false
            }
        }
        val loaded = session.loaded.value
        // The model list read above still holds (a load does not add files); no second header scan.
        val m = loaded?.let { l -> models.firstOrNull { it.file.absolutePath == l.path } }
        if (m == null) {
            _state.update { it.copy(phase = QuickPhase.NO_MODEL, message = str(R.string.quick_no_model)) }
            return false
        }
        model = m
        params = allParams[m.fileName] ?: ModelParams()
        val profile = params.profile(m.profile)
        val settings = settingsRepository.settings.first()
        val wanted = _state.value.targetCode.takeIf { _state.value.targets.isNotEmpty() }
            ?: session.lastTargetCode.value ?: settings.defaultTargetCode
        val target = LanguageRules.resolveTarget(profile, wanted)
        _state.update {
            it.copy(
                modelName = m.file.nameWithoutExtension,
                targets = profile.languages.filterNot { l -> l.isAuto },
                targetCode = target.code,
            )
        }
        return true
    }

    /** Runs [block] while counting [QuickUiState.loadSeconds] up once a second. */
    private suspend fun <T> withTicker(block: suspend () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        val ticker = viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(loadSeconds = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()) }
            }
        }
        try {
            return block()
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun translate() {
        val m = model ?: return
        val profile: ModelProfile = params.profile(m.profile)
        val sampling = params.sampling(profile)
        val st = _state.value
        val source = LanguageRules.resolveSource(profile, Language.AUTO_CODE)
        val target = LanguageRules.resolveTarget(profile, st.targetCode)
        val settingsNow = settingsRepository.settings.first()
        val glossary = if (settingsNow.glossaryEnabled) settingsNow.glossary else emptyList()
        val plan = TranslationPlanner.plan(
            profile, source, target, st.source,
            session.engine.loaded?.params?.nCtx ?: 0, sampling.maxTokens, glossary,
        )
        val events = session.engine.translateSegments(profile, plan, sampling)
        val queued = session.busy.value != null
        _state.update { it.copy(phase = if (queued) QuickPhase.QUEUED else QuickPhase.TRANSLATING) }
        session.serialized(owner, events).throttleLatest(OUTPUT_FRAME_MS).collect { ev ->
            when (ev) {
                is GenerationEvent.Stage -> Unit
                is GenerationEvent.Partial -> {
                    val cur = _state.value
                    if (cur.phase != QuickPhase.TRANSLATING || cur.segment != ev.segment || cur.segmentCount != ev.segmentCount) {
                        _state.update { it.copy(phase = QuickPhase.TRANSLATING, segment = ev.segment, segmentCount = ev.segmentCount) }
                    }
                    _output.value = ev.text
                }
                is GenerationEvent.Done -> {
                    _output.value = ev.text
                    val historyOn = settingsRepository.settings.first().historyEnabled
                    if (historyOn && HistoryRules.isSavable(ev.stats.stopReason, st.source, ev.text)) {
                        val entry = HistoryEntry(
                            id = 0,
                            sourceText = st.source.trim(),
                            targetText = ev.text.trim(),
                            sourceCode = source.code,
                            targetCode = target.code,
                            modelName = m.file.nameWithoutExtension,
                            createdAt = System.currentTimeMillis(),
                        )
                        viewModelScope.launch {
                            runCatching { history.add(entry) }.onFailure { AppLog.w(TAG, "saving history failed", it) }
                        }
                    }
                    _state.update { it.copy(phase = QuickPhase.DONE, message = ev.stats.errorMessage(), segment = 0, segmentCount = 0) }
                }
            }
        }
    }

    fun setTarget(code: String) {
        if (code == _state.value.targetCode) return
        session.lastTargetCode.value = code
        _state.update { it.copy(targetCode = code) }
        if (model != null) run()
    }

    fun toggleSpeak() {
        val out = _output.value
        if (out.isBlank() || _state.value.phase != QuickPhase.DONE) return
        speaker.toggle(SpeakTarget.OUTPUT, out, _state.value.targetCode)
    }

    /** The card is closing: stop our generation (the model stays loaded for next time). */
    fun dismiss() {
        job?.cancel()
        session.cancel(owner)
        speaker.stop()
    }

    private fun onSpeakProblem(problem: SpeakProblem) {
        val text = when (problem) {
            SpeakProblem.NoEngine -> str(R.string.tts_no_engine)
            is SpeakProblem.EngineInitFailed -> str(R.string.tts_engine_init_failed, problem.packageName)
            is SpeakProblem.MissingVoice -> {
                val name = _state.value.targets.firstOrNull { it.code == problem.code }?.nameZh ?: localeDisplayName(problem.code)
                str(if (problem.missingData) R.string.tts_missing_data else R.string.tts_unsupported, name)
            }
            is SpeakProblem.Failed -> str(R.string.tts_failed_code, problem.errorCode)
            SpeakProblem.NoResponse -> str(R.string.tts_no_response)
        }
        _state.update { it.copy(message = text) }
    }

    private fun onSpeakNotice(notice: SpeakNotice) {
        val text = when (notice) {
            SpeakNotice.ENGINE_LOADING -> str(R.string.tts_engine_loading)
            SpeakNotice.ENGINE_SLOW -> str(R.string.tts_engine_slow)
            SpeakNotice.ENGINE_GAVE_UP -> str(R.string.tts_engine_gave_up)
        }
        _state.update { it.copy(message = text) }
    }

    override fun onCleared() {
        dismiss()
        speaker.shutdown()
        super.onCleared()
    }

    private companion object {
        const val OUTPUT_FRAME_MS = 33L
        const val TAG = "NekoQuick"
        val SEQ = AtomicInteger()
    }
}
