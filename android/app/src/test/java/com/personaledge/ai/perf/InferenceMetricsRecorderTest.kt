package com.personaledge.ai.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [InferenceMetricsRecorder] using an injected fake monotonic clock, fake
 * collectors, and a capturing sink. No Android APIs are touched.
 */
class InferenceMetricsRecorderTest {

    private val device = DeviceMetadata(
        manufacturer = "test",
        model = "unit",
        androidRelease = "0",
        sdkInt = 31,
        supportedAbis = listOf("arm64-v8a"),
        availableProcessors = 8,
        totalRamBytes = 1L,
        appVersionName = "1.0",
        appVersionCode = 1,
        buildType = "debug",
        litertLmVersion = "0.13.1",
    )

    private class FakeMemoryCollector(private val java: Long = 100, private val native: Long = 200) : MemoryCollector {
        override fun snapshot(label: String, offsetMs: Long) =
            MemorySnapshot(label, offsetMs, javaUsedBytes = java, nativeAllocatedBytes = native)
    }

    private class FakeThermalCollector(private val status: Int, private val supported: Boolean = true) : ThermalCollector {
        override fun snapshot(label: String, offsetMs: Long) =
            ThermalSnapshot(label, offsetMs, statusCode = status, category = "cat$status", supported = supported)
    }

    private class CapturingSink : PerfSink {
        var lastTurn: InferencePerformanceMetrics? = null
        var turnCount = 0
        var lastLoad: ModelLoadMetrics? = null
        override fun onModelLoad(metrics: ModelLoadMetrics) { lastLoad = metrics }
        override fun onTurn(metrics: InferencePerformanceMetrics) { lastTurn = metrics; turnCount++ }
    }

    private class Clock(var nanos: Long = 0) { fun get(): Long = nanos }

    private fun recorder(
        clock: Clock,
        mem: MemoryCollector? = FakeMemoryCollector(),
        thermal: ThermalCollector? = null,
        sink: PerfSink = NoOpPerfSink,
    ) = InferenceMetricsRecorder(
        localTurnId = 1,
        sessionId = "session-uuid",
        device = device,
        modelId = "gemma3-1b-it",
        modelFileName = "gemma3-1b-it-int4.litertlm",
        modelFileSizeBytes = 584_417_280L,
        clockNanos = clock::get,
        memoryCollector = mem,
        thermalCollector = thermal,
        peakSampler = null,
        sink = sink,
    )

    // Test 1 + timing: durations are computed from the injected monotonic clock deltas.
    @Test
    fun durations_useMonotonicClockDeltas() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000 // 1ms
        rec.markGenerationStart()
        clock.nanos = 3_000_000 // 3ms — first non-empty output
        rec.onStreamEvent("Hello")
        clock.nanos = 10_000_000 // 10ms
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertEquals(2L, m.generationRequestToFirstOutputMs) // 3 - 1
        assertEquals(7L, m.decodeActiveMs)                   // 10 - 3
        assertEquals(9L, m.totalGenerationMs)                // 10 - 1
    }

    // Test 2/3: first output is recorded once and only for a non-empty fragment.
    @Test
    fun firstOutput_recordedOnceAndNotForEmptyFragment() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("") // empty — must NOT count as first output
        clock.nanos = 5_000_000
        rec.onStreamEvent("first")
        clock.nanos = 8_000_000
        rec.onStreamEvent("second")
        clock.nanos = 9_000_000
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertEquals(4L, m.generationRequestToFirstOutputMs) // 5 - 1, not 2 - 1
        assertEquals(3, m.streamEventCount)                  // "", "first", "second"
        assertEquals("firstsecond".length, m.outputCharacterCount)
    }

    // Test 8/10/11: stream events are not tokens; exact token metrics absent; estimates labelled.
    @Test
    fun tokenMetrics_streamEventsAreNotTokens_exactAbsent_estimatesLabelled() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("abcd") // 4 chars, ~1 token
        clock.nanos = 1_002_000_000 // +1000ms after first output
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertEquals(1, m.streamEventCount)
        assertEquals(4, m.outputCharacterCount)
        assertNull("exact token count must be unavailable", m.exactOutputTokenCount)
        assertNull("exact decode tok/s must be unavailable", m.exactDecodeTokensPerSecond)
        assertEquals(TokenCountType.ESTIMATED, m.tokenCountType)
        assertNotNull(m.estimatedOutputTokenCount)
        assertNotNull(m.estimatedDecodeTokensPerSecond)
    }

    // Test 9: derived rates avoid division by zero when there is no decode window.
    @Test
    fun derivedRates_nullWhenNoFirstOutput() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        rec.onStreamEvent("") // only empty fragments -> no first output
        clock.nanos = 5_000_000
        val m = rec.finish(CompletionStatus.BLANK)!!
        assertEquals(0L, m.decodeActiveMs)
        assertNull(m.outputCharactersPerSecond)
        assertNull(m.estimatedDecodeTokensPerSecond)
        assertNull(m.streamEventsPerSecond)
        assertEquals(TokenCountType.UNAVAILABLE, m.tokenCountType)
    }

    // Test 4/5/6: exactly one record is finalized, regardless of extra finish() calls.
    @Test
    fun finish_finalizesExactlyOnce() {
        val clock = Clock(0)
        val sink = CapturingSink()
        val rec = recorder(clock, sink = sink)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("x")
        clock.nanos = 3_000_000
        val first = rec.finish(CompletionStatus.SUCCESS)
        val second = rec.finish(CompletionStatus.ERROR)
        assertNotNull(first)
        assertNull(second)
        assertEquals(1, sink.turnCount)
        assertEquals(CompletionStatus.SUCCESS, sink.lastTurn!!.completionStatus)
    }

    @Test
    fun error_finalizesOneRecordWithCategory() {
        val clock = Clock(0)
        val sink = CapturingSink()
        val rec = recorder(clock, sink = sink)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        val m = rec.finish(CompletionStatus.ERROR, ErrorCategory.GENERATION_FAILURE)!!
        assertEquals(CompletionStatus.ERROR, m.completionStatus)
        assertEquals(ErrorCategory.GENERATION_FAILURE, m.errorCategory)
        assertEquals(1, sink.turnCount)
    }

    // Test 6/7: cancellation is finalized once and latency is measured from request to finish.
    @Test
    fun cancellation_recordsLatencyFromRequestToFinish() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("partial")
        clock.nanos = 5_000_000
        rec.markCancellationRequested()
        clock.nanos = 6_500_000
        val m = rec.finish(CompletionStatus.CANCELLED, ErrorCategory.CANCELLED)!!
        assertTrue(m.cancellationRequested)
        assertEquals(1L, m.cancellationLatencyMs) // 6.5ms - 5ms = 1.5ms -> 1ms floor
        assertEquals(CompletionStatus.CANCELLED, m.completionStatus)
    }

    @Test
    fun cancellationLatency_negativeWhenNotRequested() {
        val clock = Clock(0)
        val rec = recorder(clock)
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("x")
        clock.nanos = 3_000_000
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertFalse(m.cancellationRequested)
        assertEquals(-1L, m.cancellationLatencyMs)
    }

    // Test 13: backend selection (including fallback) round-trips into the record.
    @Test
    fun backendSelection_includingFallback_isRecorded() {
        val clock = Clock(0)
        val rec = recorder(clock)
        rec.setBackendSelection(
            BackendSelection(PerfBackend.GPU, PerfBackend.GPU, PerfBackend.CPU, true, "gpu_init_failed"),
        )
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("x")
        clock.nanos = 3_000_000
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertEquals(PerfBackend.GPU, m.backend.requested)
        assertEquals(PerfBackend.CPU, m.backend.selected)
        assertTrue(m.backend.fallbackOccurred)
        assertEquals("gpu_init_failed", m.backend.fallbackReason)
    }

    // Test 15: unsupported thermal fails safe (no crash, no false "changed").
    @Test
    fun thermal_unsupportedIsSafeAndNotFlaggedAsChanged() {
        val clock = Clock(0)
        val rec = recorder(clock, thermal = FakeThermalCollector(status = -1, supported = false))
        clock.nanos = 1_000_000
        rec.markGenerationStart()
        clock.nanos = 2_000_000
        rec.onStreamEvent("x")
        clock.nanos = 3_000_000
        val m = rec.finish(CompletionStatus.SUCCESS)!!
        assertFalse(m.thermalStatusChangedDuringGeneration)
    }

    @Test
    fun thermal_changeBetweenStartAndFinishIsDetected() {
        val clock = Clock(0)
        // Collector returns an increasing status each call, so before != after.
        val thermal = object : ThermalCollector {
            var n = 0
            override fun snapshot(label: String, offsetMs: Long): ThermalSnapshot {
                val s = n++
                return ThermalSnapshot(label, offsetMs, statusCode = s, category = "c$s", supported = true)
            }
        }
        val rec = recorder(clock, thermal = thermal)
        clock.nanos = 1_000_000
        rec.markGenerationStart()  // captures status 0
        clock.nanos = 2_000_000
        rec.onStreamEvent("x")     // captures status 1 (firstOutput)
        clock.nanos = 3_000_000
        val m = rec.finish(CompletionStatus.SUCCESS)!! // captures status 2 (afterGeneration)
        assertTrue(m.thermalStatusChangedDuringGeneration)
    }

    // Test 12: the metrics record has no field capable of holding raw prompt/response content.
    @Test
    fun metricsRecord_hasNoRawContentFields() {
        val forbidden = setOf(
            "prompt", "response", "answer", "memory", "memoryPacket", "systemPrompt",
            "personalityRules", "apiKey", "rawPrompt", "promptText", "responseText",
            "content", "chunk", "chunks", "userMessage", "assistantMessage", "text",
        )
        val names = InferencePerformanceMetrics::class.members.map { it.name }
        val offending = names.filter { it in forbidden }
        assertTrue("Unexpected content-bearing field(s): $offending", offending.isEmpty())
    }
}
