package com.personaledge.ai.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.personaledge.ai.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collector interfaces so the recorder stays pure/JVM-testable. Android implementations live below;
 * tests inject no-op fakes (or null) and never touch the platform.
 */
interface MemoryCollector {
    fun snapshot(label: String, offsetMs: Long): MemorySnapshot
}

interface ThermalCollector {
    fun snapshot(label: String, offsetMs: Long): ThermalSnapshot
}

/** Lightweight background peak sampler. start()/stop() must be idempotent and leak-free. */
interface PeakSampler {
    fun start()
    /** @return the sampled peaks observed while running (java used, native allocated), or -1 each. */
    fun stop(): Pair<Long, Long>
}

/** No-op sampler used in release or when detailed sampling is disabled. */
object NoOpPeakSampler : PeakSampler {
    override fun start() {}
    override fun stop(): Pair<Long, Long> = -1L to -1L
}

/** Reads Java + native heap and system memory. All reads are best-effort; unknowns become -1. */
class AndroidMemoryCollector(context: Context) : MemoryCollector {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun snapshot(label: String, offsetMs: Long): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val javaTotal = runtime.totalMemory()
        val javaFree = runtime.freeMemory()
        val javaMax = runtime.maxMemory()
        val nativeAllocated = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(-1L)
        val nativeHeap = runCatching { Debug.getNativeHeapSize() }.getOrDefault(-1L)

        var availBytes = -1L
        var totalBytes = -1L
        var low = false
        activityManager?.let { am ->
            runCatching {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                availBytes = info.availMem
                totalBytes = info.totalMem
                low = info.lowMemory
            }
        }

        return MemorySnapshot(
            label = label,
            offsetMs = offsetMs,
            javaUsedBytes = (javaTotal - javaFree).coerceAtLeast(0),
            javaTotalBytes = javaTotal,
            javaMaxBytes = javaMax,
            nativeAllocatedBytes = nativeAllocated,
            nativeHeapBytes = nativeHeap,
            systemAvailBytes = availBytes,
            systemTotalBytes = totalBytes,
            systemLowMemory = low,
        )
    }
}

/** Reads [PowerManager] thermal status where the API level supports it; safe on older devices. */
class AndroidThermalCollector(context: Context) : ThermalCollector {
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun snapshot(label: String, offsetMs: Long): ThermalSnapshot {
        // getCurrentThermalStatus() requires API 29 (Q). Below that it is unavailable.
        val pm = powerManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pm == null) {
            return ThermalSnapshot(label = label, offsetMs = offsetMs, supported = false)
        }
        val status = runCatching { pm.currentThermalStatus }.getOrNull()
            ?: return ThermalSnapshot(label = label, offsetMs = offsetMs, supported = false)
        return ThermalSnapshot(
            label = label,
            offsetMs = offsetMs,
            statusCode = status,
            category = thermalCategory(status),
            supported = true,
        )
    }

    companion object {
        fun thermalCategory(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}

/**
 * Debug-only peak sampler. Polls Java-used and native-allocated bytes on a background coroutine at a
 * modest interval and keeps the maxima. Stops cleanly on [stop] (also safe if never started).
 */
class CoroutinePeakSampler(
    private val memoryCollector: MemoryCollector,
    private val intervalMs: Long = 250L,
) : PeakSampler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile private var peakJavaUsed: Long = -1L
    @Volatile private var peakNativeAllocated: Long = -1L

    override fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val snap = memoryCollector.snapshot("sampler", 0)
                if (snap.javaUsedBytes > peakJavaUsed) peakJavaUsed = snap.javaUsedBytes
                if (snap.nativeAllocatedBytes > peakNativeAllocated) peakNativeAllocated = snap.nativeAllocatedBytes
                delay(intervalMs)
            }
        }
    }

    override fun stop(): Pair<Long, Long> {
        job?.cancel()
        job = null
        return peakJavaUsed to peakNativeAllocated
    }
}

/** Builds non-identifying device metadata. Collects NO advertising id, serial, Android ID, etc. */
object DeviceMetadataProvider {
    fun collect(context: Context): DeviceMetadata {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRam = runCatching {
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(-1L)

        return DeviceMetadata(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = totalRam,
            appVersionName = BuildConfig.VERSION_NAME ?: "unknown",
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE ?: "unknown",
            litertLmVersion = PerfConstants.LITERT_LM_VERSION,
        )
    }
}

/**
 * Stage 0 constants. [LITERT_LM_VERSION] must be kept in sync with the Gradle dependency
 * `com.google.ai.edge.litertlm:litertlm-android` in android/app/build.gradle.kts.
 */
object PerfConstants {
    const val LITERT_LM_VERSION = "0.13.1"

    /** Whether detailed memory/thermal sampling should run. Off in release to avoid overhead. */
    val detailedSamplingEnabled: Boolean get() = BuildConfig.DEBUG
}
