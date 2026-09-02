package com.themeslover.ytdapp.download

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { VIDEO, AUDIO }

@Serializable
enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, SKIPPED }

@Serializable
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

@Serializable
data class DownloadBatch(
    val id: String,
    val items: List<DownloadRequest>,
    val maxParallel: Int = 3,
    val retryLimit: Int = 4
)
