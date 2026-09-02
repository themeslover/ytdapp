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
        val source = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Download"
        val output = inputData.getString(KEY_OUTPUT) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND).orEmpty()
        val requestId = inputData.getString(KEY_ID) ?: id.toString()
        createChannel()
        setForeground(createForegroundInfo(requestId, title, 0))
        val request = DownloadRequest(requestId, source, title, if (kind == MediaKind.AUDIO.name) MediaKind.AUDIO else MediaKind.VIDEO, inputData.getString(KEY_QUALITY) ?: "best", outputUri = output)
        return try {
            val result = HttpMediaDownloader(applicationContext.contentResolver).download(request) { done, total ->
                val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                setProgress(workDataOf("bytes" to done, "total" to total, "percent" to percent))
                setForeground(createForegroundInfo(requestId, title, percent))
            }
            setProgress(workDataOf("bytes" to result.bytesDownloaded, "total" to result.totalBytes, "percent" to 100))
            Result.success(workDataOf("outputUri" to output))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
        } catch (e: Throwable) {
            Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
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
    }
}
