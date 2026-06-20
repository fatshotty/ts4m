package net.fatshotty.tgstream.emby

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Emby API client. Talks to the server using the token extracted from
 * the launch URL. All calls are blocking — invoke off the main thread.
 */
class EmbyApiClient(
    private val serverBase: String,
    private val token: String,
    private val deviceId: String = "",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val authHeader = EmbyAuth.header(token, deviceId)

    data class ItemInfo(
        val name: String,
        val path: String,            // resolved .strm content (our download URL)
        val runtimeTicks: Long,
        val positionTicks: Long,
    )

    private fun get(path: String): String {
        val url = serverBase.trimEnd('/') + path +
            (if (path.contains("?")) "&" else "?") + "api_key=" + token
        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Token", token)
            .header("X-Emby-Authorization", authHeader)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Emby ${resp.code} on $path: ${body.take(200)}")
            }
            return body
        }
    }

    /** Returns the current user's id (the token's user). */
    fun userId(): String {
        val json = JSONObject(get("/Users/Me"))
        return json.optString("Id")
    }

    fun item(userId: String, itemId: String): ItemInfo {
        val o = fetchItem(userId, itemId)
        val name = o.optString("Name")
        val runtime = o.optLong("RunTimeTicks", 0)
        val userData = o.optJSONObject("UserData")
        val pos = userData?.optLong("PlaybackPositionTicks", 0) ?: 0
        val srcs = o.optJSONArray("MediaSources")
        var mediaPath = ""
        if (srcs != null && srcs.length() > 0) {
            mediaPath = srcs.getJSONObject(0).optString("Path")
        }
        if (mediaPath.isBlank()) mediaPath = o.optString("Path")
        return ItemInfo(name, mediaPath, runtime, pos)
    }

    /**
     * Fetches the item JSON. With a user-bound token we use the per-user
     * endpoint (carries UserData for resume). With a server api_key (no user)
     * `/Users/.../Items` and `/Items/{id}` 404, so we query `/Items?Ids={id}`,
     * which works for every Emby item id type.
     */
    private fun fetchItem(userId: String, itemId: String): JSONObject {
        if (userId.isNotBlank()) {
            return JSONObject(get("/Users/$userId/Items/$itemId"))
        }
        val q = JSONObject(get("/Items?Ids=$itemId&Fields=Path,MediaSources"))
        val arr = q.optJSONArray("Items")
        if (arr != null && arr.length() > 0) return arr.getJSONObject(0)
        throw RuntimeException("Emby: item $itemId non trovato")
    }
}
