package com.personaledge.ai.models

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(ModelDownloader.KEY_MODEL_ID)
            ?: return Result.failure()
        val hfToken = inputData.getString(ModelDownloader.KEY_HF_TOKEN)?.takeIf { it.isNotBlank() }
        val entry = ModelCatalog.findById(modelId) ?: return Result.failure()

        if (entry.isGated && hfToken.isNullOrBlank()) {
            return Result.failure(
                workDataOf("error" to "Hugging Face token required for gated model"),
            )
        }

        val repository = ModelRepository(applicationContext)
        val destFile = repository.getModelFile(entry)

        return try {
            ModelDownloader.downloadWithResume(
                url = entry.downloadUrl,
                destFile = destFile,
                hfToken = hfToken,
            ) { downloaded, total ->
                setProgressAsync(
                    workDataOf(
                        ModelDownloader.PROGRESS_BYTES to downloaded,
                        ModelDownloader.PROGRESS_TOTAL to total,
                    ),
                )
            }
            if (!repository.isDownloaded(entry)) {
                destFile.delete()
                return Result.failure(
                    workDataOf("error" to "Download incomplete. Check connection and try again."),
                )
            }
            if (repository.looksLikeErrorPage(destFile)) {
                destFile.delete()
                return Result.failure(
                    workDataOf(
                        "error" to if (entry.isGated) {
                            "Invalid file — add HF token and accept the model license on huggingface.co"
                        } else {
                            "Invalid model file returned. Retry download."
                        },
                    ),
                )
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
        }
    }
}
