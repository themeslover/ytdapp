package com.themeslover.ytdapp.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE) ?: return Result.failure(workDataOf("error" to "Missing source URL"))
        val title = inputData.getString(KEY_TITLE) ?: "Download"
        val output = inputData.getString(KEY_OUTPUT) ?: return Result.failure(workDataOf("error" to "Missing destination"))
        val kind = inputData.getString(KEY_KIND).orEmpty()
        val quality = inputData.getString(KEY_QUALITY) ?: "best"
        val requestId = inputData.getString(KEY_ID) ?: id.toString()
        createChannel()
        setForeground(createForegroundInfo(requestId, title, 0))
        val request = DownloadRequest(
            requestId,
            source,
            title,
            if (kind == MediaKind.AUDIO.name) MediaKind.AUDIO else MediaKind.VIDEO,
            quality,
            outputUri = output,
            attempt = runAttemptCount
        )

        fun progressData(bytes: Long, total: Long, percent: Int) = workDataOf(
            "bytes" to bytes,
            "total" to total,
            "percent" to percent,
            "attempt" to runAttemptCount,
            KEY_SOURCE to source,
            KEY_TITLE to title,
            KEY_OUTPUT to output,
            KEY_KIND to kind,
            KEY_QUALITY to quality
        )

        setProgress(progressData(0L, 0L, 0))

        return try {
            val result = HttpMediaDownloader(applicationContext.contentResolver).download(request) { done, total ->
                val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                setProgress(progressData(done, total, percent))
                setForeground(createForegroundInfo(requestId, title, percent))
            }
            setProgress(progressData(result.bytesDownloaded, result.totalBytes, 100))
            Result.success(workDataOf("outputUri" to output, KEY_TITLE to title))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            val retryable = isRetryable(e)
            if (retryable && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        "error" to (e.message ?: "Download failed"),
                        "attempts" to (runAttemptCount + 1),
                        KEY_SOURCE to source,
                        KEY_TITLE to title,
                        KEY_OUTPUT to output,
                        KEY_KIND to kind,
                        KEY_QUALITY to quality
                    )
                )
            }
        } catch (e: Throwable) {
            Result.failure(
                workDataOf(
                    "error" to (e.message ?: "Download failed"),
                    KEY_SOURCE to source,
                    KEY_TITLE to title,
                    KEY_OUTPUT to output,
                    KEY_KIND to kind,
                    KEY_QUALITY to quality
                )
            )
        }
    }

    private fun isRetryable(error: IOException): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("temporary") ||
            message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("connection") ||
            message.contains("reset") ||
            message.contains("429") ||
            message.contains("500") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    private fun createForegroundInfo(id: String, title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "Downloading $progress%" else "Starting download")
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()
        return ForegroundInfo(id.hashCode(), notification)
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_SOURCE = "source"
        const val KEY_TITLE = "title"
        const val KEY_OUTPUT = "output"
        const val KEY_KIND = "kind"
        const val KEY_QUALITY = "quality"
        private const val CHANNEL_ID = "downloads"
        private const val MAX_RETRIES = 3
    }
}
