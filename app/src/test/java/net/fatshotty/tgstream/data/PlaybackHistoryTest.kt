package net.fatshotty.tgstream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHistoryTest {
    @Test fun upsertSortsByLastPlayedDescending() {
        val first = item("1", "10", 1_000)
        val second = item("2", "20", 2_000)

        val history = PlaybackHistoryCodec.upsert(listOf(first), second)

        assertEquals(listOf(second, first), history)
    }

    @Test fun upsertReplacesDuplicateStreamAndPreservesExistingTitleWhenIncomingIsBlank() {
        val old = item("1", "10-11", 1_000, title = "Known title", source = "emby")
        val incoming = item("1", "10-11", 2_000, title = "", source = "direct")

        val history = PlaybackHistoryCodec.upsert(listOf(old), incoming)

        assertEquals(1, history.size)
        assertEquals("Known title", history[0].title)
        assertEquals("direct", history[0].source)
        assertEquals(2_000, history[0].lastPlayedAtMs)
    }

    @Test fun upsertLimitsToFiftyItems() {
        val initial = (1L..50L).map { index -> item(index.toString(), "10", index) }

        val history = PlaybackHistoryCodec.upsert(initial, item("51", "10", 51))

        assertEquals(50, history.size)
        assertEquals("51", history.first().channelId.toString())
        assertEquals("2", history.last().channelId.toString())
    }

    @Test fun encodeDecodeRoundTrip() {
        val items = listOf(
            PlaybackHistoryItem(
                channelId = -100123,
                messagesDash = "10-11",
                title = "Movie\tTitle",
                source = "emby",
                lastPlayedAtMs = 123_456,
                startPositionMs = 90_000,
            ),
        )

        val decoded = PlaybackHistoryCodec.decode(PlaybackHistoryCodec.encode(items))

        assertEquals(items, decoded)
    }

    private fun item(
        channel: String,
        messages: String,
        lastPlayedAtMs: Long,
        title: String = "",
        source: String = "direct",
    ): PlaybackHistoryItem {
        return PlaybackHistoryItem(
            channelId = channel.toLong(),
            messagesDash = messages,
            title = title,
            source = source,
            lastPlayedAtMs = lastPlayedAtMs,
        )
    }
}
