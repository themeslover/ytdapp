package com.themeslover.ytdapp.download

enum class MediaKind { VIDEO, AUDIO }

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, SKIPPED }

data class DownloadRequest(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val kind: MediaKind,
    val quality: String,
    val outputUri: String? = null,
    val attempt: Int = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val lastError: String? = null
)

data class DownloadBatch(
    val id: String,
    val items: List<DownloadRequest>,
    val maxParallel: Int = 3,
    val retryLimit: Int = 4
)
