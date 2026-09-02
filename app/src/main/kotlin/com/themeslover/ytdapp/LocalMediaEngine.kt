package com.themeslover.ytdapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * On-device media engine. It bundles yt-dlp, Python, QuickJS and FFmpeg through
 * youtubedl-android so the normal download flow does not require a PC server.
 */
object LocalMediaEngine {
    private const val DEFAULT_LIMIT = 30
    private const val MAX_PLAYLIST = 200

    @Volatile private var initialized = false
    private val initLock = Any()

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
            } catch (e: Exception) {
                throw IOException("Could not initialize the on-device downloader: ${e.message ?: "native runtime error"}", e)
            }
        }
    }

    fun isReady(): Boolean = initialized

    fun search(context: Context, query: String, limit: Int = DEFAULT_LIMIT): List<ApiClient.SearchItem> {
        ensureInitialized(context)
        val safeLimit = limit.coerceIn(1, 50)
        val request = YoutubeDLRequest("ytsearch${safeLimit}:${query.trim()}")
        request.addOption("--flat-playlist")
        request.addOption("--dump-json")
        request.addOption("--skip-download")
        request.addOption("--ignore-errors")
        val response = YoutubeDL.getInstance().execute(request, null, true, null)
        return response.out.lineSequence().mapNotNull(::parseSearchItem).take(safeLimit).toList()
    }

    fun playlist(context: Context, playlistUrl: String, limit: Int = MAX_PLAYLIST): List<ApiClient.PlaylistItem> {
        ensureInitialized(context)
        val safeLimit = limit.coerceIn(1, MAX_PLAYLIST)
        val request = YoutubeDLRequest(playlistUrl.trim())
        request.addOption("--flat-playlist")
        request.addOption("--dump-json")
        request.addOption("--skip-download")
        request.addOption("--ignore-errors")
        request.addOption("--playlist-end", safeLimit.toString())
        val response = YoutubeDL.getInstance().execute(request, null, true, null)
        return response.out.lineSequence().mapNotNull(::parsePlaylistItem).take(safeLimit).toList()
    }

    fun resolve(mediaUrl: String, kind: String, quality: String): ApiClient.Format {
        val audio = kind.equals("AUDIO", ignoreCase = true)
        return ApiClient.Format(
            url = mediaUrl.trim(),
            ext = if (audio) "m4a" else "mp4",
            height = quality.filter(Char::isDigit).toIntOrNull(),
            abr = null,
            vcodec = if (audio) null else "*",
            acodec = "*"
        )
    }

    fun download(
        context: Context,
        sourceUrl: String,
        title: String,
        outputUri: String,
        kind: String,
        quality: String,
        processId: String,
        onProgress: (Int, String) -> Unit
    ) {
        ensureInitialized(context)
        val workDir = File(context.cacheDir, "downloads/$processId")
        workDir.mkdirs()
        val template = File(workDir, "media.%(ext)s").absolutePath
        val request = YoutubeDLRequest(sourceUrl)
        request.addOption("--no-mtime")
        request.addOption("--continue")
        request.addOption("--no-overwrites")
        request.addOption("--newline")
        request.addOption("-o", template)

        if (kind.equals("AUDIO", ignoreCase = true)) {
            request.addOption("-x")
            request.addOption("--audio-format", "m4a")
            request.addOption("--audio-quality", "0")
            request.addOption("--embed-metadata")
            request.addOption("--embed-thumbnail")
        } else {
            request.addOption("-f", videoFormat(quality))
            request.addOption("--merge-output-format", "mp4")
        }

        val response = YoutubeDL.getInstance().execute(request, processId, true) { progress, _, line ->
            onProgress(progress.toInt().coerceIn(0, 100), line)
        }
        if (response.exitCode != 0) throw IOException(response.err.ifBlank { "yt-dlp failed with exit code ${response.exitCode}" })

        val output = workDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("media.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("Downloader finished without producing a media file")

        copyToDestination(context, output, Uri.parse(outputUri))
        publishDestination(context, Uri.parse(outputUri))
        output.delete()
        workDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        workDir.delete()
        onProgress(100, "Download complete")
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun videoFormat(quality: String): String {
        val height = quality.filter(Char::isDigit).toIntOrNull()
        return if (height == null) "bv*+ba/b" else "bv*[height<=${height}]+ba/b[height<=${height}]"
    }

    private fun copyToDestination(context: Context, source: File, destination: Uri) {
        when (destination.scheme?.lowercase()) {
            "file" -> {
                val path = destination.path ?: throw IOException("Invalid file destination")
                File(path).parentFile?.mkdirs()
                FileOutputStream(File(path)).use { out -> source.inputStream().use { it.copyTo(out) } }
            }
            "content" -> {
                val out = context.contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("Unable to open Downloads destination")
                out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            }
            else -> throw IOException("Unsupported destination: ${destination.scheme}")
        }
    }

    private fun publishDestination(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            runCatching {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
        }
    }

    private fun parseSearchItem(line: String): ApiClient.SearchItem? = runCatching {
        val json = JSONObject(line)
        val url = json.optString("webpage_url").ifBlank { json.optString("original_url") }
        if (url.isBlank()) return null
        ApiClient.SearchItem(
            title = json.optString("title", "Untitled"),
            url = url,
            duration = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 }?.toLong(),
            thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() },
            channel = json.optString("uploader").takeIf { it.isNotBlank() },
            viewCount = json.optLong("view_count").takeIf { it > 0 },
            uploadDate = json.optString("upload_date").takeIf { it.isNotBlank() },
            live = json.optBoolean("is_live", false)
        )
    }.getOrNull()

    private fun parsePlaylistItem(line: String): ApiClient.PlaylistItem? = runCatching {
        val json = JSONObject(line)
        val url = json.optString("webpage_url").ifBlank { json.optString("url") }
        if (url.isBlank()) return null
        ApiClient.PlaylistItem(
            title = json.optString("title", "Untitled"),
            url = url,
            duration = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 }?.toLong(),
            thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() },
            channel = json.optString("uploader").takeIf { it.isNotBlank() },
            index = json.optInt("playlist_index").takeIf { it > 0 }
        )
    }.getOrNull()
}
