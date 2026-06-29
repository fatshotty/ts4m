package net.fatshotty.tgstream.provider

import android.net.Uri
import net.fatshotty.tgstream.emby.EmbyApiClient
import java.util.UUID

/**
 * Emby integration.
 *
 * Launch URL (from Emby external player), e.g.:
 *   http://server:port/.../Videos/{itemId}/stream.mkv?api_key=...
 *   http://server:port/item/{itemId}/stream/video.mkv?api-token=...
 *
 * We extract serverBase + itemId + token, ask Emby for the item, read the
 * resolved .strm content from MediaSources[0].Path, which must look like:
 *   http://IP:PORT/download/{ID}/{channel_id}/{message_id-dash}
 * and parse channel_id + message ids from it.
 */
class EmbyProvider : Provider {
    override val id = "emby"
    override val label = "Emby"

    private val tokenKeys = listOf(
        "api_key", "apikey", "api-token", "apitoken", "x-emby-token", "token", "X-Emby-Token",
    )

    override fun canHandle(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return findToken(uri) != null && findItemId(uri) != null
    }

    override fun resolve(uri: Uri): ResolveResult {
        val serverBase = (uri.scheme ?: "http") + "://" + (uri.authority
            ?: throw ProviderException("Emby URL has no host"))
        val token = findToken(uri)
            ?: throw ProviderException("Emby token not found in URL")
        val itemId = findItemId(uri)
            ?: throw ProviderException("Emby itemId not found in URL")

        // Reuse the session/device identity Emby put in the launch URL so our
        // playback reports attach to the very session Emby already opened —
        // exactly what the native player does.
        val deviceId = queryParam(uri, "DeviceId")
        val mediaSourceId = queryParam(uri, "MediaSourceId")
        val playSessionId = queryParam(uri, "PlaySessionId")
            .ifBlank { UUID.randomUUID().toString().replace("-", "") }

        val client = EmbyApiClient(serverBase, token, deviceId)
        val userId = runCatching { client.userId() }.getOrDefault("")
        val info = client.item(userId, itemId)

        val (channelId, messages) = parseDownloadUrl(info.path)

        val runtimeMs = info.runtimeTicks / 10_000
        val positionMs = info.positionTicks / 10_000

        return ResolveResult(
            stream = StreamSpec(
                channelId = channelId,
                messagesDash = messages,
                title = info.name,
                startPositionMs = positionMs,
                durationMs = runtimeMs,
            ),
            reporting = ReportingContext(
                kind = "emby",
                serverBase = serverBase,
                itemId = itemId,
                token = token,
                playSessionId = playSessionId,
                deviceId = deviceId,
                mediaSourceId = mediaSourceId,
            ),
        )
    }

    /** Case-insensitive query-param lookup (Emby uses PascalCase keys). */
    private fun queryParam(uri: Uri, key: String): String {
        uri.getQueryParameter(key)?.let { if (it.isNotBlank()) return it }
        for (name in uri.queryParameterNames) {
            if (name.equals(key, ignoreCase = true)) {
                val v = uri.getQueryParameter(name)
                if (!v.isNullOrBlank()) return v
            }
        }
        return ""
    }

    private fun findToken(uri: Uri): String? {
        for (k in tokenKeys) {
            val v = uri.getQueryParameter(k)
            if (!v.isNullOrBlank()) return v
        }
        // case-insensitive scan
        for (name in uri.queryParameterNames) {
            if (name.lowercase() in tokenKeys.map { it.lowercase() }) {
                val v = uri.getQueryParameter(name)
                if (!v.isNullOrBlank()) return v
            }
        }
        return null
    }

    private fun findItemId(uri: Uri): String? = extractItemId(uri.pathSegments)

    companion object {
        private val itemMarkers = setOf("videos", "items", "item")
        private val mediaExtensions = setOf(
            "mkv", "mp4", "m4v", "ts", "m3u8", "mpd", "avi", "mov", "webm",
            "strm", "mp3", "flac", "aac", "wav", "ogg", "m4a",
        )

        /**
         * Extracts the Emby/Jellyfin item id from URL path segments, e.g.
         *   /emby/Videos/{id}/stream.mkv      ->  {id}
         *   /Items/{id}/PlaybackInfo          ->  {id}
         *
         * The id can be any of: a small integer ("7"), a 32-hex GUID, or a
         * dashed GUID (Jellyfin). The segment right after a marker IS the id by
         * construction, so we trust the position and only reject filename-like
         * tokens. Without a marker we fall back to the strongest id-looking
         * segment.
         */
        fun extractItemId(segs: List<String>): String? {
            for (i in segs.indices) {
                if (segs[i].lowercase() in itemMarkers && i + 1 < segs.size) {
                    val cand = segs[i + 1]
                    if (isPlausibleId(cand)) return cand
                }
            }
            return segs.firstOrNull { isStrongId(it) }
        }

        /** Any id token following a marker: digits/letters/dashes, never a filename. */
        private fun isPlausibleId(s: String): Boolean {
            if (s.isEmpty()) return false
            if (!s.all { it.isLetterOrDigit() || it == '-' }) return false // excludes "stream.mkv"
            if (s.substringAfterLast('.', "").lowercase() in mediaExtensions) return false
            return s.any { it.isLetterOrDigit() }
        }

        /** Stricter id used when there is no marker to anchor on. */
        private fun isStrongId(s: String): Boolean {
            val compact = s.replace("-", "")
            if (compact.length >= 30 && compact.all { it.isLetterOrDigit() }) return true // GUID (±dashes)
            if (s.length in 6..20 && s.all { it.isDigit() }) return true // long numeric id
            return false
        }
    }

    /** Parses http://IP:PORT/download/{ID}/{channel_id}/{m1-m2-...}. */
    private fun parseDownloadUrl(raw: String): Pair<Long, String> {
        if (raw.isBlank()) throw ProviderException("Emby: empty media path (.strm)")
        val u = runCatching { Uri.parse(raw.trim()) }.getOrNull()
            ?: throw ProviderException("Emby: .strm is not a valid URL: $raw")
        val segs = u.pathSegments
        val idx = segs.indexOfFirst { it.equals("download", ignoreCase = true) }
        if (idx < 0 || idx + 3 > segs.size) {
            throw ProviderException("Emby: unexpected .strm format: $raw")
        }
        val channelStr = segs[idx + 2]
        val messages = segs[idx + 3]
        val channelId = channelStr.toLongOrNull()
            ?: throw ProviderException("Emby: non-numeric channel_id in .strm: $channelStr")
        if (messages.isBlank()) throw ProviderException("Emby: missing message ids in .strm")
        return channelId to messages
    }
}
