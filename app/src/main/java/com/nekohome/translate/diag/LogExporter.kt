package com.nekohome.translate.diag

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.nekohome.translate.BuildConfig
import com.nekohome.translate.data.AppSettings
import com.nekohome.translate.data.ModelParamsStore
import com.nekohome.translate.data.ModelRepository
import com.nekohome.translate.engine.MemoryStats
import com.nekohome.translate.engine.ModelSession
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.queryTtsEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 设置 → 诊断 → 导出日志: builds one plain-text file (device / app / model header, the persistent
 * ring log, this process's logcat) and hands it to the share sheet or a file the user picked.
 */
object LogExporter {

    private const val EXPORT_DIR = "export"
    private const val MAX_LOGCAT_CHARS = 1_500_000

    /** Builds the report text (IO thread). */
    suspend fun buildReport(context: Context, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val header = DiagnosticsReport.header(collect(app, settings))
        DiagnosticsReport.assemble(header, AppLog.readPersistent(), readLogcat())
    }

    /** Writes [text] to cache/export/ (older exports removed) and returns the file (IO thread). */
    suspend fun writeShareFile(context: Context, text: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, DiagnosticsReport.fileName(System.currentTimeMillis())).apply { writeText(text, Charsets.UTF_8) }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Writes [text] into a document the user created with ACTION_CREATE_DOCUMENT (IO thread). */
    suspend fun writeTo(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open output")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun suggestedFileName(): String = DiagnosticsReport.fileName(System.currentTimeMillis())

    /** `logcat -d -v threadtime --pid=<me>`: own-process logs need no permission. */
    fun readLogcat(): String {
        val p = try {
            ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return "logcat 不可用: ${e.javaClass.simpleName}: ${e.message}\n"
        }
        return try {
            val buf = ByteArrayOutputStream()
            p.inputStream.use { it.copyTo(buf) }
            p.waitFor(3, TimeUnit.SECONDS)
            DiagnosticsReport.tail(buf.toString(Charsets.UTF_8.name()), MAX_LOGCAT_CHARS)
        } catch (e: Exception) {
            "logcat 读取失败: ${e.javaClass.simpleName}: ${e.message}\n"
        } finally {
            p.destroy()
        }
    }

    private suspend fun collect(app: Context, s: AppSettings): DiagInfo {
        val session = ModelSession.get(app)
        val loaded = session.loaded.value
        val model = loaded?.let { l ->
            val file = File(l.path)
            val profile = runCatching {
                val m = ModelRepository(app).describe(file)
                val params = ModelParamsStore(app).loadAll()[m.fileName]
                (params?.profile(m.profile) ?: m.profile).displayName
            }.getOrDefault("?")
            DiagModel(
                fileName = file.name,
                architecture = l.info.architecture,
                fileType = l.info.fileType,
                sizeBytes = l.info.sizeBytes,
                nCtx = l.info.nCtx,
                nCtxTrain = l.info.nCtxTrain,
                requestedCtx = l.params.nCtx,
                threads = l.params.nThreads,
                batchThreads = l.params.nThreadsBatch,
                batch = l.params.nBatch,
                mmap = l.params.useMmap,
                gpuMode = l.params.gpuMode.key,
                textOnGpu = l.textOnGpu,
                loadMs = l.loadMs,
                mmproj = session.engine.mmprojName,
                profile = profile,
            )
        }
        val engines = runCatching { queryTtsEngines(app) }.getOrNull()
        val effective = engines?.let { TtsEngineChoice.resolve(s.ttsEngine, it.engines) ?: it.systemDefault }
        val gpu = session.gpuStateOrNull()
        val gpuText = when {
            gpu == null -> "未检测"
            !gpu.supported -> "不支持"
            else -> gpu.device + when {
                gpu.failure != null -> "（已停用：${gpu.failure}）"
                gpu.verified -> "（自检通过）"
                else -> "（未自检）"
            }
        }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRam = am?.let { ActivityManager.MemoryInfo().also { mi -> it.getMemoryInfo(mi) }.totalMem / (1024 * 1024) }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val versionCode = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        }.getOrDefault(-1L)
        return DiagInfo(
            generatedAtMs = System.currentTimeMillis(),
            appId = app.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = versionCode,
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            llamaVersion = BuildConfig.LLAMA_CPP_VERSION,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            soc = soc,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = totalRam,
            rssMb = MemoryStats.residentMb(),
            heapMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            gpu = gpuText,
            systemInfo = session.engine.cachedSystemInfo.orEmpty(),
            loadedModel = model,
            lastModel = session.lastModelName,
            ttsChosen = s.ttsEngine,
            ttsEffective = effective,
            settings = settingsSummary(s),
            persistentLogBytes = AppLog.persistentBytes(),
        )
    }

    /** Key settings only; glossary entries are user content, so just their count. */
    private fun settingsSummary(s: AppSettings): List<Pair<String, String>> = listOf(
        "主题" to "${s.themeMode.name.lowercase()} · 动态取色 ${if (s.dynamicColor) "开" else "关"}",
        "默认目标语言" to s.defaultTargetCode,
        "保存翻译历史" to if (s.historyEnabled) "开" else "关",
        "术语表" to "${if (s.glossaryEnabled) "开" else "关"} · ${s.glossary.size} 条",
        "朗读语速 / 音调" to "${s.ttsRate} / ${s.ttsPitch}",
        "翻译时保持亮屏" to if (s.keepScreenOn) "开" else "关",
        "显示速度统计" to if (s.showStats) "开" else "关",
    )
}
