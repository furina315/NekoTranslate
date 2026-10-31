package com.nekohome.translate.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DiagnosticsReportTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    private fun info(model: DiagModel? = null) = DiagInfo(
        generatedAtMs = 1_793_426_531_123L, // 2026-10-31 14:02:11 Asia/Shanghai
        appId = "com.nekohome.translate",
        versionName = "0.3.0-nightly.7",
        versionCode = 207,
        buildType = "release",
        llamaVersion = "b11827",
        manufacturer = "Xiaomi",
        model = "24129PN74C",
        device = "dada",
        androidRelease = "16",
        sdkInt = 36,
        soc = "QTI SM8750",
        abis = listOf("arm64-v8a"),
        totalRamMb = 15_872,
        rssMb = 2_410,
        heapMaxMb = 512,
        gpu = "QUALCOMM Adreno(TM) 830（自检通过）",
        systemInfo = "CPU : NEON = 1 | ARM_FMA = 1 |",
        loadedModel = model,
        lastModel = "Index-Translate-2B.Q4_K_M.gguf",
        ttsChosen = "",
        ttsEffective = "com.k2fsa.sherpa.onnx.tts.engine",
        settings = listOf("保存翻译历史" to "开", "术语表" to "开 · 3 条"),
        persistentLogBytes = 3 * 1024 * 1024 / 2,
    )

    private val model = DiagModel(
        fileName = "Index-Translate-2B.Q4_K_M.gguf",
        architecture = "qwen3vl",
        fileType = "Q4_K - Medium",
        sizeBytes = 1_288_490_189L,
        nCtx = 4096,
        nCtxTrain = 262_144,
        requestedCtx = 4096,
        threads = 6,
        batchThreads = 8,
        batch = 512,
        mmap = true,
        gpuMode = "encoder",
        textOnGpu = false,
        loadMs = 812,
        mmproj = "mmproj-Index-Translate-2B-Q8_0.gguf",
        profile = "Index-Translate",
    )

    @Test
    fun headerWithLoadedModel() {
        val h = DiagnosticsReport.header(info(model), shanghai)
        val lines = h.lines()
        assertEquals("NekoTranslate 诊断日志", lines[0])
        assertEquals("生成时间: 10-31 14:02:11.123 (Asia/Shanghai)", lines[1])
        listOf(
            "版本: 0.3.0-nightly.7 (versionCode 207, release)",
            "型号: Xiaomi 24129PN74C (dada)",
            "Android: 16 (API 36)",
            "SoC: QTI SM8750",
            "ABI: arm64-v8a",
            "内存: 总计 15872 MB · 本进程 RSS 2410 MB · Java 堆上限 512 MB",
            "当前模型: Index-Translate-2B.Q4_K_M.gguf",
            "类型: Index-Translate · qwen3vl · Q4_K - Medium · 1.20 GB",
            "上下文: n_ctx 4096（设置 4096，训练 262144）",
            "线程: 生成 6 · 预填充 8 · batch 512 · mmap 开",
            "GPU 档位: encoder（文本模型在 CPU）",
            "加载耗时: 0.81 s",
            "视觉投影: mmproj-Index-Translate-2B-Q8_0.gguf",
            "引擎设置: 跟随系统默认",
            "实际引擎: com.k2fsa.sherpa.onnx.tts.engine",
            "术语表: 开 · 3 条",
            "持久化日志: 1.5 MB",
        ).forEach { assertTrue("missing: $it\n$h", it in lines) }
        assertFalse(h.contains("上次使用"))
    }

    @Test
    fun headerWithoutModel() {
        val lines = DiagnosticsReport.header(info(null), shanghai).lines()
        assertTrue("当前模型: 未加载" in lines)
        assertTrue("上次使用: Index-Translate-2B.Q4_K_M.gguf" in lines)
    }

    @Test
    fun valuesStayOnOneLine() {
        val h = DiagnosticsReport.header(info(null).copy(systemInfo = "CPU : NEON = 1\nOpenCL : 1\n"), shanghai)
        assertTrue("CPU 特性: CPU : NEON = 1 OpenCL : 1" in h.lines())
    }

    @Test
    fun assembleOrdersSectionsAndScrubs() {
        val out = DiagnosticsReport.assemble(
            header = "HEADER\n",
            persistent = "a I/NekoImg: picked content://media/external/images/media/42\n",
            logcat = "",
        )
        val head = out.indexOf("HEADER")
        val ring = out.indexOf("== 应用日志（持久化，跨重启保留）==")
        val cat = out.indexOf("== logcat（本进程）==")
        assertTrue(head in 0 until ring && ring < cat)
        assertFalse(out.contains("media/42"))
        assertTrue(out.trimEnd().endsWith("（空）"))
    }

    @Test
    fun tailKeepsTheEndAtALineStart() {
        val text = (1..100).joinToString("") { "row $it\n" }
        val t = DiagnosticsReport.tail(text, 30)
        assertTrue(t.startsWith("…（前面 "))
        val kept = t.substringAfter('\n')
        assertTrue(kept.startsWith("row "))
        assertTrue(kept.endsWith("row 100\n"))
        assertTrue(kept.length <= 30)
        assertEquals("short\n", DiagnosticsReport.tail("short\n", 30))
    }

    @Test
    fun fileNameAndSizes() {
        assertEquals("NekoTranslate-log-20261031-140211.txt", DiagnosticsReport.fileName(1_793_426_531_123L, shanghai))
        assertEquals("512 B", DiagnosticsReport.mb(512))
        assertEquals("512.0 KB", DiagnosticsReport.mb(512 * 1024))
    }
}
