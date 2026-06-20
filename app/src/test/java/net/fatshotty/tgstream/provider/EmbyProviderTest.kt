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
}
