package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamScalesTest {
    private val t = ParamScales.TEMPERATURE
    private val rp = ParamScales.REPEAT_PENALTY
    private val k = ParamScales.TOP_K_STOPS

    @Test
    fun linearSnapRoundsToGridAndClamps() {
        assertEquals(0.75f, t.snap(0.73f), 1e-6f)
        assertEquals(0.7f, t.snap(0.7f), 1e-6f)
        assertEquals(0.7f, t.snap(0.7124f), 1e-6f)
        assertEquals(0f, t.snap(-1f), 1e-6f)
        assertEquals(2f, t.snap(5f), 1e-6f)
        assertEquals(1.07f, rp.snap(1.0712f), 1e-6f)
        assertEquals(0.95f, ParamScales.TOP_P.snap(0.951f), 1e-6f)
    }

    @Test
    fun snappedValuesHaveNoFloatNoise() {
        // Every grid point should print as a short decimal (no 0.15000001).
        var v = 0f
        repeat(41) {
            val s = t.snap(v)
            assertTrue("$s", s.toString().length <= 4)
            v += 0.05f
        }
    }

    @Test
    fun nudgeStepsAlongGrid() {
        assertEquals(0.75f, t.nudge(0.7f, +1), 1e-6f)
        assertEquals(0.65f, t.nudge(0.7f, -1), 1e-6f)
        assertEquals(2f, t.nudge(2f, +1), 1e-6f)
        assertEquals(0f, t.nudge(0f, -1), 1e-6f)
        assertEquals(1.01f, rp.nudge(1f, +1), 1e-6f)
        assertEquals(1.04f, rp.nudge(1.05f, -1), 1e-6f)
    }

    @Test
    fun nudgeFromOffGridGoesToNeighbour() {
        assertEquals(0.75f, t.nudge(0.72f, +1), 1e-6f)
        assertEquals(0.7f, t.nudge(0.72f, -1), 1e-6f)
        assertEquals(0.75f, t.nudge(0.74f, +1), 1e-6f)
        assertEquals(0.7f, t.nudge(0.74f, -1), 1e-6f)
    }

    @Test
    fun canNudgeAtEnds() {
        assertFalse(t.canNudge(2f, +1))
        assertTrue(t.canNudge(2f, -1))
        assertFalse(t.canNudge(0f, -1))
        assertFalse(rp.canNudge(1f, -1))
        assertTrue(rp.canNudge(1f, +1))
    }

    @Test
    fun topKStops() {
        assertEquals(6, ParamScales.nearestIndex(k, 40))
        assertEquals(6, ParamScales.nearestIndex(k, 45)) // tie 40/50 -> smaller
        assertEquals(10, ParamScales.nearestIndex(k, 500))
        assertEquals(5, ParamScales.optionAt(k, 2.4f))
        assertEquals(10, ParamScales.optionAt(k, 2.6f))
        assertEquals(0, ParamScales.optionAt(k, -3f))
        assertEquals(100, ParamScales.optionAt(k, 99f))
    }

    @Test
    fun stepOptionHandlesOffListValues() {
        assertEquals(50, ParamScales.stepOption(k, 40, +1))
        assertEquals(30, ParamScales.stepOption(k, 40, -1))
        assertEquals(50, ParamScales.stepOption(k, 45, +1))
        assertEquals(40, ParamScales.stepOption(k, 45, -1))
        assertEquals(100, ParamScales.stepOption(k, 100, +1))
        assertEquals(0, ParamScales.stepOption(k, 0, -1))
        assertEquals(100, ParamScales.stepOption(k, 120, -1))
    }

    @Test
    fun contextOptionsRespectTrainingContext() {
        assertEquals(listOf(1024, 2048, 4096), ParamScales.contextOptions(4096L))
        assertEquals(ParamScales.CONTEXT_OPTIONS, ParamScales.contextOptions(null))
        assertEquals(listOf(1024), ParamScales.contextOptions(512L))
    }

    @Test
    fun tokenLabels() {
        assertEquals("512", ParamScales.tokenLabel(512))
        assertEquals("1K", ParamScales.tokenLabel(1024))
        assertEquals("32K", ParamScales.tokenLabel(32768))
        assertEquals("1536", ParamScales.tokenLabel(1536))
    }
}
