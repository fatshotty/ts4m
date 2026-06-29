package net.fatshotty.tgstream.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Path-segment id extraction must cover every Emby/Jellyfin item id shape. */
class EmbyProviderTest {

    private fun segs(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    private fun id(path: String): String? = EmbyProvider.extractItemId(segs(path))

    @Test fun smallNumericIdAfterVideos() {
        assertEquals("7", id("/emby/videos/7/stream.mkv"))
    }

    @Test fun longNumericId() {
        assertEquals("123456", id("/emby/Videos/123456/stream"))
    }

    @Test fun hexGuid32NoDashes() {
        val g = "c5aab7af7c564e3bbe03f429d108e98a"
        assertEquals(g, id("/emby/Items/$g/PlaybackInfo"))
    }

    @Test fun dashedGuidJellyfin() {
        val g = "c5aab7af-7c56-4e3b-be03-f429d108e98a"
        assertEquals(g, id("/Videos/$g/stream.mkv"))
    }

    @Test fun itemSingularMarker() {
        assertEquals("42", id("/item/42/stream/video.mkv"))
    }

    @Test fun caseInsensitiveMarker() {
        assertEquals("7", id("/emby/VIDEOS/7/stream.mkv"))
    }

    @Test fun noEmbyPrefix() {
        assertEquals("9", id("/videos/9/stream.ts"))
    }

    @Test fun fallbackGuidWithoutMarker() {
        val g = "c5aab7af7c564e3bbe03f429d108e98a"
        assertEquals(g, id("/some/path/$g/play.m3u8"))
    }

    @Test fun fallbackLongNumericWithoutMarker() {
        assertEquals("123456", id("/x/123456/play"))
    }

    @Test fun filenameAfterMarkerIsNotId() {
        // marker present but immediately followed by a filename -> no id
        assertNull(id("/videos/stream.mkv"))
    }

    @Test fun emptyPath() {
        assertNull(id("/"))
    }

    @Test fun serverBaseKeepsEmbyPrefix() {
        val base = EmbyProvider.serverBase(
            scheme = "https",
            authority = "media.example.com",
            pathSegments = listOf("emby", "videos", "7", "stream.mkv"),
        )

        assertEquals("https://media.example.com/emby", base)
    }

    @Test fun serverBaseKeepsJellyfinPrefix() {
        val base = EmbyProvider.serverBase(
            scheme = "https",
            authority = "media.example.com",
            pathSegments = listOf("jellyfin", "Videos", "7", "stream.mkv"),
        )

        assertEquals("https://media.example.com/jellyfin", base)
    }

    @Test fun parsesFragmentDirectiveWithoutChangingPlayableUrl() {
        val spec = EmbyProvider.parseStrmStreamSpec(
            "http://172.17.0.1:3000/download/lvbrj3dna7/-1003847124457/10-11#tgstream/-1003847124457/10,11",
        )

        assertEquals("-1003847124457:10-11", spec.streamId)
        assertEquals(-1003847124457L, spec.channelId)
        assertEquals("10-11", spec.messagesDash)
    }

    @Test fun rejectsFragmentDirectiveWithExplicitStreamId() {
        assertProviderError("Emby: invalid tgstream directive") {
            EmbyProvider.parseStrmStreamSpec(
                "https://cdn.example.com/movie.mkv#tgstream/movie-abc/-1003847124457/10-11",
            )
        }
    }

    @Test fun rejectsLegacyDownloadPathWithoutTgstreamDirective() {
        assertProviderError("Emby: tgstream directive not found") {
            EmbyProvider.parseStrmStreamSpec(
                "http://server/download/lvbrj3dna7/-1003847124457/10-11?token=ignored",
            )
        }
    }

    @Test fun rejectsLoosePathWithoutTgstreamDirective() {
        assertProviderError("Emby: tgstream directive not found") {
            EmbyProvider.parseStrmStreamSpec(
                "http://server/any/path/-1003847124457/10-11",
            )
        }
    }

    @Test fun rejectsStrmWithoutTgstreamCoordinates() {
        assertProviderError("Emby: tgstream directive not found") {
            EmbyProvider.parseStrmStreamSpec(
                "https://cdn.example.com/movie.mkv#not-tgstream",
            )
        }
    }

    @Test fun explicitZeroStartTimeTicksOverridesSavedResumePosition() {
        val startMs = EmbyProvider.resolveStartPositionMs(
            "https://server/emby/videos/7/stream.mkv?StartTimeTicks=0&api_key=x",
            savedPositionTicks = 900_000_000,
        )

        assertEquals(0L, startMs)
    }

    @Test fun explicitStartTimeTicksOverridesSavedResumePosition() {
        val startMs = EmbyProvider.resolveStartPositionMs(
            "https://server/emby/videos/7/stream.mkv?StartTimeTicks=905000000&api_key=x",
            savedPositionTicks = 900_000_000,
        )

        assertEquals(90_500L, startMs)
    }

    @Test fun missingStartTimeTicksFallsBackToSavedResumePosition() {
        val startMs = EmbyProvider.resolveStartPositionMs(
            "https://server/emby/videos/7/stream.mkv?api_key=x",
            savedPositionTicks = 900_000_000,
        )

        assertEquals(90_000L, startMs)
    }

    private fun assertProviderError(expectedPrefix: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            org.junit.Assert.assertTrue("message=${e.message}", e.message?.startsWith(expectedPrefix) == true)
        }
    }
}
