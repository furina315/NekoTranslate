package com.nekohome.translate.llama

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/** Process-wide llama.cpp / ggml backend initialisation. Call [init] once before loading a model. */
object LlamaBackend {
    @Volatile
    private var initialized = false

    @Volatile
    private var gpuDevice: String? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        // ggml CPU variants (libggml-cpu-android_armv*.so) are extracted here
        // (android:extractNativeLibs="true" / useLegacyPackaging) and loaded by path.
        LlamaNative.nativeInitBackend(context.applicationInfo.nativeLibraryDir)
        initialized = true
    }

    /** llama_print_system_info() plus registered backends, e.g. "CPU : NEON = 1 | ARM_FMA = 1 | ...". */
    fun systemInfo(): String = LlamaNative.nativeSystemInfo()

    /**
     * Experimental GPU: description of the Adreno GPU usable through OpenCL, or "" when this device
     * has none (or this build has no OpenCL backend). Probed once; requires [init]. Engine thread.
     */
    @Synchronized
    fun gpuProbe(context: Context): String {
        gpuDevice?.let { return it }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        val desc = if (GpuSupport.worthProbing(Process.is64Bit(), soc, Build.HARDWARE)) {
            val cache = File(context.cacheDir, "cl-kernels").apply { mkdirs() }
            runCatching { LlamaNative.nativeGpuProbe(cache.absolutePath) }.getOrDefault("")
        } else {
            ""
        }
        gpuDevice = desc
        return desc
    }

    /** GPU vs CPU quantized matmul check; "" = passed, else the reason. Engine thread, after [gpuProbe]. */
    fun gpuSelfTest(): String = LlamaNative.nativeGpuSelfTest()
}
