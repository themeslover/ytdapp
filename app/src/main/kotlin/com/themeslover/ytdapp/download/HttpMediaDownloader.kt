package com.themeslover.ytdapp.download

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpMediaDownloader(
    private val resolver: ContentResolver,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) : MediaDownloader {
    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (Long, Long) -> Unit
    ): DownloadRequest {
        val uri = request.outputUri?.let(Uri::parse)
            ?: throw IllegalArgumentException("A destination is required")
        var existing = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        if (existing < 0L) existing = 0L

        val builder = Request.Builder().url(request.sourceUrl)
        if (existing > 0L) builder.header("Range", "bytes=$existing-")
        val response = client.newCall(builder.build()).execute()

        response.use { r ->
            if (!r.isSuccessful) {
                if (r.code == 429 || r.code >= 500) throw IOException("Temporary HTTP ${r.code}")
                throw IOException("HTTP ${r.code}")
            }
            val body = r.body ?: throw IOException("Empty response")
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.startsWith("text/html", ignoreCase = true)) {
                throw IOException("The URL returned a web page, not a media file")
            }

            val append = existing > 0L && r.code == 206
            if (!append) existing = 0L
            val output = resolver.openOutputStream(uri, if (append) "wa" else "w")
                ?: throw IOException("Unable to open destination")
            output.use { sink ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = existing
                    val length = if (body.contentLength() >= 0) existing + body.contentLength() else -1L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        total += read
                        onProgress(total, length)
                    }
                    sink.flush()
                    return request.copy(bytesDownloaded = total, totalBytes = length)
                }
            }
        }
    }
}
