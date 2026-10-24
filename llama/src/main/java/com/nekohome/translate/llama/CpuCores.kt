package com.nekohome.translate.llama

import java.io.File

/**
 * Thread counts for llama.cpp derived from the CPU topology.
 *
 * Phones mix core types; a ggml compute graph is split evenly across its threads and every node
 * waits for the slowest one, so putting a thread on an efficiency core slows the whole pass down.
 * Cores are told apart by the kernel's `cpu_capacity` (scheduler capacity, microarchitecture x
 * clock, 0..1024) when it distinguishes them, else by `cpufreq/cpuinfo_max_freq`.
 */
object CpuCores {

    /** Cores at or above this fraction of the fastest core's max frequency count as performance cores. */
    const val PERFORMANCE_FRACTION = 0.75

    /** Same for `cpu_capacity`: little in-order cores (A55 / A510 / A520) sit at roughly 0.2–0.45. */
    const val CAPACITY_FRACTION = 0.5

    /** Upper bound on threads for compute-bound work (prompt prefill, image encode). */
    const val MAX_BATCH_THREADS = 8

    /**
     * Number of performance cores given each core's max frequency (kHz; null / <= 0 = unknown).
     * Returns null when no frequency is known (caller falls back to the processor count).
     *
     * Examples: Snapdragon 8 Elite Gen 5 (2 x 4.61 GHz + 6 x 3.63 GHz) -> 8;
     * Snapdragon 8 Gen 3 (1 x 3.3 + 3 x 3.15 + 2 x 2.96 + 2 x 2.27 GHz) -> 6;
     * Snapdragon 8 Gen 2 (1 x 3.19 + 4 x 2.8 + 3 x 2.0 GHz) -> 5.
     */
    fun performanceCoreCount(maxFreqsKhz: List<Long?>, fraction: Double = PERFORMANCE_FRACTION): Int? {
        val known = maxFreqsKhz.filterNotNull().filter { it > 0 }
        if (known.isEmpty()) return null
        val threshold = known.max() * fraction
        // Cores with an unknown value (offline / unreadable) are not counted.
        return known.count { it >= threshold }
    }

    /**
     * Performance cores from `cpu_capacity` when it tells the cores apart (two or more distinct
     * values; some kernels report 1024 everywhere), else from max frequencies.
     * E.g. 4 x A78 2.4 GHz + 4 x A55 1.95 GHz: frequencies alone say 8, capacities (1024 / ~400) say 4.
     */
    fun performanceCoreCount(capacities: List<Long?>, maxFreqsKhz: List<Long?>): Int? {
        val caps = capacities.filterNotNull().filter { it > 0 }
        if (caps.distinct().size >= 2) return performanceCoreCount(capacities, CAPACITY_FRACTION)
        return performanceCoreCount(maxFreqsKhz, PERFORMANCE_FRACTION)
    }

    /**
     * Threads for prompt prefill and image encoding (compute bound, scales with cores):
     * the performance cores, capped at [MAX_BATCH_THREADS]. Falls back to the processor count.
     */
    fun batchThreads(performanceCores: Int?, processors: Int): Int =
        (performanceCores ?: processors).coerceIn(1, MAX_BATCH_THREADS)

    /**
     * Threads for token generation (memory-bandwidth bound): the previous default (processors - 2,
     * kept within 2..6 so the UI keeps a core) but never more than the performance cores.
     */
    fun generationThreads(performanceCores: Int?, processors: Int): Int {
        val legacy = (processors - 2).coerceIn(2, 6)
        return if (performanceCores == null) legacy else legacy.coerceAtMost(performanceCores).coerceAtLeast(1)
    }

    /** Max frequency (kHz) of each possible CPU, null where unreadable. */
    fun readMaxFreqsKhz(root: File = SYS_CPU): List<Long?> = readPerCpu(root, "cpufreq/cpuinfo_max_freq")

    /** Scheduler capacity (0..1024) of each possible CPU, null where unreadable. */
    fun readCapacities(root: File = SYS_CPU): List<Long?> = readPerCpu(root, "cpu_capacity")

    private fun readPerCpu(root: File, file: String): List<Long?> {
        val cpus = root.listFiles { f -> f.isDirectory && CPU_DIR.matches(f.name) }
            ?.sortedBy { it.name.removePrefix("cpu").toInt() }
            ?: return emptyList()
        return cpus.map { dir -> runCatching { File(dir, file).readText().trim().toLong() }.getOrNull() }
    }

    private val SYS_CPU = File("/sys/devices/system/cpu")

    private val CPU_DIR = Regex("cpu[0-9]+")

    /** Performance core count of this device, read once. */
    val performanceCores: Int? by lazy {
        runCatching { performanceCoreCount(readCapacities(), readMaxFreqsKhz()) }.getOrNull()
    }

    private val processors: Int get() = Runtime.getRuntime().availableProcessors()

    fun defaultBatchThreads(): Int = batchThreads(performanceCores, processors)
    fun defaultGenerationThreads(): Int = generationThreads(performanceCores, processors)
}
