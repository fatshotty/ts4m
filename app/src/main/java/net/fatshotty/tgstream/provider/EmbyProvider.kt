package net.fatshotty.tgstream.provider

import android.net.Uri
import net.fatshotty.tgstream.emby.EmbyApiClient
import net.fatshotty.tgstream.util.AppLog
import java.net.URI
import java.util.UUID

/**
 * Emby/Jellyfin integration.
 *
 * Launch URL (from Emby external player), e.g.:
 *   http://server:port/.../Videos/{itemId}/stream.mkv?api_key=...
 *   http://server:port/item/{itemId}/stream/video.mkv?api-token=...
 *
 * We extract serverBase + itemId + token, ask the media server for the item, read the
 * resolved .strm content from MediaSources[0].Path, and parse the tgstream
 * directive from its fragment:
 *   http://host/playable-file.mkv#tgstream/{channel_id}/{message_id-dash}
 *
 * The URL before `#` remains a normal Emby/ffmpeg-playable URL. The stream id is
 * generated internally from the resolved Telegram coordinates.
 *
 * If the launch URL carries StartTimeTicks/StartPositionTicks, that explicit
 * playback choice wins over the saved item resume position.
 */
class EmbyProvider : Provider {
    override val id = "emby"
    override val label = "Emby/Jellyfin"

    private val tokenKeys = listOf(
        "api_key", "apikey", "api-token", "apitoken", "x-emby-token", "token", "X-Emby-Token",
    )

    override fun canHandle(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return findToken(uri) != null && findItemId(uri) != null
    }

    override fun resolve(uri: Uri): ResolveResult {
        val serverBase = serverBase(uri)
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

        AppLog.i(
            TAG,
            "Emby resolve: serverBase=$serverBase itemId=$itemId " +
                "deviceId=${deviceId.isNotBlank()} mediaSourceId=${mediaSourceId.isNotBlank()} " +
                "playSessionId=${playSessionId.isNotBlank()}",
        )

        val client = EmbyApiClient(serverBase, token, deviceId)
        val userId = runCatching { client.userId() }.getOrDefault("")
        AppLog.i(TAG, "Emby userId resolved: ${userId.isNotBlank()}")
        val info = client.item(userId, itemId)
        AppLog.i(
            TAG,
            "Emby item: name=${info.name.take(80)} runtimeTicks=${info.runtimeTicks} " +
                "positionTicks=${info.positionTicks} path=${AppLog.redactUrl(info.path)}",
        )

        val stream = parseStrmStreamSpec(info.path)
        AppLog.i(
            TAG,
            "Emby tgstream directive: channel=${stream.channelId} messages=${stream.messagesDash}",
        )

        val runtimeMs = info.runtimeTicks / 10_000
        val positionMs = resolveStartPositionMs(uri.toString(), info.positionTicks)

        return ResolveResult(
            stream = stream.copy(
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
        private const val TAG = "EmbyProvider"
        private val itemMarkers = setOf("videos", "items", "item")
        private val apiBaseMarkers = itemMarkers + "users"
        private val mediaExtensions = setOf(
            "mkv", "mp4", "m4v", "ts", "m3u8", "mpd", "avi", "mov", "webm",
            "strm", "mp3", "flac", "aac", "wav", "ogg", "m4a",
        )

        fun serverBase(uri: Uri): String {
            return serverBase(uri.scheme, uri.authority, uri.pathSegments)
        }

        fun serverBase(scheme: String?, authority: String?, pathSegments: List<String>): String {
            val normalizedScheme = scheme ?: "http"
            val normalizedAuthority = authority ?: throw ProviderException("Emby URL has no host")
            val markerIndex = pathSegments.indexOfFirst { it.lowercase() in apiBaseMarkers }
            val prefix = when {
                markerIndex > 0 -> pathSegments.take(markerIndex).joinToString(prefix = "/", separator = "/")
                else -> ""
            }
            return "$normalizedScheme://$normalizedAuthority$prefix"
        }

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

        fun parseStrmStreamSpec(raw: String): StreamSpec {
            if (raw.isBlank()) throw ProviderException("Emby: empty media path (.strm)")
            parseFragmentDirective(fragmentDirective(raw))?.let { return it }

            throw ProviderException("Emby: tgstream directive not found in .strm")
        }

        fun resolveStartPositionMs(launchUrl: String, savedPositionTicks: Long): Long {
            val explicitTicks = launchStartTicks(launchUrl)
            return (explicitTicks ?: savedPositionTicks).coerceAtLeast(0) / 10_000
        }

        private fun launchStartTicks(launchUrl: String): Long? {
            val u = runCatching { URI(launchUrl.trim()) }.getOrNull() ?: return null
            val params = queryParams(u.rawQuery ?: return null)
            for (key in startTickKeys) {
                val raw = params.entries
                    .firstOrNull { it.key.equals(key, ignoreCase = true) }
                    ?.value
                    ?.trim()
                    .orEmpty()
                if (raw.isBlank()) continue
                return raw.toLongOrNull()?.takeIf { it >= 0 }
            }
            return null
        }

        private fun queryParams(rawQuery: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (part in rawQuery.split('&')) {
                if (part.isBlank()) continue
                val key = part.substringBefore('=', "")
                if (key.isBlank()) continue
                result[decode(key)] = decode(part.substringAfter('=', ""))
            }
            return result
        }

        private fun parseFragmentDirective(fragment: String?): StreamSpec? {
            if (fragment.isNullOrBlank()) return null
            val parts = fragment.trim('/').split('/').filter { it.isNotBlank() }
            if (parts.firstOrNull()?.equals("tgstream", ignoreCase = true) != true) return null
            return when (parts.size) {
                3 -> streamSpec(parts[1], parts[2])
                else -> throw ProviderException("Emby: invalid tgstream directive")
            }
        }

        private fun fragmentDirective(raw: String): String? {
            val text = raw.trim()
            val marker = text.indexOf('#')
            if (marker < 0) return null
            return text.substring(marker + 1)
        }

        private fun streamSpec(channelText: String, messagesText: String): StreamSpec {
            val channelId = channelText.toLongOrNull()
                ?: throw ProviderException("Emby: non-numeric channel_id in .strm: $channelText")
            return DirectLinkCodec.parse(
                "tgstream://play?channel=$channelId&messages=${messagesText.trim()}",
            )
        }

        private fun decode(value: String): String = java.net.URLDecoder.decode(value, Charsets.UTF_8.name())

        private val startTickKeys = listOf(
            "StartTimeTicks",
            "StartPositionTicks",
        )
    }
}
