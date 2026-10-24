package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuGuardTest {

    private class MapKv : GpuKeyValue {
        val map = HashMap<String, Any>()
        override fun getString(key: String) = map[key] as String?
        override fun getBoolean(key: String) = map[key] as Boolean? ?: false
        override fun put(key: String, value: Any?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val dev = "QUALCOMM Adreno(TM) 840"

    @Test
    fun noGpuMeansUnsupported() {
        val s = GpuGuard(MapKv(), "b1").state("")
        assertFalse(s.supported)
        assertFalse(s.usable)
    }

    @Test
    fun unverifiedUntilSelfTestPasses() {
        val kv = MapKv()
        val g = GpuGuard(kv, "b1")
        val before = g.state(dev)
        assertTrue(before.supported)
        assertFalse(before.verified)
        assertFalse(before.usable)
        g.recordSelfTest(dev, "")
        val after = g.state(dev)
        assertTrue(after.verified)
        assertTrue(after.usable)
        assertNull(after.failure)
        // Persisted: a new guard over the same store (next app start) still knows.
        assertTrue(GpuGuard(kv, "b1").state(dev).usable)
    }

    @Test
    fun failedSelfTestDisablesWithReason() {
        val g = GpuGuard(MapKv(), "b1")
        g.recordSelfTest(dev, "q4_K x 33: nmse 0.9")
        val s = g.state(dev)
        assertEquals("q4_K x 33: nmse 0.9", s.failure)
        assertFalse(s.usable)
        g.clear()
        assertNull(g.state(dev).failure)
    }

    @Test
    fun newLlamaBuildOrGpuNeedsANewCheck() {
        val kv = MapKv()
        GpuGuard(kv, "b1").recordSelfTest(dev, "")
        assertFalse(GpuGuard(kv, "b2").state(dev).verified)
        assertFalse(GpuGuard(kv, "b1").state("Adreno 830").verified)
    }

    @Test
    fun crashDuringGpuInitDisablesOnNextStart() {
        val kv = MapKv()
        val g = GpuGuard(kv, "b1")
        g.recordSelfTest(dev, "")
        g.begin() // process dies here
        val next = GpuGuard(kv, "b1").state(dev)
        assertEquals(GpuGuard.CRASH, next.failure)
        assertFalse(next.usable)
        assertFalse(kv.getBoolean("gpu_init_pending"))
    }

    @Test
    fun guardedClearsTheMarkEvenOnException() {
        val kv = MapKv()
        val g = GpuGuard(kv, "b1")
        var seen = false
        runCatching { g.guarded(true) { seen = kv.getBoolean("gpu_init_pending"); error("boom") } }
        assertTrue(seen)
        assertFalse(kv.getBoolean("gpu_init_pending"))
        g.guarded(false) { assertFalse(kv.getBoolean("gpu_init_pending")) }
        g.recordSelfTest(dev, "")
        assertTrue(g.state(dev).usable)
    }

    @Test
    fun probeThatNeverReturnedIsSkippedUntilTheNextBuild() {
        val kv = MapKv()
        assertEquals(dev, GpuGuard(kv, "b1").probe { dev })
        assertFalse(kv.getBoolean("gpu_probe_pending"))
        // Simulate a crash inside the probe: the mark stays set.
        kv.put("gpu_probe_pending", true)
        var probed = false
        assertEquals("", GpuGuard(kv, "b1").probe { probed = true; dev })
        assertFalse(probed)
        assertEquals("", GpuGuard(kv, "b1").probe { probed = true; dev })
        assertFalse(probed)
        // An app update (new llama.cpp build) tries again.
        assertEquals(dev, GpuGuard(kv, "b2").probe { dev })
    }
}
