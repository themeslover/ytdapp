package com.themeslover.ytdapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApiClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    data class SearchItem(
        val title: String,
        val url: String,
        val duration: Long?,
        val thumbnail: String?
    )

    data class PlaylistItem(
        val title: String,
        val url: String,
        val duration: Long?,
        val thumbnail: String?
    )

    data class Format(
        val url: String,
        val ext: String?,
        val height: Int?,
        val abr: Double?,
        val vcodec: String?,
        val acodec: String?
    )

    fun search(baseUrl: String, query: String): List<SearchItem> {
        val url = normalize(baseUrl) + "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=20"
        val response = get(url)
        val items = response.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val mediaUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(SearchItem(
                    title = item.optString("title", "Untitled"),
                    url = mediaUrl,
                    duration = item.optLongOrNull("duration"),
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() }
                ))
            }
        }
    }

    fun playlist(baseUrl: String, playlistUrl: String): List<PlaylistItem> {
        val payload = JSONObject().put("url", playlistUrl)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(normalize(baseUrl) + "/playlist").post(body).build()
        val json = executeJson(request)
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(PlaylistItem(
                    title = item.optString("title", "Untitled"),
                    url = url,
                    duration = item.optLongOrNull("duration"),
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() }
                ))
            }
        }
    }

    fun resolve(baseUrl: String, mediaUrl: String, kind: String, quality: String): Format {
        val payload = JSONObject().put("url", mediaUrl).put("mode", kind.lowercase()).put("quality", quality)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(normalize(baseUrl) + "/resolve").post(body).build()
        val json = executeJson(request)
        if (json.optString("type") != "media") {
            throw IOException("This URL is a playlist. Use Download Playlist instead.")
        }
        val formats = json.optJSONArray("formats") ?: throw IOException("No downloadable formats returned")
        val candidates = buildList {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val direct = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(Format(
                    url = direct,
                    ext = f.optString("ext").takeIf { it.isNotBlank() },
                    height = f.optInt("height").takeIf { it > 0 },
                    abr = f.optDouble("abr").takeIf { !it.isNaN() && it > 0 },
                    vcodec = f.optString("vcodec").takeIf { it.isNotBlank() },
                    acodec = f.optString("acodec").takeIf { it.isNotBlank() }
                ))
            }
        }
        return choose(candidates, kind, quality) ?: throw IOException("No compatible $kind format returned")
    }

    private fun choose(formats: List<Format>, kind: String, quality: String): Format? {
        val audio = kind.equals("AUDIO", ignoreCase = true)
        val compatible = if (audio) {
            formats.filter { !it.acodec.isNullOrBlank() && it.vcodec == null }
        } else {
            formats.filter { !it.vcodec.isNullOrBlank() && !it.acodec.isNullOrBlank() }
        }
        val pool = if (compatible.isNotEmpty()) compatible else formats
        val requestedHeight = quality.filter(Char::isDigit).toIntOrNull()
        return if (audio) {
            pool.maxByOrNull { it.abr ?: 0.0 }
        } else if (requestedHeight != null) {
            pool.minByOrNull { kotlin.math.abs((it.height ?: 0) - requestedHeight) }
        } else {
            pool.maxByOrNull { it.height ?: 0 }
        }
    }

    private fun get(url: String): JSONObject = executeJson(Request.Builder().url(url).get().build())

    /**
     * The app used to hard-code 10.0.2.2, which is only the Android Emulator's
     * special host address. On a real phone the PC is normally on the same LAN,
     * so a default 10.0.2.2 URL now triggers automatic LAN discovery.
     */
    private fun executeJson(request: Request): JSONObject {
        val candidates = candidateRequests(request)
        var lastError: IOException? = null
        for (candidate in candidates) {
            try {
                return executeOnce(candidate)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Could not connect to the AH Downloader server")
    }

    private fun executeOnce(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("API ${it.code}: ${errorMessage(text)}")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Server returned invalid JSON", e)
            }
        }
    }

    private fun candidateRequests(request: Request): List<Request> {
        val host = request.url.host
        if (host != DEFAULT_EMULATOR_HOST) return listOf(request)

        val localIps = localIpv4Addresses()
        val isEmulatorNetwork = localIps.any { it.startsWith("10.0.2.") }
        if (isEmulatorNetwork) return listOf(request)

        val discoveredHost = discoverServerHost(localIps)
        return if (discoveredHost != null) {
            listOf(request.newBuilder().url(request.url.newBuilder().host(discoveredHost).build()).build())
        } else {
            emptyList()
        }
    }

    private fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * Finds a machine listening on the same /24 subnet on port 8000.
     * Probing is TCP-only and short-lived; no data is sent beyond the connection.
     */
    private fun discoverServerHost(localIps: List<String>): String? {
        val prefix = localIps.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: return null
        val parts = prefix.split('.')
        if (parts.size != 4) return null

        val subnetPrefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val executor = Executors.newFixedThreadPool(32)
        return try {
            val tasks = (1..254).map { host ->
                Callable {
                    val candidate = "$subnetPrefix.$host"
                    if (localIps.contains(candidate)) return@Callable null
                    if (probe(candidate)) candidate else null
                }
            }
            val futures = executor.invokeAll(tasks)
            futures.asSequence().mapNotNull { runCatching { it.get() }.getOrNull() }.firstOrNull()
        } catch (_: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probe(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, API_PORT), DISCOVERY_TIMEOUT_MS)
            socket.soTimeout = DISCOVERY_TIMEOUT_MS
            socket.getOutputStream().write(
                "GET /health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()
            )
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val status = reader.readLine().orEmpty()
            status.startsWith("HTTP/1.1 200") || status.startsWith("HTTP/1.0 200")
        }
    } catch (_: Exception) {
        false
    }

    private fun normalize(baseUrl: String): String = baseUrl.trim().removeSuffix("/")

    private fun errorMessage(body: String): String = try {
        JSONObject(body).optString("detail").ifBlank { body.take(200) }
    } catch (_: Exception) {
        body.take(200)
    }

    companion object {
        private const val DEFAULT_EMULATOR_HOST = "10.0.2.2"
        private const val API_PORT = 8000
        private const val DISCOVERY_TIMEOUT_MS = 180
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name)) null else optLong(name).takeIf { it > 0 }
