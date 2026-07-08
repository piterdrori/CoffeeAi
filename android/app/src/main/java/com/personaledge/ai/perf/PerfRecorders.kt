package com.personaledge.ai.perf

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Destination for finalized metrics. Kept as an interface so the recorders remain pure/JVM-testable
 * (tests use a capturing fake; production uses a Logcat sink + in-memory store).
 */
interface PerfSink {
    fun onModelLoad(metrics: ModelLoadMetrics)
    fun onTurn(metrics: InferencePerformanceMetrics)
}

/** No-op sink (safe default). */
object NoOpPerfSink : PerfSink {
    override fun onModelLoad(metrics: ModelLoadMetrics) {}
    override fun onTurn(metrics: InferencePerformanceMetrics) {}
}

/**
 * Thread-safe bounded ring of the most recent metrics, for tests and (future) debug tooling.
 * Holds only the privacy-safe metric records — never raw content.
 */
class InMemoryPerfStore(private val capacity: Int = 50) : PerfSink {
    private val lock = Any()
    private val turns = ArrayDeque<InferencePerformanceMetrics>()
    private val loads = ArrayDeque<ModelLoadMetrics>()

    override fun onModelLoad(metrics: ModelLoadMetrics) = synchronized(lock) {
        loads.addLast(metrics)
        while (loads.size > capacity) loads.removeFirst()
    }

    override fun onTurn(metrics: InferencePerformanceMetrics) = synchronized(lock) {
        turns.addLast(metrics)
        while (turns.size > capacity) turns.removeFirst()
    }

    fun recentTurns(): List<InferencePerformanceMetrics> = synchronized(lock) { turns.toList() }
    fun recentLoads(): List<ModelLoadMetrics> = synchronized(lock) { loads.toList() }
    fun lastTurn(): InferencePerformanceMetrics? = synchronized(lock) { turns.lastOrNull() }
    fun clear() = synchronized(lock) { turns.clear(); loads.clear() }
}

/** Fans a metric out to several sinks. */
class CompositePerfSink(private val sinks: List<PerfSink>) : PerfSink {
    override fun onModelLoad(metrics: ModelLoadMetrics) = sinks.forEach { it.onModelLoad(metrics) }
    override fun onTurn(metrics: InferencePerformanceMetrics) = sinks.forEach { it.onTurn(metrics) }
}

/**
 * Records one model-load event. Durations use the injected monotonic [clockNanos]. Not reused across
 * loads. All phase durations default to -1 (not measured) until explicitly set.
 */
class ModelLoadRecorder(
    private val modelId: String,
    private val modelFileName: String,
    private val modelFileSizeBytes: Long,
    private val device: DeviceMetadata,
    private val requestedBackend: PerfBackend,
    private val clockNanos: () -> Long,
    private val memoryCollector: MemoryCollector? = null,
    private val thermalCollector: ThermalCollector? = null,
    private val sink: PerfSink = NoOpPerfSink,
) {
    private val startNanos = clockNanos()
    private val finished = AtomicBoolean(false)
    private val lock = Any()
    private val memory = ArrayList<MemorySnapshot>()
    private val thermal = ArrayList<ThermalSnapshot>()

    var modelPreparationMs: Long = -1
    var engineCreationMs: Long = -1
    var engineInitializationMs: Long = -1
    var conversationCreationMs: Long = -1
    private var backend: BackendSelection =
        BackendSelection(requestedBackend, PerfBackend.UNKNOWN, PerfBackend.UNKNOWN, false)

    private fun offsetMs(): Long = PerfCalc.nanosToMs(clockNanos() - startNanos)

    fun captureMemory(label: String) {
        val snap = memoryCollector?.snapshot(label, offsetMs()) ?: return
        synchronized(lock) { memory.add(snap) }
    }

    fun captureThermal(label: String) {
        val snap = thermalCollector?.snapshot(label, offsetMs()) ?: return
        synchronized(lock) { thermal.add(snap) }
    }

    fun setBackendSelection(selection: BackendSelection) { backend = selection }

    fun finish(status: CompletionStatus, errorCategory: ErrorCategory = ErrorCategory.NONE): ModelLoadMetrics? {
        if (!finished.compareAndSet(false, true)) return null
        val total = offsetMs()
        val metrics = ModelLoadMetrics(
            modelId = modelId,
            modelFileName = modelFileName,
            modelFileSizeBytes = modelFileSizeBytes,
            device = device,
            backend = backend,
            modelPreparationMs = modelPreparationMs,
            engineCreationMs = engineCreationMs,
            engineInitializationMs = engineInitializationMs,
            conversationCreationMs = conversationCreationMs,
            totalLoadMs = total,
            completionStatus = status,
            errorCategory = errorCategory,
            memorySnapshots = synchronized(lock) { memory.toList() },
            thermalSnapshots = synchronized(lock) { thermal.toList() },
        )
        sink.onModelLoad(metrics)
        return metrics
    }
}

/**
 * Records exactly one generation turn. Timers use the injected monotonic [clockNanos].
 *
 * Thread-safety: stream events arrive on the collector coroutine; cancellation/finish may be invoked
 * from a watchdog coroutine. [finished] guarantees a single finalized record, and the snapshot lists
 * are guarded. First-output time is recorded exactly once and only for a non-empty fragment.
 */
class InferenceMetricsRecorder(
    private val localTurnId: Long,
    private val sessionId: String?,
    private val device: DeviceMetadata,
    private val modelId: String,
    private val modelFileName: String,
    private val modelFileSizeBytes: Long,
    private val clockNanos: () -> Long,
    private val memoryCollector: MemoryCollector? = null,
    private val thermalCollector: ThermalCollector? = null,
    private val peakSampler: PeakSampler? = null,
    private val sink: PerfSink = NoOpPerfSink,
) {
    private val startNanos = clockNanos()
    private val finished = AtomicBoolean(false)
    private val lock = Any()
    private val memory = ArrayList<MemorySnapshot>()
    private val thermal = ArrayList<ThermalSnapshot>()

    private var backend = BackendSelection(PerfBackend.UNKNOWN, PerfBackend.UNKNOWN, PerfBackend.UNKNOWN, false)

    private var backendPrefetchMs: Long = -1
    private var prefetchOutcome: PrefetchOutcome = PrefetchOutcome.NOT_ATTEMPTED
    private var prefetchChunkCount: Int = 0
    private var prefetchContextChars: Int = 0
    private var prefetchPacketBytes: Long = -1
    private var offlineFallbackMs: Long = -1

    private var promptBuildMs: Long = -1
    private var promptChars: Int = 0
    private var conversationCreationMs: Long = -1

    private var memorySyncMs: Long = -1

    @Volatile private var genStartNanos: Long = -1
    @Volatile private var firstOutputNanos: Long = -1
    @Volatile private var streamEventCount: Int = 0
    @Volatile private var outputCharacterCount: Int = 0

    @Volatile private var cancellationRequested: Boolean = false
    @Volatile private var cancelReqNanos: Long = -1

    private var thermalBeforeGeneration: ThermalSnapshot? = null

    private fun offsetMs(): Long = PerfCalc.nanosToMs(clockNanos() - startNanos)

    fun captureMemory(label: String) {
        val snap = memoryCollector?.snapshot(label, offsetMs()) ?: return
        synchronized(lock) { memory.add(snap) }
    }

    fun captureThermal(label: String): ThermalSnapshot? {
        val snap = thermalCollector?.snapshot(label, offsetMs()) ?: return null
        synchronized(lock) { thermal.add(snap) }
        return snap
    }

    fun setBackendSelection(selection: BackendSelection) { backend = selection }

    fun setPrefetch(outcome: PrefetchOutcome, latencyMs: Long, chunkCount: Int, contextChars: Int, packetBytes: Long = -1) {
        prefetchOutcome = outcome
        backendPrefetchMs = latencyMs
        prefetchChunkCount = chunkCount
        prefetchContextChars = contextChars
        prefetchPacketBytes = packetBytes
    }

    fun setOfflineFallback(latencyMs: Long) { offlineFallbackMs = latencyMs }

    fun setPromptBuild(ms: Long, promptCharacterCount: Int) {
        promptBuildMs = ms
        promptChars = promptCharacterCount
    }

    fun setConversationCreation(ms: Long) { conversationCreationMs = ms }

    fun setMemorySync(ms: Long) { memorySyncMs = ms }

    /** Marks the moment just before submitting the generation request. */
    fun markGenerationStart() {
        genStartNanos = clockNanos()
        captureMemory("beforeGeneration")
        thermalBeforeGeneration = captureThermal("beforeGeneration")
        peakSampler?.start()
    }

    /** Called for every native stream callback. Only a non-empty fragment marks first output. */
    fun onStreamEvent(fragment: String) {
        streamEventCount += 1
        outputCharacterCount += fragment.length
        if (firstOutputNanos < 0 && fragment.isNotEmpty()) {
            firstOutputNanos = clockNanos()
            captureMemory("firstOutput")
            captureThermal("firstOutput")
        }
    }

    fun markCancellationRequested() {
        if (!cancellationRequested) {
            cancellationRequested = true
            cancelReqNanos = clockNanos()
        }
    }

    /** Builds and emits exactly one record. Subsequent calls are ignored (returns null). */
    fun finish(status: CompletionStatus, errorCategory: ErrorCategory = ErrorCategory.NONE): InferencePerformanceMetrics? {
        if (!finished.compareAndSet(false, true)) return null
        val endNanos = clockNanos()
        val peaks = peakSampler?.stop() ?: (-1L to -1L)

        val afterLabel = if (cancellationRequested) "afterCancellation" else "afterGeneration"
        captureMemory(afterLabel)
        val thermalAfter = captureThermal(afterLabel)

        val hasStart = genStartNanos > 0
        val hasFirst = firstOutputNanos > 0
        val genReqToFirst = if (hasStart && hasFirst) PerfCalc.nanosToMs(firstOutputNanos - genStartNanos) else -1
        val decodeActiveMs = if (hasFirst) PerfCalc.nanosToMs(endNanos - firstOutputNanos) else 0
        val totalGenerationMs = if (hasStart) PerfCalc.nanosToMs(endNanos - genStartNanos) else -1
        val cancellationLatencyMs = if (cancellationRequested && cancelReqNanos > 0) {
            PerfCalc.nanosToMs(endNanos - cancelReqNanos)
        } else {
            -1
        }

        val thermalChanged = run {
            val before = thermalBeforeGeneration
            before != null && thermalAfter != null && before.supported && thermalAfter.supported &&
                before.statusCode != thermalAfter.statusCode
        }

        // Token accuracy: the app consumes LiteRT-LM streamed text (Message.toString()), which may
        // be tokens, fragments, or multi-token chunks. There is no reliable exact per-message token
        // count in use, so exact counts are UNAVAILABLE and only clearly-labelled estimates are set.
        val estimatedOutputTokens = if (outputCharacterCount > 0) PerfCalc.estimateTokens(outputCharacterCount) else null
        val tokenCountType = if (outputCharacterCount > 0) TokenCountType.ESTIMATED else TokenCountType.UNAVAILABLE

        val metrics = InferencePerformanceMetrics(
            localTurnId = localTurnId,
            sessionId = sessionId,
            device = device,
            modelId = modelId,
            modelFileName = modelFileName,
            modelFileSizeBytes = modelFileSizeBytes,
            backend = backend,
            backendPrefetchMs = backendPrefetchMs,
            backendPrefetchOutcome = prefetchOutcome,
            prefetchChunkCount = prefetchChunkCount,
            prefetchContextCharacterCount = prefetchContextChars,
            prefetchPacketBytes = prefetchPacketBytes,
            offlineFallbackMs = offlineFallbackMs,
            promptBuildMs = promptBuildMs,
            promptCharacterCount = promptChars,
            promptTokenCount = PerfCalc.estimateTokens(promptChars),
            promptTokenCountType = if (promptChars > 0) TokenCountType.ESTIMATED else TokenCountType.UNAVAILABLE,
            conversationCreationMs = conversationCreationMs,
            generationRequestToFirstOutputMs = genReqToFirst,
            decodeActiveMs = decodeActiveMs,
            totalGenerationMs = totalGenerationMs,
            memorySyncMs = memorySyncMs,
            streamEventCount = streamEventCount,
            outputCharacterCount = outputCharacterCount,
            exactOutputTokenCount = null,
            estimatedOutputTokenCount = estimatedOutputTokens,
            tokenCountType = tokenCountType,
            exactDecodeTokensPerSecond = null,
            estimatedDecodeTokensPerSecond = PerfCalc.estimatedDecodeTokensPerSecond(outputCharacterCount, decodeActiveMs),
            outputCharactersPerSecond = PerfCalc.ratePerSecond(outputCharacterCount, decodeActiveMs),
            streamEventsPerSecond = PerfCalc.ratePerSecond(streamEventCount, decodeActiveMs),
            cancellationRequested = cancellationRequested,
            cancellationLatencyMs = cancellationLatencyMs,
            completionStatus = status,
            errorCategory = errorCategory,
            memorySnapshots = synchronized(lock) { memory.toList() },
            thermalSnapshots = synchronized(lock) { thermal.toList() },
            thermalStatusChangedDuringGeneration = thermalChanged,
            sampledPeakJavaUsedBytes = peaks.first,
            sampledPeakNativeAllocatedBytes = peaks.second,
        )
        sink.onTurn(metrics)
        return metrics
    }
}
