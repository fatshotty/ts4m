package org.tgstream.app.ui

import org.tgstream.app.provider.DirectLinkCodec
import org.tgstream.app.provider.ProviderException
import org.tgstream.app.provider.StreamSpec
import kotlin.math.roundToLong

object ManualPlayRequest {
    fun toDirectLink(
        channelText: String,
        messagesText: String,
        titleText: String,
        positionSecondsText: String,
    ): String {
        val channel = channelText.trim().ifBlank {
            throw ProviderException("Manual play: missing channel_id")
        }.toLongOrNull()
            ?: throw ProviderException("Manual play: invalid channel_id")

        val messages = messagesText.trim().ifBlank {
            throw ProviderException("Manual play: missing message_ids")
        }

        val startPositionMs = positionMs(positionSecondsText)
        val uri = DirectLinkCodec.build(
            StreamSpec(
                channelId = channel,
                messagesDash = messages,
                title = titleText.trim(),
                startPositionMs = startPositionMs,
            ),
        )
        DirectLinkCodec.parse(uri)
        return uri
    }

    private fun positionMs(positionSecondsText: String): Long {
        val text = positionSecondsText.trim()
        if (text.isBlank()) return 0
        val seconds = text.toDoubleOrNull()
            ?: throw ProviderException("Manual play: invalid position")
        if (seconds < 0.0) {
            throw ProviderException("Manual play: invalid position")
        }
        return (seconds * 1000.0).roundToLong()
    }
}
