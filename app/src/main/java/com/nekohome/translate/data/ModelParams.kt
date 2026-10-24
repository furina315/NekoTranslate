package com.nekohome.translate.data

import com.nekohome.translate.llama.GpuMode
import com.nekohome.translate.llama.LoadParams
import com.nekohome.translate.llama.SamplingParams

/**
 * User overrides for one imported model, persisted per model file. A `null` field means
 * "use the profile / app default", so profile defaults keep flowing through until the user
 * actually changes something.
 */
data class ModelParams(
    /** Forced profile id; `null` = use the auto-matched profile. */
    val profileId: String? = null,
    /** Prompt template used when the effective profile is Custom; `null` = Custom's default. */
    val customTemplate: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val repeatPenalty: Float? = null,
    val maxTokens: Int? = null,
    val nCtx: Int? = null,
    val nThreads: Int? = null,
    /**
     * Experimental GPU acceleration for this model; `null` = off. Per model because it depends on
     * the model's quantization (the OpenCL fast paths cover Q4_0 / Q4_K / Q6_K / Q8_0) and its
     * memory needs, and like context / threads it needs a reload.
     */
    val gpuMode: GpuMode? = null,
    /**
     * File name (in filesDir/mmproj) of the vision projector associated with this model for image
     * translation; `null` = none. Not a tunable value: kept by [resetToDefaults].
     */
    val mmproj: String? = null,
) {
    /** Effective profile: the override if any, else [matched]; Custom gets the user's template. */
    fun profile(matched: ModelProfile): ModelProfile {
        val base = BuiltInProfiles.byId(profileId) ?: matched
        val template = customTemplate
        return if (base.isCustom && !template.isNullOrBlank()) {
            // The user's template is used for auto-detected source too ({source_lang} becomes empty).
            base.copy(promptTemplate = template, autoSourceTemplate = null)
        } else {
            base
        }
    }

    /** Profile sampling defaults with the user's overrides applied. */
    fun sampling(profile: ModelProfile): SamplingParams {
        val d = profile.sampling
        return d.copy(
            temperature = temperature ?: d.temperature,
            topP = topP ?: d.topP,
            topK = topK ?: d.topK,
            repeatPenalty = repeatPenalty ?: d.repeatPenalty,
            maxTokens = maxTokens ?: d.maxTokens,
        )
    }

    /**
     * The user's thread count, when set, applies to generation and to prefill / image encoding
     * alike; otherwise each uses its own default (see LoadParams). The GPU mode applies only when
     * [gpuUsable] (device supported and its self-test passed); otherwise everything is on the CPU.
     */
    fun loadParams(
        gpuUsable: Boolean = false,
        defaultThreads: Int = LoadParams.defaultThreads(),
        defaultBatchThreads: Int = LoadParams.defaultBatchThreads(),
    ): LoadParams = LoadParams(
        nCtx = nCtx ?: DEFAULT_CONTEXT,
        nThreads = nThreads ?: defaultThreads,
        nThreadsBatch = nThreads ?: defaultBatchThreads,
        gpuMode = effectiveGpuMode(gpuUsable),
    )

    /** The GPU mode the next load will use. */
    fun effectiveGpuMode(gpuUsable: Boolean): GpuMode = if (gpuUsable) gpuMode ?: GpuMode.OFF else GpuMode.OFF

    /** Drops every sampling / load override; keeps the profile choice and custom template. */
    fun resetToDefaults(): ModelParams = copy(
        temperature = null, topP = null, topK = null, repeatPenalty = null, maxTokens = null,
        nCtx = null, nThreads = null, gpuMode = null,
    )

    val hasValueOverrides: Boolean
        get() = this != ModelParams(profileId = profileId, customTemplate = customTemplate, mmproj = mmproj)

    companion object {
        const val DEFAULT_CONTEXT = 4096

        /** Context / thread / GPU changes only take effect after the model is loaded again. */
        fun needsReload(loaded: LoadParams, wanted: LoadParams): Boolean =
            loaded.nCtx != wanted.nCtx || loaded.nThreads != wanted.nThreads ||
                loaded.nThreadsBatch != wanted.nThreadsBatch || loaded.gpuMode != wanted.gpuMode
    }
}

/**
 * Flat string encoding of [ModelParams] for a key-value store. Keys are
 * `"<modelKey>::<field>"`; absent fields are not written.
 */
object ModelParamsCodec {
    private const val SEP = "::"
    private const val PROFILE = "profile"
    private const val TEMPLATE = "template"
    private const val TEMPERATURE = "temperature"
    private const val TOP_P = "top_p"
    private const val TOP_K = "top_k"
    private const val REPEAT_PENALTY = "repeat_penalty"
    private const val MAX_TOKENS = "max_tokens"
    private const val N_CTX = "n_ctx"
    private const val N_THREADS = "n_threads"
    private const val MMPROJ = "mmproj"
    private const val GPU = "gpu"

    fun encode(modelKey: String, p: ModelParams): Map<String, String> = buildMap<String, String> {
        fun add(field: String, v: Any?) { if (v != null) put("$modelKey$SEP$field", v.toString()) }
        add(PROFILE, p.profileId)
        add(TEMPLATE, p.customTemplate)
        add(TEMPERATURE, p.temperature)
        add(TOP_P, p.topP)
        add(TOP_K, p.topK)
        add(REPEAT_PENALTY, p.repeatPenalty)
        add(MAX_TOKENS, p.maxTokens)
        add(N_CTX, p.nCtx)
        add(N_THREADS, p.nThreads)
        add(MMPROJ, p.mmproj)
        add(GPU, p.gpuMode?.key)
    }

    fun belongsTo(key: String, modelKey: String): Boolean = splitKey(key)?.first == modelKey

    fun decodeAll(entries: Map<String, String>): Map<String, ModelParams> =
        entries.entries
            .mapNotNull { (k, v) -> splitKey(k)?.let { (model, field) -> Triple(model, field, v) } }
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, fields) -> decode(fields.toMap()) }

    private fun decode(f: Map<String, String>) = ModelParams(
        profileId = f[PROFILE]?.takeIf { it.isNotBlank() },
        customTemplate = f[TEMPLATE],
        temperature = f[TEMPERATURE]?.toFloatOrNull(),
        topP = f[TOP_P]?.toFloatOrNull(),
        topK = f[TOP_K]?.toIntOrNull(),
        repeatPenalty = f[REPEAT_PENALTY]?.toFloatOrNull(),
        maxTokens = f[MAX_TOKENS]?.toIntOrNull(),
        nCtx = f[N_CTX]?.toIntOrNull(),
        nThreads = f[N_THREADS]?.toIntOrNull(),
        mmproj = f[MMPROJ]?.takeIf { it.isNotBlank() },
        gpuMode = f[GPU]?.let { GpuMode.fromKey(it) }?.takeIf { it != GpuMode.OFF },
    )

    private fun splitKey(key: String): Pair<String, String>? {
        val i = key.lastIndexOf(SEP)
        if (i <= 0) return null
        return key.substring(0, i) to key.substring(i + SEP.length)
    }
}
