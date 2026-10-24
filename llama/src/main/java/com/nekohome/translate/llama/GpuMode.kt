package com.nekohome.translate.llama

/**
 * Experimental GPU acceleration (ggml OpenCL backend, Adreno GPUs, arm64-v8a only).
 *
 * llama.cpp's OpenCL backend has no "offload only big batches" hook (its offload_op is NULL), so
 * there is no way to prefill on the GPU while keeping the weights on the CPU for generation. The
 * hybrid that does exist is per model part: the vision encoder (mmproj, a pure large-batch
 * workload) on the GPU while the text model stays on the CPU, where token-by-token generation is
 * usually faster on phones.
 */
enum class GpuMode(
    /** Stable value written to the per-model settings. */
    val key: String,
    /** Value passed to nativeLoad (llama_jni.cpp LT_GPU_*). */
    val nativeCode: Int,
) {
    /** Everything on the CPU (default). */
    OFF("off", 0),
    /** Vision encoder on the GPU; text prefill and generation on the CPU. */
    ENCODER("encoder", 1),
    /** Vision encoder and the whole text model (prefill and generation) on the GPU. */
    ALL("all", 2);

    val encoderOnGpu: Boolean get() = this != OFF
    val textOnGpu: Boolean get() = this == ALL

    companion object {
        /** Unknown / missing keys fall back to [OFF]. */
        fun fromKey(key: String?): GpuMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Pure helpers around GPU support detection (unit tested). */
object GpuSupport {
    /**
     * Whether it is worth probing for an Adreno GPU at all: Qualcomm SoC (Build.SOC_MANUFACTURER on
     * Android 12+, else Build.HARDWARE) and a 64-bit process. The probe itself is cheap, this just
     * keeps other vendors' OpenCL drivers out of the process.
     */
    fun worthProbing(is64Bit: Boolean, socManufacturer: String?, hardware: String?): Boolean {
        if (!is64Bit) return false
        val soc = socManufacturer?.trim().orEmpty()
        if (soc.isNotEmpty() && !soc.equals("unknown", ignoreCase = true)) {
            return soc.equals("QTI", ignoreCase = true) || soc.contains("qualcomm", ignoreCase = true)
        }
        val hw = hardware.orEmpty()
        return hw.contains("qcom", ignoreCase = true) || hw.contains("qualcomm", ignoreCase = true)
    }

    /** Self-test results are valid for one llama.cpp build on one GPU / driver description. */
    fun selfTestKey(llamaVersion: String, device: String): String = "$llamaVersion|$device"
}
