package net.fatshotty.tgstream.data

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

data class PlaybackHistoryItem(
    val channelId: Long,
    val messagesDash: String,
    val title: String = "",
    val source: String = "",
    val lastPlayedAtMs: Long,
    val startPositionMs: Long = 0,
) {
    val streamKey: String get() = "$channelId:$messagesDash"
}

object PlaybackHistoryStore {
    private const val PREFS = "tgstream"
    private const val KEY_HISTORY = "playback_history"

    fun recent(ctx: Context): List<PlaybackHistoryItem> {
        val raw = prefs(ctx).getString(KEY_HISTORY, "") ?: ""
        return PlaybackHistoryCodec.decode(raw)
    }

    fun record(ctx: Context, item: PlaybackHistoryItem) {
        val updated = PlaybackHistoryCodec.upsert(recent(ctx), item)
        prefs(ctx).edit().putString(KEY_HISTORY, PlaybackHistoryCodec.encode(updated)).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object PlaybackHistoryCodec {
    private const val MAX_ITEMS = 50
    private const val FIELD_SEPARATOR = "\t"

    fun upsert(
        existing: List<PlaybackHistoryItem>,
        incoming: PlaybackHistoryItem,
        maxItems: Int = MAX_ITEMS,
    ): List<PlaybackHistoryItem> {
        val previous = existing.firstOrNull { it.streamKey == incoming.streamKey }
        val item = if (incoming.title.isBlank() && previous != null) {
            incoming.copy(title = previous.title)
        } else {
            incoming
        }
        return (existing.filterNot { it.streamKey == incoming.streamKey } + item)
            .sortedByDescending { it.lastPlayedAtMs }
            .take(maxItems)
    }

    fun encode(items: List<PlaybackHistoryItem>): String {
        return items.joinToString("\n") { item ->
            listOf(
                item.channelId.toString(),
                encodeField(item.messagesDash),
                encodeField(item.title),
                encodeField(item.source),
                item.lastPlayedAtMs.toString(),
                item.startPositionMs.toString(),
            ).joinToString(FIELD_SEPARATOR)
        }
    }

    fun decode(raw: String): List<PlaybackHistoryItem> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line -> decodeLine(line) }
            .sortedByDescending { it.lastPlayedAtMs }
            .take(MAX_ITEMS)
            .toList()
    }

    private fun decodeLine(line: String): PlaybackHistoryItem? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 6) return null
        val channelId = parts[0].toLongOrNull() ?: return null
        val lastPlayedAtMs = parts[4].toLongOrNull() ?: return null
        val startPositionMs = parts[5].toLongOrNull() ?: 0
        return PlaybackHistoryItem(
            channelId = channelId,
            messagesDash = decodeField(parts[1]),
            title = decodeField(parts[2]),
            source = decodeField(parts[3]),
            lastPlayedAtMs = lastPlayedAtMs,
            startPositionMs = startPositionMs,
        )
    }

    private fun encodeField(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeField(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
