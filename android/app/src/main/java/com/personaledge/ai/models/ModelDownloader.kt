package com.personaledge.ai.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    const val WORK_NAME_PREFIX = "model_download_"
    const val KEY_MODEL_ID = "model_id"
    const val KEY_HF_TOKEN = "hf_token"
    const val KEY_WIFI_ONLY = "wifi_only"
    const val PROGRESS_BYTES = "progress_bytes"
    const val PROGRESS_TOTAL = "progress_total"

    fun enqueue(context: Context, modelId: String, hfToken: String?, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_HF_TOKEN to (hfToken ?: ""),
                    KEY_WIFI_ONLY to wifiOnly,
                ),
            )
            .addTag("$WORK_NAME_PREFIX$modelId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_PREFIX$modelId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME_PREFIX$modelId")
    }

    fun observeProgress(context: Context, modelId: String): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow("$WORK_NAME_PREFIX$modelId")
            .map { infos -> infos.firstOrNull() }
    }

    internal fun downloadWithResume(
        url: String,
        destFile: File,
        hfToken: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        destFile.parentFile?.mkdirs()
        var downloaded = if (destFile.exists()) destFile.length() else 0L

        var currentUrl = url
        var redirects = 0
        while (redirects < 10) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PersonalEdgeAI/1.0")
                if (!hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
                if (downloaded > 0) {
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
            }

            val code = connection.responseCode
            if (code in 300..399) {
                currentUrl = connection.getHeaderField("Location")
                    ?: error("Redirect without Location header")
                connection.disconnect()
                redirects++
                continue
            }

            if (code !in listOf(200, 206)) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                connection.disconnect()
                error("Download failed HTTP $code: $errorBody")
            }

            val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { len ->
                if (code == 206) downloaded + len else len
            } ?: -1L

            connection.inputStream.use { input ->
                RandomAccessFile(destFile, "rw").use { raf ->
                    if (downloaded > 0) raf.seek(downloaded)
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            connection.disconnect()
            return
        }
        error("Too many redirects")
    }
}
