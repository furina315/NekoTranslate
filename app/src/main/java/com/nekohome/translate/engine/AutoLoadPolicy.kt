package com.nekohome.translate.engine

/** What the quick-translate card does about the model before translating. */
sealed interface AutoLoadDecision {
    /** A model is already loaded in this process (e.g. by the main screen): use it. */
    data object UseLoaded : AutoLoadDecision

    /** Another screen is loading a model right now: wait for it instead of loading a second one. */
    data object WaitForLoading : AutoLoadDecision

    /** Nothing loaded (cold start after the process was killed, or never loaded): load [fileName]. */
    data class Load(val fileName: String) : AutoLoadDecision

    /** No model to load: tell the user and offer 「在 App 中打开」. */
    data object NoModel : AutoLoadDecision
}

/** Pure decision (unit-tested): which model to use / load for a translation outside the main screen. */
object AutoLoadPolicy {

    /**
     * @param loadedPath   absolute path of the model loaded in this process, if any
     * @param loadingPath  absolute path of the model being loaded right now, if any
     * @param lastModel    file name of the model loaded most recently (persisted), if any
     * @param available    file names of the models on disk
     */
    fun decide(loadedPath: String?, loadingPath: String?, lastModel: String?, available: List<String>): AutoLoadDecision = when {
        loadedPath != null -> AutoLoadDecision.UseLoaded
        loadingPath != null -> AutoLoadDecision.WaitForLoading
        lastModel != null && lastModel in available -> AutoLoadDecision.Load(lastModel)
        // Never loaded (or the last one was deleted) but only one model on disk: no choice to make.
        available.size == 1 -> AutoLoadDecision.Load(available.single())
        else -> AutoLoadDecision.NoModel
    }
}
