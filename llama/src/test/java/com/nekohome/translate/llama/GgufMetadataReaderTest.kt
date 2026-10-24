package com.nekohome.translate.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufMetadataReaderTest {

    private class Writer {
        val out = ByteArrayOutputStream()
        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(v: Int) = out.write(le(4).putInt(v).array())
        fun u64(v: Long) = out.write(le(8).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
    }

    private fun sampleGguf(): ByteArray {
        val w = Writer()
        w.out.write("GGUF".toByteArray())
        w.u32(3)        // version
        w.u64(0)        // tensor count
        w.u64(5)        // kv count
        w.str("general.architecture"); w.u32(8); w.str("qwen35")
        w.str("general.name"); w.u32(8); w.str("Index-Translate-2B")
        w.str("general.file_type"); w.u32(4); w.u32(15)
        // string array that must be skipped
        w.str("tokenizer.ggml.tokens"); w.u32(9); w.u32(8); w.u64(2); w.str("a"); w.str("翻译")
        w.str("qwen35.context_length"); w.u32(4); w.u32(262144)
        return w.out.toByteArray()
    }

    @Test
    fun readsWantedKeysAndSkipsArrays() {
        val h = GgufMetadataReader.read(ByteArrayInputStream(sampleGguf()))
        assertEquals(3, h.version)
        assertEquals("qwen35", h.architecture)
        assertEquals("Index-Translate-2B", h.name)
        assertEquals(15, h.fileType)
        assertEquals("Q4_K_M", h.fileTypeName)
        assertEquals(262144L, h.contextLength())
        assertFalse(h.metadata.containsKey("tokenizer.ggml.tokens"))
    }

    @Test
    fun magicCheck() {
        assertTrue(GgufMetadataReader.hasMagic("GGUF".toByteArray()))
        assertFalse(GgufMetadataReader.hasMagic("PK\u0003\u0004".toByteArray()))
    }

    @Test(expected = GgufMetadataReader.NotGgufException::class)
    fun rejectsNonGguf() {
        GgufMetadataReader.read(ByteArrayInputStream(ByteArray(64)))
    }

    @Test
    fun recognisesVisionProjector() {
        // Header layout of Index-Translate-2B.mmproj-Q8_0.gguf (abridged).
        val w = Writer()
        w.out.write("GGUF".toByteArray())
        w.u32(3); w.u64(0); w.u64(5)
        w.str("general.architecture"); w.u32(8); w.str("clip")
        w.str("general.type"); w.u32(8); w.str("mmproj")
        w.str("clip.has_vision_encoder"); w.u32(7); w.out.write(1)
        w.str("clip.vision.image_mean"); w.u32(9); w.u32(6); w.u64(3); w.u32(0); w.u32(0); w.u32(0)
        w.str("clip.projector_type"); w.u32(8); w.str("qwen3vl_merger")
        val h = GgufMetadataReader.read(ByteArrayInputStream(w.out.toByteArray()))
        assertTrue(h.isMmproj)
        assertTrue(h.hasVisionEncoder)
        assertEquals("mmproj", h.type)
        assertEquals("qwen3vl_merger", h.projectorType)
        assertFalse(GgufMetadataReader.read(ByteArrayInputStream(sampleGguf())).isMmproj)
    }
}
