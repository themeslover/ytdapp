package com.themeslover.ytdapp.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface MediaDownloader {
    suspend fun download(request: DownloadRequest, onProgress: suspend (Long, Long) -> Unit): DownloadRequest
}

class DownloadQueue(
    private val downloader: MediaDownloader,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    suspend fun run(batch: DownloadBatch): List<DownloadRequest> = coroutineScope {
        val semaphore = Semaphore(batch.maxParallel.coerceIn(1, 8))
        batch.items.map { original ->
            async(Dispatchers.IO) {
                semaphore.withPermit { runOne(original, batch.retryLimit) }
            }
        }.awaitAll()
    }

    private suspend fun runOne(original: DownloadRequest, retryLimit: Int): DownloadRequest {
        var current = original.copy(state = DownloadState.RUNNING)
        while (true) {
            try {
                return downloader.download(current) { _, _ -> }
                    .copy(state = DownloadState.COMPLETED, lastError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val nextAttempt = current.attempt + 1
                if (nextAttempt > retryLimit || !retryPolicy.shouldRetry(current.attempt)) {
                    return current.copy(
                        state = DownloadState.FAILED,
                        attempt = nextAttempt,
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                }
                current = current.copy(
                    state = DownloadState.QUEUED,
                    attempt = nextAttempt,
                    lastError = error.message
                )
                delay(retryPolicy.delayMs(current.attempt))
            }
        }
    }
}
