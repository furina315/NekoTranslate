package com.nekohome.translate.tts

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Immutable
import java.util.Locale

/** An installed TTS engine (what [TextToSpeech.getEngines] reports), minus the icon. */
@Immutable
data class TtsEngineInfo(val packageName: String, val label: String, val system: Boolean = false)

/** Installed engines plus the system-wide default engine package (null if unknown). */
@Immutable
data class TtsEngineList(val engines: List<TtsEngineInfo>, val systemDefault: String?) {
    fun find(packageName: String?): TtsEngineInfo? = engines.firstOrNull { it.packageName == packageName }
}

/** Pure decisions around engine choice, Chinese voices and the device ABI (unit-tested on the JVM). */
object TtsEngineChoice {

    /** Saved value meaning 「跟随系统默认」. */
    const val SYSTEM_DEFAULT = ""

    /**
     * Engine package to bind for the [saved] choice: null = the system default engine. A saved
     * engine that is no longer installed falls back to the default. While the installed list is
     * still unknown ([installed] == null) the saved choice is kept (TextToSpeech itself falls back
     * to the default engine for a package it can't find).
     */
    fun resolve(saved: String?, installed: List<TtsEngineInfo>?): String? {
        if (saved.isNullOrBlank()) return null
        if (installed == null) return saved
        return saved.takeIf { s -> installed.any { it.packageName == s } }
    }

    /**
     * Identity of the engine binding. Following the system default includes the default engine's
     * package, so changing it in the system settings rebinds on the next resume.
     */
    fun bindingKey(resolved: String?, systemDefault: String?): String =
        resolved ?: "default:${systemDefault.orEmpty()}"

    /** System engines first (the usual default), then alphabetically by label. */
    fun sorted(engines: List<TtsEngineInfo>): List<TtsEngineInfo> =
        engines.distinctBy { it.packageName }
            .sortedWith(compareByDescending<TtsEngineInfo> { it.system }.thenBy { it.label.lowercase(Locale.ROOT) })

    /** Chinese-family languages (Mandarin, Cantonese and other Sinitic codes an engine may use). */
    private val CHINESE = setOf("zh", "yue", "cmn", "wuu", "nan", "hak", "gan", "hsn", "cdo", "cjy", "cpx", "czh", "czo", "mnp", "lzh")

    fun isChinese(locale: Locale): Boolean = locale.language.lowercase(Locale.ROOT) in CHINESE

    /** For an app language code such as `zh`, `zh-Hant`, `yue`, `zh-HK`. */
    fun isChineseCode(code: String): Boolean = isChinese(TtsLocales.localeFor(code))

    /**
     * True when reading [locale] failed because the engine has no Chinese voice: the language is
     * Chinese and some candidate (the locale itself or its fallback, e.g. yue -> zh-HK) answered
     * [TextToSpeech.isLanguageAvailable] with LANG_MISSING_DATA or LANG_NOT_SUPPORTED.
     */
    fun lacksChineseVoice(locale: Locale, availability: List<Int>): Boolean =
        isChinese(locale) && availability.any {
            it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED
        }

    /** The recommended engine APK is arm64-v8a only. */
    fun isArm64(abis: List<String>): Boolean = abis.any { it.equals(ARM64, ignoreCase = true) }

    const val ARM64 = "arm64-v8a"
}

/**
 * Download links for the sherpa-onnx Kokoro multi-lang TTS engine (offline, Chinese + English).
 * Bump [VERSION] when a newer engine APK is published on [ENGINE_LIST].
 */
object KokoroEngineLinks {
    const val VERSION = "1.13.8"
    private const val APK_PATH =
        "csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/$VERSION/" +
            "sherpa-onnx-$VERSION-arm64-v8a-eng-tts-engine-kokoro-multi-lang-v1_1.apk"

    /** Mainland-China mirror of the HuggingFace download. */
    const val MIRROR_APK = "https://hf-mirror.com/$APK_PATH"
    const val HUGGINGFACE_APK = "https://huggingface.co/$APK_PATH"

    /** Official list of engine APKs (newer versions, other ABIs). */
    const val ENGINE_LIST = "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine-cn.html"
    const val SOURCE = "https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine"
}

/**
 * Installed TTS engines: the same PackageManager query [TextToSpeech.getEngines] runs, without
 * binding an engine first (that would cost a service connection just to list them).
 */
fun queryTtsEngines(context: Context): TtsEngineList {
    val pm = context.packageManager
    val engines = runCatching {
        @Suppress("DEPRECATION")
        pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                TtsEngineInfo(
                    packageName = si.packageName,
                    label = label?.takeIf { it.isNotBlank() } ?: si.packageName,
                    system = (si.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
    }.getOrDefault(emptyList())
    val default = runCatching { Settings.Secure.getString(context.contentResolver, TTS_DEFAULT_SYNTH) }
        .getOrNull()?.takeIf { it.isNotBlank() }
    return TtsEngineList(TtsEngineChoice.sorted(engines), default)
}

/** Settings.Secure.TTS_DEFAULT_SYNTH. */
private const val TTS_DEFAULT_SYNTH = "tts_default_synth"
