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
import java.security.MessageDigest

/** A GGUF file in app-private storage plus what we read from its header. */
data class LocalModel(
    val file: File,
    val architecture: String?,
    val generalName: String?,
    val fileType: String?,
    val contextLength: Long?,
    val hasChatTemplate: Boolean,
    val profile: ModelProfile,
    /** Comparison with the known official files (OfficialFiles); null = not an official size. */
    val official: OfficialMatch? = null,
) {
    val fileName: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val displayName: String get() = generalName?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
}

/** What a document picked through the model import turned out to be. */
sealed interface ImportResult {
    data class Model(val model: LocalModel) : ImportResult

    /** A vision projector (mmproj GGUF); it was stored in [ModelRepository.mmprojDir]. */
    data class Projector(val file: File) : ImportResult
}

/**
 * Model import and storage. Models are copied into filesDir/models so they can be mmap'ed by
 * path and survive the source document being moved or revoked. Vision projectors (mmproj GGUFs,
 * for image translation) are copied the same way into filesDir/mmproj and associated with a model
 * through [ModelParams.mmproj].
 */
class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val mmprojDir: File = File(context.filesDir, "mmproj").apply { mkdirs() }

    class NotMmprojException(message: String) : IOException(message)

    /** Lists imported models (reads each GGUF header; cheap compared to loading). */
    suspend fun listModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f ->
                runCatching {
                    val header = GgufMetadataReader.read(f)
                    if (header.isMmproj) null else describe(f, header)
                }.getOrNull()
            }
    }

    fun describe(file: File): LocalModel = describe(file, GgufMetadataReader.read(file))

    private fun describe(file: File, header: GgufMetadataReader.GgufHeader): LocalModel {
        val size = file.length()
        return LocalModel(
            file = file,
            architecture = header.architecture,
            generalName = header.name,
            fileType = header.fileTypeName,
            contextLength = header.contextLength(),
            hasChatTemplate = !header.chatTemplate.isNullOrBlank(),
            profile = BuiltInProfiles.match(header.architecture, header.name, file.name),
            official = if (OfficialFiles.isCandidateSize(size)) OfficialFiles.match(size, readChecksum(file)) else null,
        )
    }

    /** Checksum sidecar "<model>.gguf.sha256", written at import for files of an official size. */
    private fun checksumFile(model: File) = File(model.parentFile, model.name + CHECKSUM_SUFFIX)

    private fun readChecksum(model: File): String? =
        runCatching { checksumFile(model).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 64 } }.getOrNull()

    /**
     * Copies a user-picked document into [modelsDir], reporting progress in 0..1 (or -1 when the
     * size is unknown). Validates the GGUF magic before copying and the header after. A vision
     * projector picked here is recognised by its header and stored in [mmprojDir] instead.
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        val copy = copyToPartial(uri, modelsDir, onProgress, hash = true)
        val (partial, displayName) = copy
        try {
            // Validate the full header before exposing the file.
            val header = GgufMetadataReader.read(partial)
            if (header.isMmproj) {
                ImportResult.Projector(moveIntoPlace(partial, uniqueTarget(mmprojDir, safeGgufName(displayName))))
            } else {
                val target = moveIntoPlace(partial, uniqueTarget(modelsDir, safeGgufName(displayName)))
                val sha = copy.sha256
                checksumFile(target).delete() // a stale one left by a file of the same name
                if (sha != null && OfficialFiles.isCandidateSize(target.length())) {
                    runCatching { checksumFile(target).writeText(sha) }
                        .onFailure { checksumFile(target).delete() }
                }
                ImportResult.Model(describe(target, header))
            }
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    /**
     * Copies a user-picked vision projector (mmproj GGUF) into [mmprojDir]. Rejects GGUFs whose
     * header is not a CLIP / mmproj file with a vision encoder.
     */
    suspend fun importMmproj(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val (partial, displayName) = copyToPartial(uri, mmprojDir, onProgress, hash = false)
        try {
            val header = GgufMetadataReader.read(partial)
            if (!header.isMmproj) {
                throw NotMmprojException("“$displayName” 不是视觉投影（mmproj）文件（架构：${header.architecture ?: "未知"}）")
            }
            if (header.metadata.containsKey("clip.has_vision_encoder") && !header.hasVisionEncoder) {
                throw NotMmprojException("“$displayName” 不含视觉编码器（可能是音频投影），无法用于图片翻译")
            }
            moveIntoPlace(partial, uniqueTarget(mmprojDir, safeGgufName(displayName)))
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    /** The stored projector called [name], if it still exists. */
    fun mmprojFile(name: String?): File? =
        name?.let { File(mmprojDir, it) }?.takeIf { it.isFile && it.parentFile == mmprojDir }

    fun deleteMmproj(name: String): Boolean = mmprojFile(name)?.delete() ?: false

    private data class Copied(val partial: File, val displayName: String, val sha256: String?)

    /**
     * Copies [uri] to "<dir>/<name>.part" after checking the GGUF magic; returns it with the display
     * name. With [hash], the SHA-256 is computed while copying unless the size is known and matches
     * no official file (OfficialFiles), so ordinary imports pay nothing extra.
     */
    private suspend fun copyToPartial(uri: Uri, dir: File, onProgress: (Float) -> Unit, hash: Boolean): Copied =
        withContext(Dispatchers.IO) {
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

            val partial = File(dir, "${safeGgufName(displayName)}.${System.nanoTime()}.part")
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
                    val digest = if (hash && (totalSize < 0 || OfficialFiles.isCandidateSize(totalSize))) {
                        MessageDigest.getInstance("SHA-256").apply { update(head, 0, 4) }
                    } else {
                        null
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
                            digest?.update(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= (8 shl 20)) {
                                lastReport = copied
                                onProgress(if (totalSize > 0) copied.toFloat() / totalSize else -1f)
                            }
                        }
                        out.fd.sync()
                    }
                    onProgress(1f)
                    Copied(partial, displayName, digest?.let { OfficialFiles.hex(it.digest()) })
                }
            } catch (t: Throwable) {
                partial.delete()
                throw t
            }
        }

    private fun moveIntoPlace(partial: File, target: File): File {
        if (!partial.renameTo(target)) throw IOException("Cannot move file into place")
        return target
    }

    fun delete(model: LocalModel): Boolean {
        checksumFile(model.file).delete()
        return model.file.delete()
    }

    private companion object {
        const val CHECKSUM_SUFFIX = ".sha256"
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.removeSuffix(".gguf")
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i).gguf").also { i++ }
        return f
    }

    private fun safeGgufName(displayName: String): String =
        sanitize(displayName).let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim().ifEmpty { "model.gguf" }
}
