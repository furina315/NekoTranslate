package com.nekohome.translate.llama

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Minimal pure-Kotlin GGUF header reader (no native code, no model load).
 *
 * Used at import time to validate the file and read the few metadata keys needed to pick a
 * [ModelProfile][com.nekohome.translate] before the model is ever loaded into llama.cpp.
 * Array values are skipped except for their length. Spec: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
 */
object GgufMetadataReader {

    const val MAGIC = 0x46554747 // "GGUF" little-endian

    /** Keys whose values are kept. Everything else is skipped. */
    val DEFAULT_KEYS = setOf(
        "general.architecture",
        "general.name",
        "general.basename",
        "general.size_label",
        "general.file_type",
        "general.quantization_version",
        "general.type",
        "tokenizer.chat_template",
        "clip.has_vision_encoder",
        "clip.projector_type",
    )

    data class GgufHeader(
        val version: Int,
        val tensorCount: Long,
        val metadata: Map<String, Any>,
    ) {
        val architecture: String? get() = metadata["general.architecture"] as? String
        val name: String? get() = metadata["general.name"] as? String
        val chatTemplate: String? get() = metadata["tokenizer.chat_template"] as? String
        val fileType: Int? get() = (metadata["general.file_type"] as? Number)?.toInt()
        val fileTypeName: String? get() = fileType?.let { fileTypeName(it) }
        /** general.type, e.g. "model" or "mmproj" (absent in older files). */
        val type: String? get() = metadata["general.type"] as? String
        val projectorType: String? get() = metadata["clip.projector_type"] as? String
        val hasVisionEncoder: Boolean get() = metadata["clip.has_vision_encoder"] == true

        /** True for a multimodal projector (mmproj) GGUF: arch "clip" or general.type "mmproj". */
        val isMmproj: Boolean get() = architecture == "clip" || type == "mmproj"

        fun contextLength(): Long? =
            architecture?.let { (metadata["$it.context_length"] as? Number)?.toLong() }
    }

    class NotGgufException(message: String) : IOException(message)

    /** True if the first four bytes are the GGUF magic. */
    fun hasMagic(first4: ByteArray): Boolean =
        first4.size >= 4 && first4[0] == 'G'.code.toByte() && first4[1] == 'G'.code.toByte() &&
            first4[2] == 'U'.code.toByte() && first4[3] == 'F'.code.toByte()

    fun read(file: File, keys: Set<String> = DEFAULT_KEYS): GgufHeader =
        file.inputStream().use { read(it, keys) }

    fun read(input: InputStream, keys: Set<String> = DEFAULT_KEYS): GgufHeader {
        val r = LeReader(DataInputStream(BufferedInputStream(input, 1 shl 16)))
        val magic = r.u32()
        if (magic != MAGIC) throw NotGgufException("Not a GGUF file (bad magic)")
        val version = r.u32()
        if (version < 2 || version > 3) throw NotGgufException("Unsupported GGUF version $version")
        val tensorCount = r.u64()
        val kvCount = r.u64()
        if (kvCount < 0 || kvCount > 1_000_000) throw NotGgufException("Corrupt GGUF header")

        // Dynamic keys that depend on the architecture.
        val wanted = keys.toMutableSet()
        val meta = LinkedHashMap<String, Any>()
        for (i in 0 until kvCount) {
            val key = r.string()
            val type = r.u32()
            if (key == "general.architecture") {
                val arch = r.value(type) as String
                meta[key] = arch
                wanted += "$arch.context_length"
                continue
            }
            if (key in wanted) {
                meta[key] = r.value(type)
            } else {
                r.skipValue(type)
            }
        }
        return GgufHeader(version, tensorCount, meta)
    }

    /** llama_ftype names for general.file_type (subset; unknown ids are shown as numbers). */
    fun fileTypeName(id: Int): String = when (id) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"
        10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"; 13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"
        16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"; 21 -> "Q2_K_S"
        22 -> "IQ3_XS"; 23 -> "IQ3_XXS"; 24 -> "IQ1_S"; 25 -> "IQ4_NL"; 26 -> "IQ3_S"; 27 -> "IQ3_M"
        28 -> "IQ2_S"; 29 -> "IQ2_M"; 30 -> "IQ4_XS"; 31 -> "IQ1_M"; 32 -> "BF16"
        36 -> "TQ1_0"; 37 -> "TQ2_0"; 38 -> "MXFP4_MOE"
        else -> "ftype#$id"
    }

    private class LeReader(private val input: DataInputStream) {
        private val buf = ByteArray(8)

        private fun fill(n: Int) {
            try {
                input.readFully(buf, 0, n)
            } catch (e: EOFException) {
                throw NotGgufException("Truncated GGUF header")
            }
        }

        fun u8(): Int { fill(1); return buf[0].toInt() and 0xFF }
        fun u16(): Int { fill(2); return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) }
        fun u32(): Int {
            fill(4)
            return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
        }
        fun u64(): Long {
            fill(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (buf[i].toLong() and 0xFF)
            return v
        }

        fun string(): String {
            val len = u64()
            if (len < 0 || len > 64L * 1024 * 1024) throw NotGgufException("Corrupt string length $len")
            val bytes = ByteArray(len.toInt())
            try {
                input.readFully(bytes)
            } catch (e: EOFException) {
                throw NotGgufException("Truncated GGUF header")
            }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun skipBytes(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw NotGgufException("Truncated GGUF header")
                    left -= 1
                } else {
                    left -= s
                }
            }
        }

        fun value(type: Int): Any = when (type) {
            T_UINT8 -> u8(); T_INT8 -> u8().toByte().toInt()
            T_UINT16 -> u16(); T_INT16 -> u16().toShort().toInt()
            T_UINT32 -> u32().toLong() and 0xFFFFFFFFL; T_INT32 -> u32()
            T_FLOAT32 -> Float.fromBits(u32())
            T_BOOL -> u8() != 0
            T_STRING -> string()
            T_UINT64, T_INT64 -> u64()
            T_FLOAT64 -> Double.fromBits(u64())
            T_ARRAY -> { skipValue(type); "[array]" }
            else -> throw NotGgufException("Unknown GGUF value type $type")
        }

        fun skipValue(type: Int) {
            when (type) {
                T_UINT8, T_INT8, T_BOOL -> skipBytes(1)
                T_UINT16, T_INT16 -> skipBytes(2)
                T_UINT32, T_INT32, T_FLOAT32 -> skipBytes(4)
                T_UINT64, T_INT64, T_FLOAT64 -> skipBytes(8)
                T_STRING -> {
                    val len = u64()
                    if (len < 0) throw NotGgufException("Corrupt string length")
                    skipBytes(len)
                }
                T_ARRAY -> {
                    val elemType = u32()
                    val count = u64()
                    if (count < 0) throw NotGgufException("Corrupt array length")
                    val fixed = when (elemType) {
                        T_UINT8, T_INT8, T_BOOL -> 1L
                        T_UINT16, T_INT16 -> 2L
                        T_UINT32, T_INT32, T_FLOAT32 -> 4L
                        T_UINT64, T_INT64, T_FLOAT64 -> 8L
                        else -> -1L
                    }
                    if (fixed > 0) skipBytes(fixed * count) else for (i in 0 until count) skipValue(elemType)
                }
                else -> throw NotGgufException("Unknown GGUF value type $type")
            }
        }

        companion object {
            const val T_UINT8 = 0; const val T_INT8 = 1; const val T_UINT16 = 2; const val T_INT16 = 3
            const val T_UINT32 = 4; const val T_INT32 = 5; const val T_FLOAT32 = 6; const val T_BOOL = 7
            const val T_STRING = 8; const val T_ARRAY = 9; const val T_UINT64 = 10; const val T_INT64 = 11
            const val T_FLOAT64 = 12
        }
    }
}
