package com.personaledge.ai.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test 16: the memory sampler tracks a peak and stops cleanly. Uses a fake, Android-free collector. */
class PeakSamplerTest {

    private class ConstantCollector(private val java: Long, private val native: Long) : MemoryCollector {
        override fun snapshot(label: String, offsetMs: Long) =
            MemorySnapshot(label, offsetMs, javaUsedBytes = java, nativeAllocatedBytes = native)
    }

    @Test
    fun noOpSampler_returnsUnknownPeaks() {
        assertEquals(-1L to -1L, NoOpPeakSampler.stop())
    }

    @Test
    fun coroutineSampler_stopWithoutStart_returnsUnknownPeaks() {
        val sampler = CoroutinePeakSampler(ConstantCollector(100, 200), intervalMs = 10)
        assertEquals(-1L to -1L, sampler.stop())
    }

    @Test
    fun coroutineSampler_tracksPeakAndStopsCleanly() {
        val sampler = CoroutinePeakSampler(ConstantCollector(1_000, 2_000), intervalMs = 5)
        sampler.start()
        Thread.sleep(60) // allow several sampling ticks
        val (java, native) = sampler.stop()
        assertEquals(1_000L, java)
        assertEquals(2_000L, native)
        // Stopping again is safe and idempotent.
        sampler.stop()
    }
}
