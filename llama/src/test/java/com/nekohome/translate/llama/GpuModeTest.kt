package com.nekohome.translate.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuModeTest {

    @Test
    fun nativeCodesMatchJni() {
        // llama_jni.cpp: LT_GPU_OFF = 0, LT_GPU_ENCODER = 1, LT_GPU_ALL = 2
        assertEquals(0, GpuMode.OFF.nativeCode)
        assertEquals(1, GpuMode.ENCODER.nativeCode)
        assertEquals(2, GpuMode.ALL.nativeCode)
    }

    @Test
    fun keysRoundTripAndUnknownIsOff() {
        GpuMode.entries.forEach { assertEquals(it, GpuMode.fromKey(it.key)) }
        assertEquals(GpuMode.OFF, GpuMode.fromKey(null))
        assertEquals(GpuMode.OFF, GpuMode.fromKey("vulkan"))
    }

    @Test
    fun whatRunsWhere() {
        assertFalse(GpuMode.OFF.encoderOnGpu)
        assertFalse(GpuMode.OFF.textOnGpu)
        assertTrue(GpuMode.ENCODER.encoderOnGpu)
        assertFalse(GpuMode.ENCODER.textOnGpu)
        assertTrue(GpuMode.ALL.encoderOnGpu)
        assertTrue(GpuMode.ALL.textOnGpu)
    }

    @Test
    fun probeOnlyQualcommIn64BitProcesses() {
        assertTrue(GpuSupport.worthProbing(true, "QTI", "qcom"))
        assertTrue(GpuSupport.worthProbing(true, "Qualcomm", ""))
        assertFalse(GpuSupport.worthProbing(false, "QTI", "qcom"))       // armeabi-v7a process
        assertFalse(GpuSupport.worthProbing(true, "Mediatek", "mt6989"))
        assertFalse(GpuSupport.worthProbing(true, "Google", "qcom"))     // SoC field wins when known
        // Android 10/11: no SOC_MANUFACTURER, fall back to Build.HARDWARE.
        assertTrue(GpuSupport.worthProbing(true, null, "qcom"))
        assertTrue(GpuSupport.worthProbing(true, "unknown", "qcom"))
        assertFalse(GpuSupport.worthProbing(true, null, "exynos2400"))
    }

    @Test
    fun selfTestKeyChangesWithBuildAndDevice() {
        val k = GpuSupport.selfTestKey("b11827", "QUALCOMM Adreno(TM) 840")
        assertNotEquals(k, GpuSupport.selfTestKey("b11900", "QUALCOMM Adreno(TM) 840"))
        assertNotEquals(k, GpuSupport.selfTestKey("b11827", "QUALCOMM Adreno(TM) 830"))
    }
}
