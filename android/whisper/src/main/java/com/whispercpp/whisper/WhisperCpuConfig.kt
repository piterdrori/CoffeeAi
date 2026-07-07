package com.whispercpp.whisper

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

object WhisperCpuConfig {
    /** Use 1 thread on x86 emulators — multi-thread whisper can hang in compatibility mode. */
    val preferredThreadCount: Int
        get() {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            if (abi.contains("x86")) return 1
            return CpuInfo.getHighPerfCpuCount().coerceIn(1, 2)
        }
}

private class CpuInfo(private val lines: List<String>) {
    private fun getHighPerfCpuCount(): Int = try {
        getHighPerfCpuCountByFrequencies()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Couldn't read CPU frequencies", e)
        getHighPerfCpuCountByVariant()
    }

    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues(property = "processor") { getMaxCpuFrequency(it.toInt()) }
            .countDroppingMin()

    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues(property = "CPU variant") { it.substringAfter("0x").toInt(radix = 16) }
            .countKeepingMin()

    private fun getCpuValues(property: String, mapper: (String) -> Int) = lines
        .asSequence()
        .filter { it.startsWith(property) }
        .map { mapper(it.substringAfter(':').trim()) }
        .sorted()
        .toList()

    private fun List<Int>.countDroppingMin(): Int {
        if (isEmpty()) return 2
        val min = min()
        return count { it > min }.coerceAtLeast(2)
    }

    private fun List<Int>.countKeepingMin(): Int {
        if (isEmpty()) return 2
        val min = min()
        return count { it == min }.coerceAtLeast(2)
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        fun getHighPerfCpuCount(): Int = try {
            readCpuInfo().getHighPerfCpuCount()
        } catch (e: Exception) {
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        }

        private fun readCpuInfo() = CpuInfo(
            BufferedReader(FileReader("/proc/cpuinfo")).useLines { it.toList() },
        )

        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
            return BufferedReader(FileReader(path)).use { it.readLine().toInt() }
        }
    }
}
