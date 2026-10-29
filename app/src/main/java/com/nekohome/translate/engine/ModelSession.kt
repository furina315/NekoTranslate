package com.nekohome.translate.engine

import android.content.Context
import com.nekohome.translate.data.GpuState
import com.nekohome.translate.llama.LoadParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The loaded model, process-wide. Owns the one [TranslationEngine] (one llama.cpp session, one
 * native thread) so the main screen and the 选中文字 / 分享 quick-translate card share the same
 * loaded model instead of each Activity's ViewModel holding its own (an empty one for the card:
 * that was the 「模型未加载」 bug). Translations from either screen run one at a time through
 * [serialized]; [busy] tells who is running.
 */
class ModelSession private constructor(context: Context) {

    private val appContext = context.applicationContext
    val engine = TranslationEngine(appContext)

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _loaded = MutableStateFlow<LoadedModel?>(null)
    val loaded: StateFlow<LoadedModel?> = _loaded.asStateFlow()

    private val _loadingPath = MutableStateFlow<String?>(null)
    /** Absolute path of the model being loaded right now (by any screen), or null. */
    val loadingPath: StateFlow<String?> = _loadingPath.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** Owner tag of the translation running on the engine, or null when idle. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Target language last chosen on the main screen / the card (this process only). */
    val lastTargetCode = MutableStateFlow<String?>(null)

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var gpu: GpuState? = null

    /** File name of the model loaded most recently (survives process death), or null. */
    val lastModelName: String? get() = prefs.getString(KEY_LAST_MODEL, null)

    /** GPU state, probed once per process; [rememberGpu] after a self-test / reset. */
    suspend fun gpuState(): GpuState = gpu ?: engine.gpuState().also { gpu = it }

    fun rememberGpu(state: GpuState) {
        gpu = state
    }

    /**
     * Loads [file] (replacing any loaded model). Waits for a running translation to finish first,
     * so a model is never swapped underneath it. Throws on failure (nothing is loaded then).
     */
    suspend fun load(file: File, params: LoadParams): LoadedModel = loadMutex.withLock {
        _loadingPath.value = file.absolutePath
        try {
            inferenceMutex.withLock {
                try {
                    engine.load(file, params).also {
                        _loaded.value = it
                        prefs.edit().putString(KEY_LAST_MODEL, file.name).apply()
                    }
                } catch (t: Throwable) {
                    _loaded.value = engine.loaded
                    throw t
                }
            }
        } finally {
            _loadingPath.value = null
        }
    }

    suspend fun unload() = inferenceMutex.withLock {
        engine.unload()
        _loaded.value = null
    }

    /** The model file was deleted: don't try to auto-load it again. */
    fun forgetModel(fileName: String) {
        if (lastModelName == fileName) prefs.edit().remove(KEY_LAST_MODEL).apply()
    }

    /**
     * Runs [events] (an engine translation) once no other translation is running. [busy] is
     * [owner] while it runs; cancelling the collector while queued just leaves the queue.
     */
    fun serialized(owner: String, events: Flow<GenerationEvent>): Flow<GenerationEvent> = flow {
        inferenceMutex.withLock {
            _busy.value = owner
            try {
                emitAll(events)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Stops the running generation, but only if it is [owner]'s. */
    fun cancel(owner: String) {
        if (_busy.value == owner) engine.cancel()
    }

    companion object {
        const val OWNER_MAIN = "main"
        private const val PREFS = "model_session"
        private const val KEY_LAST_MODEL = "last_model"

        @Volatile
        private var instance: ModelSession? = null

        fun get(context: Context): ModelSession =
            instance ?: synchronized(this) {
                instance ?: ModelSession(context.applicationContext).also { instance = it }
            }
    }
}
