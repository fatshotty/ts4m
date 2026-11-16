package org.tgstream.app.emby

import org.tgstream.app.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reports playback to an Emby server: start / progress / stopped plus explicit
 * watched-state updates near completion. All calls are blocking; invoke off the
 * main thread. Failures are swallowed (reporting must never crash playback) but
 * logged to [AppLog] so the HTTP result is visible in the on-device log.
 *
 * The DeviceId/PlaySessionId/MediaSourceId come from the Emby launch URL, so our
 * reports attach to the session Emby already opened — required for the resume
 * position to actually be saved against the user.
 */
class EmbyReporter(
    private val serverBase: String,
    private val token: String,
    private val itemId: String,
    private val userId: String,
    private val playSessionId: String,
    private val deviceId: String,
    private val mediaSourceId: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()
    private val authHeader = EmbyAuth.header(token, deviceId)

    private fun post(path: String, body: JSONObject): Boolean {
        try {
            val url = serverBase.trimEnd('/') + path + "?api_key=" + token
            val req = Request.Builder()
                .url(url)
                .header("X-Emby-Token", token)
                .header("X-Emby-Authorization", authHeader)
                .post(body.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { resp ->
                AppLog.i("EmbyReporter", "POST $path -> ${resp.code}")
                return resp.isSuccessful
            }
        } catch (t: Throwable) {
            AppLog.w("EmbyReporter", "POST $path failed", t)
        }
        return false
    }

    private fun postEmpty(path: String): Boolean {
        try {
            val url = serverBase.trimEnd('/') + path +
                (if (path.contains("?")) "&" else "?") + "api_key=" + token
            val req = Request.Builder()
                .url(url)
                .header("X-Emby-Token", token)
                .header("X-Emby-Authorization", authHeader)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(req).execute().use { resp ->
                AppLog.i("EmbyReporter", "POST $path -> ${resp.code}")
                return resp.isSuccessful
            }
        } catch (t: Throwable) {
            AppLog.w("EmbyReporter", "POST $path failed", t)
        }
        return false
    }

    private fun base(positionMs: Long, paused: Boolean) = JSONObject().apply {
        put("ItemId", itemId)
        if (mediaSourceId.isNotBlank()) put("MediaSourceId", mediaSourceId)
        put("PlaySessionId", playSessionId)
        put("PositionTicks", positionMs * 10_000)
        put("IsPaused", paused)
        put("IsMuted", false)
        put("CanSeek", true)
        put("PlayMethod", "DirectPlay")
        put("RepeatMode", "RepeatNone")
    }

    fun start(positionMs: Long) = post("/Sessions/Playing", base(positionMs, false))

    fun progress(positionMs: Long, paused: Boolean, eventName: String = "TimeUpdate") =
        post("/Sessions/Playing/Progress", base(positionMs, paused).apply {
            put("EventName", eventName)
        })

    fun stopped(positionMs: Long) {
        val b = JSONObject().apply {
            put("ItemId", itemId)
            if (mediaSourceId.isNotBlank()) put("MediaSourceId", mediaSourceId)
            put("PlaySessionId", playSessionId)
            put("PositionTicks", positionMs * 10_000)
        }
        post("/Sessions/Playing/Stopped", b)
    }

    fun markPlayed() {
        if (userId.isBlank()) {
            AppLog.w("EmbyReporter", "Cannot mark item played: missing userId")
            return
        }
        if (postEmpty("/Users/$userId/PlayedItems/$itemId")) return
        postEmpty("/UserPlayedItems/$itemId?userId=$userId")
    }
}
