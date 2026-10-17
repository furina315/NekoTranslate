package com.nekohome.translate.llama

import android.content.Context

/** Process-wide llama.cpp / ggml backend initialisation. Call [init] once before loading a model. */
object LlamaBackend {
    @Volatile
    private var initialized = false

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
}
