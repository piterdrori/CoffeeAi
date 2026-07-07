package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "LibWhisper"

/** Result of a timed transcription attempt. */
sealed class WhisperTranscribeResult {
    data class Success(val text: String) : WhisperTranscribeResult()
    data object TimedOut : WhisperTranscribeResult()
    data class Error(val message: String) : WhisperTranscribeResult()
}

class WhisperContext private constructor(
    private var ptr: Long,
    private val modelPath: String?,
) {
    private val lock = Any()

    @Volatile
    private var abandoned = false

    /**
     * Runs whisper on a dedicated thread with [timeoutMs] hard limit.
     * Coroutine timeouts cannot cancel blocking JNI — Thread.join can.
     * On timeout the context is abandoned and must be recreated via [reload].
     */
    fun transcribeWithTimeout(samples: FloatArray, timeoutMs: Long): WhisperTranscribeResult {
        val contextPtr = synchronized(lock) {
            if (ptr == 0L) {
                return WhisperTranscribeResult.Error("Whisper context not loaded")
            }
            ptr
        }

        val result = AtomicReference<WhisperTranscribeResult?>(null)
        val thread = Thread({
            try {
                val numThreads = WhisperCpuConfig.preferredThreadCount.coerceIn(1, 2)
                WhisperLib.fullTranscribe(contextPtr, numThreads, samples)
                val textCount = WhisperLib.getTextSegmentCount(contextPtr)
                val text = buildString {
                    for (i in 0 until textCount) {
                        append(WhisperLib.getTextSegment(contextPtr, i))
                    }
                }.trim()
                result.set(WhisperTranscribeResult.Success(text))
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "Whisper native transcribe failed", e)
                result.set(WhisperTranscribeResult.Error(e.message ?: "Native error"))
            }
        }, "whisper-transcribe")
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        thread.start()
        thread.join(timeoutMs)

        if (thread.isAlive) {
            Log.e(LOG_TAG, "Whisper timed out after ${timeoutMs}ms (${samples.size} samples)")
            synchronized(lock) {
                abandoned = true
                ptr = 0L
            }
            return WhisperTranscribeResult.TimedOut
        }

        return result.get() ?: WhisperTranscribeResult.Error("No transcription result")
    }

    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        val path = modelPath ?: return@withContext false
        synchronized(lock) {
            if (!abandoned && ptr != 0L) {
                try {
                    WhisperLib.freeContext(ptr)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "freeContext failed during reload", e)
                }
            }
            ptr = WhisperLib.initContext(path)
            abandoned = false
            ptr != 0L
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (abandoned || ptr == 0L) {
                ptr = 0L
                return@withContext
            }
            try {
                WhisperLib.freeContext(ptr)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "freeContext failed", e)
            }
            ptr = 0L
        }
    }

    companion object {
        fun createFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create whisper context at $filePath")
            }
            Log.i(LOG_TAG, "Loaded whisper model from $filePath")
            return WhisperContext(ptr, filePath)
        }

        fun createFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create whisper context from asset $assetPath")
            }
            return WhisperContext(ptr, null)
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                readCpuInfo()?.let { cpuInfo ->
                    if (cpuInfo.contains("vfpv4")) loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                readCpuInfo()?.let { cpuInfo ->
                    if (cpuInfo.contains("fphp")) loadV8fp16 = true
                }
            }
            when {
                loadVfpv4 -> System.loadLibrary("whisper_vfpv4")
                loadV8fp16 -> System.loadLibrary("whisper_v8fp16_va")
                else -> System.loadLibrary("whisper")
            }
        }

        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS[0] == "armeabi-v7a"

private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS[0] == "arm64-v8a"

private fun readCpuInfo(): String? = try {
    java.io.File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (_: Exception) {
    null
}
