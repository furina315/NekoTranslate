package com.nekohome.translate.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CpuCoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ghz(vararg groups: Pair<Int, Double>): List<Long?> =
        groups.flatMap { (n, f) -> List(n) { (f * 1_000_000).toLong() } }

    @Test
    fun snapdragon8EliteGen5UsesAllEightCores() {
        // 2 prime (Oryon, 4.61 GHz) + 6 performance (3.63 GHz), no efficiency cores.
        val freqs = ghz(6 to 3.63, 2 to 4.61)
        assertEquals(8, CpuCores.performanceCoreCount(freqs))
        assertEquals(8, CpuCores.batchThreads(8, 8))
        // Generation keeps the previous default on this phone (no regression for text).
        assertEquals(6, CpuCores.generationThreads(8, 8))
    }

    @Test
    fun snapdragon8EliteGen1() {
        assertEquals(8, CpuCores.performanceCoreCount(ghz(6 to 3.53, 2 to 4.32)))
    }

    @Test
    fun efficiencyCoresAreExcluded() {
        // Snapdragon 8 Gen 3: 1 x X4 3.3, 3 x A720 3.15, 2 x A720 2.96, 2 x A520 2.27.
        val gen3 = ghz(2 to 2.27, 2 to 2.96, 3 to 3.15, 1 to 3.3)
        assertEquals(6, CpuCores.performanceCoreCount(gen3))
        // Snapdragon 8 Gen 2: 3 x A510 2.0, 4 x A715/A710 2.8, 1 x X3 3.19.
        val gen2 = ghz(3 to 2.0, 4 to 2.8, 1 to 3.19)
        assertEquals(5, CpuCores.performanceCoreCount(gen2))
        assertEquals(5, CpuCores.batchThreads(5, 8))
        assertEquals(5, CpuCores.generationThreads(5, 8))
        // Mid-range 4 + 4 with clearly lower little clocks.
        assertEquals(4, CpuCores.performanceCoreCount(ghz(4 to 1.4, 4 to 2.4)))
    }

    @Test
    fun capacityWinsWhenItDistinguishesCores() {
        // 4 x A55 1.95 GHz + 4 x A78 2.4 GHz: clocks are close, capacities are not.
        val freqs = ghz(4 to 1.95, 4 to 2.4)
        assertEquals(8, CpuCores.performanceCoreCount(freqs))
        val caps = listOf(400L, 400L, 400L, 400L, 1024L, 1024L, 1024L, 1024L)
        assertEquals(4, CpuCores.performanceCoreCount(caps, freqs))
        // 8 Elite Gen 5-like capacities: all eight stay in.
        assertEquals(8, CpuCores.performanceCoreCount(List(6) { 870L } + listOf(1024L, 1024L), ghz(6 to 3.63, 2 to 4.61)))
        // Uniform or missing capacities fall back to frequencies.
        assertEquals(6, CpuCores.performanceCoreCount(List(8) { 1024L }, ghz(2 to 2.27, 2 to 2.96, 3 to 3.15, 1 to 3.3)))
        assertEquals(6, CpuCores.performanceCoreCount(List(8) { null }, ghz(2 to 2.27, 2 to 2.96, 3 to 3.15, 1 to 3.3)))
        assertNull(CpuCores.performanceCoreCount(emptyList(), emptyList()))
    }

    @Test
    fun uniformClusterCountsEveryCore() {
        assertEquals(4, CpuCores.performanceCoreCount(ghz(4 to 2.0)))
    }

    @Test
    fun unknownFrequenciesFallBackToProcessorCount() {
        assertNull(CpuCores.performanceCoreCount(emptyList()))
        assertNull(CpuCores.performanceCoreCount(listOf(null, null, 0L)))
        assertEquals(8, CpuCores.batchThreads(null, 8))
        assertEquals(6, CpuCores.generationThreads(null, 8))
        // Offline cores (unreadable) are not counted.
        assertEquals(2, CpuCores.performanceCoreCount(listOf(null, 3_000_000L, 3_000_000L)))
    }

    @Test
    fun threadCapsAndFloors() {
        assertEquals(8, CpuCores.batchThreads(12, 12))   // capped
        assertEquals(1, CpuCores.batchThreads(null, 1))
        assertEquals(2, CpuCores.generationThreads(null, 2))
        assertEquals(2, CpuCores.generationThreads(2, 8))
        assertEquals(1, CpuCores.generationThreads(1, 1))
    }

    @Test
    fun readsSysfsLayout() {
        val root = tmp.newFolder("cpu")
        fun cpu(i: Int, khz: String?) {
            val d = File(root, "cpu$i").apply { mkdirs() }
            if (khz != null) File(d, "cpufreq").apply { mkdirs() }.resolve("cpuinfo_max_freq").writeText("$khz\n")
        }
        cpu(0, "3628800"); cpu(1, "3628800"); cpu(10, "4608000"); cpu(2, null)
        File(root, "cpu0/cpu_capacity").writeText("870\n")
        File(root, "cpufreq").mkdirs()      // not a cpuN dir
        File(root, "cpuidle").mkdirs()
        val freqs = CpuCores.readMaxFreqsKhz(root)
        assertEquals(listOf(3628800L, 3628800L, null, 4608000L), freqs)
        assertEquals(3, CpuCores.performanceCoreCount(freqs))
        assertEquals(listOf(870L, null, null, null), CpuCores.readCapacities(root))
    }
}
