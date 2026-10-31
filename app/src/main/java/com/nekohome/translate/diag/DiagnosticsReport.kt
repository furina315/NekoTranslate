package com.nekohome.translate.diag

import java.util.Locale
import java.util.TimeZone

/** The loaded model as the export header shows it. */
data class DiagModel(
    val fileName: String,
    val architecture: String,
    val fileType: String,
    val sizeBytes: Long,
    /** n_ctx the context was actually created with. */
    val nCtx: Int,
    val nCtxTrain: Int,
    val requestedCtx: Int,
    val threads: Int,
    val batchThreads: Int,
    val batch: Int,
    val mmap: Boolean,
    /** GpuMode key (off / encoder / all). */
    val gpuMode: String,
    val textOnGpu: Boolean,
    val loadMs: Long,
    /** Vision projector file currently loaded, or null. */
    val mmproj: String?,
    /** Profile (model family) used for prompts, e.g. Index-Translate. */
    val profile: String,
)

/** Everything in the header of an exported log. No user text, no file contents. */
data class DiagInfo(
    val generatedAtMs: Long,
    val appId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val llamaVersion: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val soc: String,
    val abis: List<String>,
    val totalRamMb: Long?,
    val rssMb: Long?,
    val heapMaxMb: Long,
    val gpu: String,
    val systemInfo: String,
    val loadedModel: DiagModel?,
    val lastModel: String?,
    /** Engine picked in 设置 (empty = follow the system default). */
    val ttsChosen: String,
    /** Engine actually bound (package), or null when there is none. */
    val ttsEffective: String?,
    val settings: List<Pair<String, String>>,
    val persistentLogBytes: Long,
)

/** Formats [DiagInfo] as the plain-text header of an export. Pure JVM. */
object DiagnosticsReport {

    fun header(info: DiagInfo, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        appendLine("NekoTranslate 诊断日志")
        appendLine("生成时间: ${LogFormat.timestamp(info.generatedAtMs, zone)} (${zone.id})")
        appendLine("说明: 默认不含原文 / 译文内容（只记录长度）")
        appendLine()
        section("应用")
        kv("包名", info.appId)
        kv("版本", "${info.versionName} (versionCode ${info.versionCode}, ${info.buildType})")
        kv("llama.cpp", info.llamaVersion)
        appendLine()
        section("设备")
        kv("型号", "${info.manufacturer} ${info.model} (${info.device})")
        kv("Android", "${info.androidRelease} (API ${info.sdkInt})")
        kv("SoC", info.soc.ifBlank { "未知" })
        kv("ABI", info.abis.joinToString(", ").ifBlank { "未知" })
        kv("内存", buildList {
            info.totalRamMb?.let { add("总计 $it MB") }
            info.rssMb?.let { add("本进程 RSS $it MB") }
            add("Java 堆上限 ${info.heapMaxMb} MB")
        }.joinToString(" · "))
        kv("GPU", info.gpu.ifBlank { "无" })
        kv("CPU 特性", info.systemInfo.ifBlank { "未初始化" })
        appendLine()
        section("模型")
        val m = info.loadedModel
        if (m == null) {
            kv("当前模型", "未加载")
            kv("上次使用", info.lastModel ?: "无")
        } else {
            kv("当前模型", m.fileName)
            kv("类型", "${m.profile} · ${m.architecture.ifBlank { "?" }} · ${m.fileType.ifBlank { "?" }} · ${mb(m.sizeBytes)}")
            kv("上下文", "n_ctx ${m.nCtx}（设置 ${m.requestedCtx}，训练 ${m.nCtxTrain}）")
            kv("线程", "生成 ${m.threads} · 预填充 ${m.batchThreads} · batch ${m.batch} · mmap ${onOff(m.mmap)}")
            kv("GPU 档位", "${m.gpuMode}（文本模型在 ${if (m.textOnGpu) "GPU" else "CPU"}）")
            kv("加载耗时", String.format(Locale.US, "%.2f s", m.loadMs / 1000.0))
            kv("视觉投影", m.mmproj ?: "未加载")
        }
        appendLine()
        section("朗读")
        kv("引擎设置", info.ttsChosen.ifBlank { "跟随系统默认" })
        kv("实际引擎", info.ttsEffective ?: "无")
        appendLine()
        section("设置")
        for ((k, v) in info.settings) kv(k, v)
        appendLine()
        kv("持久化日志", mb(info.persistentLogBytes))
    }

    private fun StringBuilder.section(title: String) {
        append("== ").append(title).append(" ==\n")
    }

    private fun StringBuilder.kv(key: String, value: String) {
        append(key).append(": ").append(value.replace('\n', ' ').trim()).append('\n')
    }

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    fun mb(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Export file name: NekoTranslate-log-20261031-140211.txt */
    fun fileName(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "NekoTranslate-log-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }.format(java.util.Date(timeMs)) +
            ".txt"

    /** Keeps the last [maxChars] of [text], cut at a line start, with a marker. */
    fun tail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var start = text.length - maxChars
        val nl = text.indexOf('\n', start)
        if (nl in start until text.length - 1) start = nl + 1
        return "…（前面 ${start} 个字符已省略）\n" + text.substring(start)
    }

    /** Header + persistent log + logcat, scrubbed by [LogPrivacy.scrub]. */
    fun assemble(header: String, persistent: String, logcat: String): String = LogPrivacy.scrub(
        buildString {
            append(header)
            append("\n== 应用日志（持久化，跨重启保留）==\n")
            append(persistent.ifBlank { "（空）\n" })
            if (!endsWith('\n')) append('\n')
            append("\n== logcat（本进程）==\n")
            append(logcat.ifBlank { "（空）\n" })
            if (!endsWith('\n')) append('\n')
        },
    )
}
