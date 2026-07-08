package com.personaledge.ai.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the safe derived-rate calculations. */
class PerfCalcTest {

    @Test
    fun nanosToMs_convertsAndClampsNegative() {
        assertEquals(0L, PerfCalc.nanosToMs(0))
        assertEquals(0L, PerfCalc.nanosToMs(-5))
        assertEquals(1L, PerfCalc.nanosToMs(1_000_000))
        assertEquals(7L, PerfCalc.nanosToMs(7_500_000))
    }

    @Test
    fun ratePerSecond_returnsNullOnZeroOrNegativeDuration() {
        assertNull(PerfCalc.ratePerSecond(10, 0))
        assertNull(PerfCalc.ratePerSecond(10, -1))
        assertNull(PerfCalc.ratePerSecond(0, 100))
    }

    @Test
    fun ratePerSecond_computesCorrectly() {
        // 50 units over 1000ms = 50/sec
        assertEquals(50.0, PerfCalc.ratePerSecond(50, 1000)!!, 0.0001)
        // 11 chars over 500ms = 22/sec
        assertEquals(22.0, PerfCalc.ratePerSecond(11, 500)!!, 0.0001)
    }

    @Test
    fun estimateTokens_isCharsOverFour() {
        assertEquals(0, PerfCalc.estimateTokens(0))
        assertEquals(1, PerfCalc.estimateTokens(4))
        assertEquals(3, PerfCalc.estimateTokens(11)) // 11/4 = 2.75 -> 3
    }

    @Test
    fun estimatedDecodeTokensPerSecond_nullWhenNoDuration() {
        assertNull(PerfCalc.estimatedDecodeTokensPerSecond(100, 0))
    }

    @Test
    fun estimatedDecodeTokensPerSecond_positiveWhenValid() {
        // 40 chars -> ~10 tokens over 1000ms -> 10/sec
        val rate = PerfCalc.estimatedDecodeTokensPerSecond(40, 1000)!!
        assertTrue(rate > 0)
        assertEquals(10.0, rate, 0.0001)
    }
}
