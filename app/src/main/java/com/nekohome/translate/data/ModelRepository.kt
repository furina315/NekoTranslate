package com.nekohome.translate.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nekohome.translate.llama.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A GGUF file in app-private storage plus what we read from its header. */
data class LocalModel(
    val file: File,
    val architecture: String?,
    val generalName: String?,
    val fileType: String?,
    val contextLength: Long?,
    val hasChatTemplate: Boolean,
    val profile: ModelProfile,
) {
    val fileName: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val displayName: String get() = generalName?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
}

/**
 * Model import and storage. Models are copied into filesDir/models so they can be mmap'ed by
 * path and survive the source document being moved or revoked.
 */
class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    /** Lists imported models (reads each GGUF header; cheap compared to loading). */
    suspend fun listModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f -> runCatching { describe(f) }.getOrNull() }
    }

    fun describe(file: File): LocalModel {
        val header = GgufMetadataReader.read(file)
        return LocalModel(
            file = file,
            architecture = header.architecture,
            generalName = header.name,
            fileType = header.fileTypeName,
            contextLength = header.contextLength(),
            hasChatTemplate = !header.chatTemplate.isNullOrBlank(),
            profile = BuiltInProfiles.match(header.architecture, header.name, file.name),
        )
    }

    /**
     * Copies a user-picked document into [modelsDir], reporting progress in 0..1 (or -1 when the
     * size is unknown). Validates the GGUF magic before copying and the header after.
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "model.gguf"
        var totalSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) displayName = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) totalSize = c.getLong(si)
                }
            }

        val safeName = sanitize(displayName).let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val target = uniqueTarget(safeName)
        val partial = File(modelsDir, "${target.name}.part")

        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $displayName")
            input.use { ins ->
                val head = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = ins.read(head, read, 4 - read)
                    if (n < 0) break
                    read += n
                }
                if (read < 4 || !GgufMetadataReader.hasMagic(head)) {
                    throw GgufMetadataReader.NotGgufException("“$displayName” is not a GGUF file")
                }
                partial.outputStream().use { out ->
                    out.write(head, 0, 4)
                    var copied = 4L
                    val buf = ByteArray(1 shl 20)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= (8 shl 20)) {
                            lastReport = copied
                            onProgress(if (totalSize > 0) copied.toFloat() / totalSize else -1f)
                        }
                    }
                    out.fd.sync()
                }
            }
            onProgress(1f)
            // Validate the full header before exposing the file.
            GgufMetadataReader.read(partial)
            if (!partial.renameTo(target)) throw IOException("Cannot move model into place")
            describe(target)
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    fun delete(model: LocalModel): Boolean = model.file.delete()

    private fun uniqueTarget(name: String): File {
        var f = File(modelsDir, name)
        if (!f.exists()) return f
        val base = name.removeSuffix(".gguf")
        var i = 2
        while (f.exists()) f = File(modelsDir, "$base ($i).gguf").also { i++ }
        return f
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim().ifEmpty { "model.gguf" }
}
