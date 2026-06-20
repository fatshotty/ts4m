package net.fatshotty.tgstream.provider

import android.net.Uri

/** What to stream: a Telegram channel + ordered message-id parts. */
data class StreamSpec(
    val channelId: Long,
    val messagesDash: String, // e.g. "10-11"
    val title: String = "",
    val startPositionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Where/how to report playback progress back to the source app (e.g. Emby). */
data class ReportingContext(
    val kind: String,          // "emby" or "none"
    val serverBase: String = "",
    val itemId: String = "",
    val token: String = "",
    val playSessionId: String = "",
    val deviceId: String = "",
    val mediaSourceId: String = "",
)

data class ResolveResult(
    val stream: StreamSpec,
    val reporting: ReportingContext,
)

/** A source app integration (Emby first). */
interface Provider {
    val id: String
    val label: String
    fun canHandle(uri: Uri): Boolean
    /** Network + parsing; call off the main thread. Throws on failure. */
    fun resolve(uri: Uri): ResolveResult
}

class ProviderException(message: String) : Exception(message)
