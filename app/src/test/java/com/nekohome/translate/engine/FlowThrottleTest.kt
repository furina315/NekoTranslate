package com.nekohome.translate.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowThrottleTest {

    @Test
    fun burstIsCollapsedButFirstAndLastSurvive() = runBlocking {
        val out = flow { for (i in 1..500) emit(i) }.throttleLatest(50).toList()
        assertEquals(1, out.first())
        assertEquals(500, out.last())
        assertTrue("expected heavy conflation, got ${out.size} values", out.size <= 5)
        assertEquals(out.sorted(), out) // order preserved
    }

    @Test
    fun slowUpstreamPassesEverything() = runBlocking {
        val out = flow {
            for (i in 1..5) {
                emit(i)
                delay(40)
            }
        }.throttleLatest(5).toList()
        assertEquals(listOf(1, 2, 3, 4, 5), out)
    }

    @Test
    fun zeroPeriodStillDeliversLast() = runBlocking {
        val out = flow { for (i in 1..100) emit(i) }.throttleLatest(0).toList()
        assertEquals(100, out.last())
    }

    @Test
    fun emptyFlowStaysEmpty() = runBlocking {
        assertEquals(emptyList<Int>(), flow<Int> { }.throttleLatest(30).toList())
    }
}
