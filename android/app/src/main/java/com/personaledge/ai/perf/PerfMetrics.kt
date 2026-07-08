package com.personaledge.ai.perf

/**
 * Stage 0 performance instrumentation — pure, Android-free data + calculations.
 *
 * This file intentionally has NO Android imports so every record and every derived-rate
 * calculation can be unit tested on the JVM without a device. Anything that needs the Android
 * platform (memory/thermal/device APIs, monotonic clock) is injected from [PerfCollectors] /
 * the recorder wiring.
 *
 * Privacy rule (enforced by construction): these records store COUNTS and DURATIONS only.
 * There is deliberately NO field that can hold a raw prompt, raw response, memory chunk text,
 * user profile, API key, or any secret. Only character/byte counts and category enums are kept.
 */

/** Which inference backend was involved. Mirrors [com.personaledge.ai.inference.InferenceBackend]. */
enum class PerfBackend { CPU, GPU, UNKNOWN }

/** Outcome of a backend memory prefetch attempt (no bodies, categories only). */
enum class PrefetchOutcome {
    SUCCESS,
    TIMEOUT,
    HTTP_FAILURE,
    PARSE_FAILURE,
    AUTH_FAILURE,
    OFFLINE,
    NOT_ATTEMPTED,
    OTHER,
}

/** How a token count was obtained. */
enum class TokenCountType { EXACT, ESTIMATED, UNAVAILABLE }

/** Terminal state of a generation turn. */
enum class CompletionStatus {
    SUCCESS,
    BLANK,
    ERROR,
    TIMEOUT_FIRST_TOKEN,
    TIMEOUT_INTER_TOKEN,
    CANCELLED,
    NOT_RUN,
}

/** Coarse error category (never the raw exception message, to avoid leaking content). */
enum class ErrorCategory { NONE, LOAD_FAILURE, GENERATION_FAILURE, PREFETCH_FAILURE, TIMEOUT, CANCELLED, UNKNOWN }

/**
 * One memory sample. All values are bytes. Any value that could not be read on the current device
 * is left as -1 (unknown) rather than a misleading 0.
 */
data class MemorySnapshot(
    val label: String,
    val offsetMs: Long,
    val javaUsedBytes: Long = -1,
    val javaTotalBytes: Long = -1,
    val javaMaxBytes: Long = -1,
    val nativeAllocatedBytes: Long = -1,
    val nativeHeapBytes: Long = -1,
    val systemAvailBytes: Long = -1,
    val systemTotalBytes: Long = -1,
    val systemLowMemory: Boolean = false,
)

/**
 * One thermal sample. [supported] is false on devices/API levels where the thermal status is not
 * available; consumers must treat [statusCode] as meaningless when unsupported.
 */
data class ThermalSnapshot(
    val label: String,
    val offsetMs: Long,
    val statusCode: Int = -1,
    val category: String = "unknown",
    val supported: Boolean = false,
)

/** What backend was requested, attempted, and finally selected — recording only, no behavior change. */
data class BackendSelection(
    val requested: PerfBackend,
    val attempted: PerfBackend,
    val selected: PerfBackend,
    val fallbackOccurred: Boolean,
    /** Category only (e.g. "gpu_init_failed"); never a raw exception message. */
    val fallbackReason: String? = null,
)

/** Static, non-identifying device + build info used to interpret a benchmark. */
data class DeviceMetadata(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val availableProcessors: Int,
    val totalRamBytes: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: String,
    val litertLmVersion: String,
)

/** Result of a single model-load event. Durations in ms, measured with a monotonic clock. */
data class ModelLoadMetrics(
    val modelId: String,
    val modelFileName: String,
    val modelFileSizeBytes: Long,
    val device: DeviceMetadata,
    val backend: BackendSelection,
    val modelPreparationMs: Long,
    val engineCreationMs: Long,
    val engineInitializationMs: Long,
    val conversationCreationMs: Long,
    val totalLoadMs: Long,
    val completionStatus: CompletionStatus,
    val errorCategory: ErrorCategory,
    val memorySnapshots: List<MemorySnapshot>,
    val thermalSnapshots: List<ThermalSnapshot>,
)

/**
 * Metrics for exactly one generation turn.
 *
 * IMPORTANT accuracy contract:
 *  - [streamEventCount] counts native stream callbacks. LiteRT-LM may emit tokens, token
 *    fragments, or multi-token text chunks per callback, so this is NOT a token count.
 *  - [outputCharacterCount] is the exact number of characters streamed.
 *  - [exactOutputTokenCount] is populated only if a reliable runtime token API is used
 *    ([tokenCountType] == EXACT). At the time of writing the app uses the streamed
 *    `Message.toString()` text (chunks), so exact counts are UNAVAILABLE.
 *  - [estimatedOutputTokenCount] is a clearly-labelled heuristic (see [PerfCalc.estimateTokens]).
 */
data class InferencePerformanceMetrics(
    val localTurnId: Long,
    val sessionId: String?,
    val device: DeviceMetadata,
    val modelId: String,
    val modelFileName: String,
    val modelFileSizeBytes: Long,
    val backend: BackendSelection,

    val backendPrefetchMs: Long,
    val backendPrefetchOutcome: PrefetchOutcome,
    val prefetchChunkCount: Int,
    val prefetchContextCharacterCount: Int,
    val prefetchPacketBytes: Long,
    val offlineFallbackMs: Long,

    val promptBuildMs: Long,
    val promptCharacterCount: Int,
    val promptTokenCount: Int,
    val promptTokenCountType: TokenCountType,

    val conversationCreationMs: Long,

    val generationRequestToFirstOutputMs: Long,
    val decodeActiveMs: Long,
    val totalGenerationMs: Long,
    val memorySyncMs: Long,

    val streamEventCount: Int,
    val outputCharacterCount: Int,
    val exactOutputTokenCount: Int?,
    val estimatedOutputTokenCount: Int?,
    val tokenCountType: TokenCountType,

    val exactDecodeTokensPerSecond: Double?,
    val estimatedDecodeTokensPerSecond: Double?,
    val outputCharactersPerSecond: Double?,
    val streamEventsPerSecond: Double?,

    val cancellationRequested: Boolean,
    val cancellationLatencyMs: Long,

    val completionStatus: CompletionStatus,
    val errorCategory: ErrorCategory,

    val memorySnapshots: List<MemorySnapshot>,
    val thermalSnapshots: List<ThermalSnapshot>,
    val thermalStatusChangedDuringGeneration: Boolean,

    val sampledPeakJavaUsedBytes: Long,
    val sampledPeakNativeAllocatedBytes: Long,
)

/**
 * Pure, side-effect-free derived-metric helpers. Every rate returns null (not 0, not NaN) when it
 * cannot be computed safely, so callers never emit misleading values.
 */
object PerfCalc {
    /** Characters used to approximate one token. Documented heuristic, NOT an exact tokenizer. */
    const val CHARS_PER_TOKEN_ESTIMATE = 4.0

    fun nanosToMs(deltaNanos: Long): Long = if (deltaNanos <= 0) 0 else deltaNanos / 1_000_000

    /** Rate per second, or null when the duration is non-positive (avoids divide-by-zero). */
    fun ratePerSecond(count: Long, durationMs: Long): Double? {
        if (durationMs <= 0L || count <= 0L) return null
        return count.toDouble() * 1000.0 / durationMs.toDouble()
    }

    fun ratePerSecond(count: Int, durationMs: Long): Double? = ratePerSecond(count.toLong(), durationMs)

    /**
     * Approximate token count from character count. Clearly an ESTIMATE — LiteRT-LM does not expose
     * an exact per-message token count through the streamed text API the app currently uses.
     */
    fun estimateTokens(characterCount: Int): Int {
        if (characterCount <= 0) return 0
        return Math.round(characterCount / CHARS_PER_TOKEN_ESTIMATE).toInt()
    }

    /**
     * Estimated decode tokens/sec, derived from the ESTIMATED token count over the decode-active
     * window. Only meaningful alongside [TokenCountType.ESTIMATED]. Null when not computable.
     */
    fun estimatedDecodeTokensPerSecond(characterCount: Int, decodeActiveMs: Long): Double? =
        ratePerSecond(estimateTokens(characterCount), decodeActiveMs)
}
