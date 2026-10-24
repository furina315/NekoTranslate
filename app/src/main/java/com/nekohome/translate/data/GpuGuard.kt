package com.nekohome.translate.data

import android.content.Context
import com.nekohome.translate.llama.GpuSupport

/** Minimal synchronous key-value store (SharedPreferences in the app, a map in tests). */
interface GpuKeyValue {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
    /** Must be durable when it returns: it is called right before native GPU code that may crash. */
    fun put(key: String, value: Any?)
}

/** Device-wide state of the experimental GPU acceleration. */
data class GpuState(
    /** GPU description; `null` = not probed yet, "" = this device has no usable GPU. */
    val device: String? = null,
    /** Self-test passed for this llama.cpp build and GPU. */
    val verified: Boolean = false,
    /** Why the GPU is disabled (self-test failure or a crash during GPU init), or null. */
    val failure: String? = null,
    /** A self-test is running. */
    val checking: Boolean = false,
) {
    val supported: Boolean get() = !device.isNullOrEmpty()
    /** Per-model GPU modes take effect only when this is true; otherwise everything runs on the CPU. */
    val usable: Boolean get() = supported && verified && failure == null
}

/**
 * Persistence and crash guard for the GPU path. The self-test result is remembered per llama.cpp
 * build + GPU description ([GpuSupport.selfTestKey]). Native GPU initialisation (self-test, model
 * or vision encoder load on the GPU) is bracketed by [begin] / [end]; a "pending" mark that is
 * still set on the next start means the app died inside GPU code, and the GPU is then disabled
 * until the user asks for a new check.
 */
class GpuGuard(private val kv: GpuKeyValue, private val llamaVersion: String) {

    /** Result for [device] (consumes a leftover crash mark first). */
    fun state(device: String): GpuState {
        if (device.isEmpty()) return GpuState(device = "")
        val key = GpuSupport.selfTestKey(llamaVersion, device)
        if (kv.getBoolean(PENDING)) {
            kv.put(PENDING, false)
            kv.put(RESULT_KEY, key)
            kv.put(RESULT, FAIL_PREFIX + CRASH)
        }
        if (kv.getString(RESULT_KEY) != key) return GpuState(device = device)
        val result = kv.getString(RESULT).orEmpty()
        return when {
            result == PASS -> GpuState(device = device, verified = true)
            result.startsWith(FAIL_PREFIX) -> GpuState(device = device, failure = result.removePrefix(FAIL_PREFIX))
            else -> GpuState(device = device)
        }
    }

    fun recordSelfTest(device: String, error: String) {
        kv.put(RESULT_KEY, GpuSupport.selfTestKey(llamaVersion, device))
        kv.put(RESULT, if (error.isEmpty()) PASS else FAIL_PREFIX + error)
    }

    /** Forget a failure so the user can run the check again. */
    fun clear() {
        kv.put(RESULT_KEY, null)
        kv.put(RESULT, null)
    }

    fun begin() = kv.put(PENDING, true)
    fun end() = kv.put(PENDING, false)

    /** Runs [block] (native GPU initialisation) with the crash mark set. */
    inline fun <T> guarded(active: Boolean, block: () -> T): T {
        if (!active) return block()
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    companion object {
        const val CRASH = "crash"
        private const val PENDING = "gpu_init_pending"
        private const val RESULT_KEY = "gpu_selftest_key"
        private const val RESULT = "gpu_selftest_result"
        private const val PASS = "pass"
        private const val FAIL_PREFIX = "fail:"

        fun create(context: Context, llamaVersion: String): GpuGuard {
            val prefs = context.applicationContext.getSharedPreferences("gpu", Context.MODE_PRIVATE)
            return GpuGuard(object : GpuKeyValue {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)
                override fun put(key: String, value: Any?) {
                    val e = prefs.edit()
                    when (value) {
                        null -> e.remove(key)
                        is Boolean -> e.putBoolean(key, value)
                        else -> e.putString(key, value.toString())
                    }
                    e.commit() // synchronous on purpose (see GpuKeyValue.put)
                }
            }, llamaVersion)
        }
    }
}
