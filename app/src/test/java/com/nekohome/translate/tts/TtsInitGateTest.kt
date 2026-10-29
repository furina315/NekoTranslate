package com.nekohome.translate.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsInitGateTest {

    private val gate = TtsInitGate<String>(slowNoticeMs = 1_000, timeoutMs = 15_000, giveUpMs = 60_000)

    @Test
    fun firstRequestStartsTheEngineAndRunsAfterInit() {
        assertEquals(TtsInitGate.Action.StartInit("a"), gate.request("a", 0))
        assertEquals(TtsInitGate.State.INITIALIZING, gate.state)
        assertEquals("a", gate.onInit(success = true))
        assertEquals(TtsInitGate.State.READY, gate.state)
        assertEquals(TtsInitGate.Action.Speak("b"), gate.request("b", 10))
    }

    @Test
    fun requestsWhileStartingAreQueuedNotDroppedAndTheLatestWins() {
        gate.request("a", 0)
        assertEquals(TtsInitGate.Action.Queued("b"), gate.request("b", 500))
        assertEquals("b", gate.onInit(success = true))
    }

    @Test
    fun stopWhileStartingForgetsTheRequestButKeepsTheEngineStarting() {
        gate.request("a", 0)
        gate.cancel()
        assertNull(gate.onInit(success = true))
        assertEquals(TtsInitGate.State.READY, gate.state)
    }

    @Test
    fun failedInitReturnsTheRequestToReportAndRetriesNextTime() {
        gate.request("a", 0)
        assertEquals("a", gate.onInit(success = false))
        assertEquals(TtsInitGate.State.FAILED, gate.state)
        assertTrue(gate.request("b", 100) is TtsInitGate.Action.StartInit)
    }

    @Test
    fun slowEngineGetsLoadingThenTimeoutNoticesOnceThenGivesUp() {
        gate.request("a", 0)
        assertEquals(TtsInitGate.Tick.NONE, gate.onTick(500))
        assertEquals(TtsInitGate.Tick.LOADING, gate.onTick(1_000))
        assertEquals(TtsInitGate.Tick.NONE, gate.onTick(2_000))
        assertEquals(TtsInitGate.Tick.TIMEOUT, gate.onTick(15_000))
        assertEquals(TtsInitGate.Tick.NONE, gate.onTick(20_000))
        // Still queued after the timeout notice: a late onInit speaks it.
        assertEquals("a", gate.pending)
        assertEquals(TtsInitGate.Tick.GIVE_UP, gate.onTick(60_000))
        assertNull(gate.pending)
        assertNull(gate.onInit(success = true))
    }

    @Test
    fun noTicksWithoutAPendingRequestOrOnceReady() {
        assertEquals(TtsInitGate.Tick.NONE, gate.onTick(99_000))
        gate.request("a", 0)
        gate.onInit(true)
        assertEquals(TtsInitGate.Tick.NONE, gate.onTick(99_000))
    }

    @Test
    fun engineSwitchResetsToIdle() {
        gate.request("a", 0)
        gate.onInit(true)
        gate.reset()
        assertEquals(TtsInitGate.State.IDLE, gate.state)
        assertTrue(gate.request("b", 0) is TtsInitGate.Action.StartInit)
    }
}
