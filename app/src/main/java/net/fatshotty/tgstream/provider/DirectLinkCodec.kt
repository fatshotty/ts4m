package net.fatshotty.tgstream.provider

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToLong

/** Parser/builder for the canonical tgstream://play direct-link format. */
object DirectLinkCodec {
    const val SCHEME = "tgstream"
    const val HOST_PLAY = "play"

    fun canParse(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals(SCHEME, ignoreCase = true) &&
            uri.host.equals(HOST_PLAY, ignoreCase = true)
    }

    fun parse(raw: String): StreamSpec {
        val uri = parseUri(raw)
        if (!canParse(raw)) {
            throw ProviderException("Direct link: unsupported URI")
        }
        val query = queryParams(uri)

        val channelText = queryParam(query, "channel").ifBlank { queryParam(query, "channel_id") }
        if (channelText.isBlank()) {
            throw ProviderException("Direct link: missing channel")
        }
        val channel = channelText.toLongOrNull()
            ?: throw ProviderException("Direct link: invalid channel: $channelText")

        val messagesText = queryParam(query, "messages").ifBlank { queryParam(query, "message_ids") }
        if (messagesText.isBlank()) {
            throw ProviderException("Direct link: missing messages")
        }

        val posText = queryParam(query, "pos")
        val posMs = if (posText.isBlank()) 0L else {
            val seconds = posText.toDoubleOrNull()
                ?: throw ProviderException("Direct link: invalid pos: $posText")
            if (seconds < 0.0) {
                throw ProviderException("Direct link: invalid pos: $posText")
            }
            (seconds * 1000.0).roundToLong()
        }

        return StreamSpec(
            channelId = channel,
            messagesDash = normalizeMessages(messagesText),
            title = queryParam(query, "title"),
            startPositionMs = posMs,
        )
    }

    fun build(spec: StreamSpec): String {
        val params = mutableListOf(
            "channel=${encode(spec.channelId.toString())}",
            "messages=${encode(normalizeMessages(spec.messagesDash))}",
        )
        if (spec.title.isNotBlank()) {
            params += "title=${encode(spec.title)}"
        }
        if (spec.startPositionMs > 0) {
            val pos = String.format(Locale.US, "%.3f", spec.startPositionMs / 1000.0)
                .trimEnd('0')
                .trimEnd('.')
            params += "pos=${encode(pos)}"
        }
        return "$SCHEME://$HOST_PLAY?${params.joinToString("&")}"
    }

    private fun parseUri(raw: String): URI =
        runCatching { URI(raw.trim()) }
            .getOrElse { throw ProviderException("Direct link: invalid URI") }

    private fun queryParams(uri: URI): Map<String, String> {
        val rawQuery = uri.rawQuery ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=', "")
            if (key.isBlank()) continue
            val value = part.substringAfter('=', "")
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun queryParam(params: Map<String, String>, key: String): String =
        params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.trim().orEmpty()

    private fun normalizeMessages(raw: String): String {
        val parts = raw.trim().split('-', ',')
        if (parts.isEmpty()) {
            throw ProviderException("Direct link: missing messages")
        }
        return parts.joinToString("-") { part ->
            val id = part.trim()
            val n = id.toLongOrNull()
                ?: throw ProviderException("Direct link: invalid message id: $id")
            if (n <= 0) {
                throw ProviderException("Direct link: invalid message id: $id")
            }
            n.toString()
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
