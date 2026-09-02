package com.themeslover.ytdapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class ApiClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    data class SearchItem(
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
                    duration = if (item.isNull("duration")) null else item.optLong("duration"),
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() }
                ))
            }
        }
    }

    fun resolve(baseUrl: String, mediaUrl: String, kind: String, quality: String): Format {
        val payload = JSONObject().put("url", mediaUrl).put("mode", kind.lowercase()).put("quality", quality)
        val body = okhttp3.RequestBody.create("application/json".toMediaTypeCompat(), payload.toString())
        val request = Request.Builder().url(normalize(baseUrl) + "/resolve").post(body).build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("API ${it.code}: ${errorMessage(text)}")
            val json = JSONObject(text)
            if (json.optString("type") != "media") throw IOException("This link is a playlist; open a video instead")
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
            val chosen = choose(candidates, kind, quality) ?: throw IOException("No compatible $kind format returned")
            return chosen
        }
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

    private fun get(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("API ${it.code}: ${errorMessage(text)}")
            return JSONObject(text)
        }
    }

    private fun normalize(baseUrl: String): String = baseUrl.trim().removeSuffix("/")

    private fun errorMessage(body: String): String = try {
        JSONObject(body).optString("detail").ifBlank { body.take(160) }
    } catch (_: Exception) {
        body.take(160)
    }
}

private fun String.toMediaTypeCompat() = okhttp3.MediaType.Companion.parse(this)
