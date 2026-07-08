package com.personaledge.ai.perf

import android.content.Context
import android.util.Log

/**
 * Structured Logcat sink under a stable tag. Emits ONE concise summary line per model-load and per
 * turn. Only counts/durations/categories are logged — never raw prompts, responses, memory chunks,
 * user data, API keys, or model filesystem paths.
 */
class LogcatPerfSink : PerfSink {
    override fun onModelLoad(metrics: ModelLoadMetrics) {
        Log.i(
            PerfLog.TAG,
            "modelLoad model=${metrics.modelId} status=${metrics.completionStatus} " +
                "requested=${metrics.backend.requested} attempted=${metrics.backend.attempted} " +
                "selected=${metrics.backend.selected} fallback=${metrics.backend.fallbackOccurred} " +
                "reason=${metrics.backend.fallbackReason ?: "-"} " +
                "prepMs=${metrics.modelPreparationMs} engineCreateMs=${metrics.engineCreationMs} " +
                "engineInitMs=${metrics.engineInitializationMs} convCreateMs=${metrics.conversationCreationMs} " +
                "totalMs=${metrics.totalLoadMs} sizeB=${metrics.modelFileSizeBytes} " +
                "javaUsedB=${lastJavaUsed(metrics.memorySnapshots)} nativeB=${lastNativeAllocated(metrics.memorySnapshots)} " +
                "thermal=${lastThermal(metrics.thermalSnapshots)}",
        )
    }

    override fun onTurn(metrics: InferencePerformanceMetrics) {
        Log.i(
            PerfLog.TAG,
            "turn=${metrics.localTurnId} status=${metrics.completionStatus} err=${metrics.errorCategory} " +
                "selected=${metrics.backend.selected} fallback=${metrics.backend.fallbackOccurred} " +
                "prefetch=${metrics.backendPrefetchOutcome}/${metrics.backendPrefetchMs}ms chunks=${metrics.prefetchChunkCount} " +
                "ctxChars=${metrics.prefetchContextCharacterCount} offlineFallbackMs=${metrics.offlineFallbackMs} " +
                "promptBuildMs=${metrics.promptBuildMs} promptChars=${metrics.promptCharacterCount} " +
                "convCreateMs=${metrics.conversationCreationMs} " +
                "ttFirstOutputMs=${metrics.generationRequestToFirstOutputMs} decodeActiveMs=${metrics.decodeActiveMs} " +
                "totalGenMs=${metrics.totalGenerationMs} memorySyncMs=${metrics.memorySyncMs} " +
                "streamEvents=${metrics.streamEventCount} outChars=${metrics.outputCharacterCount} " +
                "estTokens=${metrics.estimatedOutputTokenCount ?: -1}(${metrics.tokenCountType}) " +
                "estDecodeTps=${fmt(metrics.estimatedDecodeTokensPerSecond)} " +
                "charsPerSec=${fmt(metrics.outputCharactersPerSecond)} eventsPerSec=${fmt(metrics.streamEventsPerSecond)} " +
                "cancelReq=${metrics.cancellationRequested} cancelLatencyMs=${metrics.cancellationLatencyMs} " +
                "peakJavaB=${metrics.sampledPeakJavaUsedBytes} peakNativeB=${metrics.sampledPeakNativeAllocatedBytes} " +
                "thermalChanged=${metrics.thermalStatusChangedDuringGeneration} " +
                "device=${metrics.device.manufacturer}/${metrics.device.model} sdk=${metrics.device.sdkInt} " +
                "litertlm=${metrics.device.litertLmVersion} build=${metrics.device.buildType}",
        )
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format("%.2f", v)
    private fun lastJavaUsed(snaps: List<MemorySnapshot>): Long = snaps.lastOrNull()?.javaUsedBytes ?: -1
    private fun lastNativeAllocated(snaps: List<MemorySnapshot>): Long = snaps.lastOrNull()?.nativeAllocatedBytes ?: -1
    private fun lastThermal(snaps: List<ThermalSnapshot>): String = snaps.lastOrNull()?.category ?: "unknown"
}

/**
 * Process-wide wiring for Stage 0 instrumentation. Lightweight timing is always collected; detailed
 * memory/thermal sampling is gated by [PerfConstants.detailedSamplingEnabled] (debug-only) so release
 * behavior is not meaningfully slowed. Exposes [store] for tests and future debug tooling.
 */
object PerfLog {
    const val TAG = "CoffeeAIPerf"

    val store = InMemoryPerfStore()

    @Volatile private var device: DeviceMetadata? = null
    @Volatile private var memoryCollector: MemoryCollector? = null
    @Volatile private var thermalCollector: ThermalCollector? = null
    private val sink: PerfSink = CompositePerfSink(listOf(LogcatPerfSink(), store))

    /** Monotonic clock. Durations must use this, never wall-clock time. */
    fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()

    private fun ensureInit(context: Context) {
        if (device == null) {
            synchronized(this) {
                if (device == null) {
                    device = DeviceMetadataProvider.collect(context)
                    memoryCollector = AndroidMemoryCollector(context)
                    thermalCollector = AndroidThermalCollector(context)
                }
            }
        }
    }

    fun device(context: Context): DeviceMetadata {
        ensureInit(context)
        return device!!
    }

    fun newModelLoadRecorder(
        context: Context,
        modelId: String,
        modelFileName: String,
        modelFileSizeBytes: Long,
        requestedBackend: PerfBackend,
    ): ModelLoadRecorder {
        ensureInit(context)
        return ModelLoadRecorder(
            modelId = modelId,
            modelFileName = modelFileName,
            modelFileSizeBytes = modelFileSizeBytes,
            device = device!!,
            requestedBackend = requestedBackend,
            clockNanos = ::nowNanos,
            memoryCollector = memoryCollector,
            thermalCollector = thermalCollector,
            sink = sink,
        )
    }

    fun newTurnRecorder(
        context: Context,
        localTurnId: Long,
        sessionId: String?,
        modelId: String,
        modelFileName: String,
        modelFileSizeBytes: Long,
    ): InferenceMetricsRecorder {
        ensureInit(context)
        val sampler = if (PerfConstants.detailedSamplingEnabled) {
            memoryCollector?.let { CoroutinePeakSampler(it) } ?: NoOpPeakSampler
        } else {
            NoOpPeakSampler
        }
        return InferenceMetricsRecorder(
            localTurnId = localTurnId,
            sessionId = sessionId,
            device = device!!,
            modelId = modelId,
            modelFileName = modelFileName,
            modelFileSizeBytes = modelFileSizeBytes,
            clockNanos = ::nowNanos,
            memoryCollector = memoryCollector,
            thermalCollector = thermalCollector,
            peakSampler = sampler,
            sink = sink,
        )
    }
}
