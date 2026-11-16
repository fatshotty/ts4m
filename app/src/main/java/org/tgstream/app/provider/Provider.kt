package org.tgstream.app.provider

import android.net.Uri

/** What to stream: a stable logical id plus Telegram channel and message parts. */
data class StreamSpec(
    val streamId: String = "",
    val channelId: Long,
    val messagesDash: String, // e.g. "10-11"
    val title: String = "",
    val startPositionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Where/how to report playback progress back to the source app. */
data class ReportingContext(
    val kind: String,          // "emby" or "none"
    val serverBase: String = "",
    val itemId: String = "",
    val token: String = "",
    val userId: String = "",
    val playSessionId: String = "",
    val deviceId: String = "",
    val mediaSourceId: String = "",
)

data class ResolveResult(
    val stream: StreamSpec,
    val reporting: ReportingContext,
)

/** A source app integration. */
interface Provider {
    val id: String
    val label: String
    fun canHandle(uri: Uri): Boolean
    /** Network + parsing; call off the main thread. Throws on failure. */
    fun resolve(uri: Uri): ResolveResult
}

class ProviderException(message: String) : Exception(message)
