package org.tgstream.app.player.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlsModelTest {
    @Test fun formatsChapterFallbackWithTimeOffset() {
        val option = PlayerOption.chapter(
            id = "2",
            index = 2,
            name = "",
            timeOffsetMs = 65_000,
            selected = false,
        )

        assertEquals("Chapter 3", option.title)
        assertEquals("1:05", option.subtitle)
    }

    @Test fun keepsNamedChapterTitle() {
        val option = PlayerOption.chapter(
            id = "0",
            index = 0,
            name = "Opening",
            timeOffsetMs = 3_600_000,
            selected = true,
        )

        assertEquals("Opening", option.title)
        assertEquals("1:00:00", option.subtitle)
        assertEquals(true, option.selected)
    }

    @Test fun formatsTrackDetails() {
        val option = PlayerOption.track(
            id = "audio:0",
            title = "English",
            language = "eng",
            details = "AC-3 6ch",
            selected = true,
        )

        assertEquals("English", option.title)
        assertEquals("eng - AC-3 6ch", option.subtitle)
    }

    @Test fun hidesSingleVideoTrackButKeepsStatsVisible() {
        val visibility = ControlVisibility.fromOptions(
            audio = listOf(PlayerOption.track("a0", "English")),
            subtitles = emptyList(),
            video = listOf(PlayerOption.track("v0", "1080p")),
            chapters = emptyList(),
        )

        assertEquals(true, visibility.audio)
        assertEquals(false, visibility.subtitles)
        assertEquals(false, visibility.video)
        assertEquals(false, visibility.chapters)
        assertEquals(true, visibility.stats)
    }

    @Test fun showsChaptersWhenPresent() {
        val visibility = ControlVisibility.fromOptions(
            audio = emptyList(),
            subtitles = emptyList(),
            video = emptyList(),
            chapters = listOf(PlayerOption.chapter("0", 0, "", 0, false)),
        )

        assertEquals(true, visibility.chapters)
    }

    @Test fun hidesDisabledOptions() {
        val visibility = ControlVisibility.fromOptions(
            audio = listOf(PlayerOption.track("a0", "English", enabled = false)),
            subtitles = listOf(PlayerOption.track("s0", "English", enabled = false)),
            video = emptyList(),
            chapters = listOf(PlayerOption("c0", "Chapter 1", enabled = false)),
        )

        assertEquals(false, visibility.audio)
        assertEquals(false, visibility.subtitles)
        assertEquals(false, visibility.chapters)
    }

    @Test fun showsVideoWhenMultipleEnabledTracksArePresent() {
        val visibility = ControlVisibility.fromOptions(
            audio = emptyList(),
            subtitles = emptyList(),
            video = listOf(
                PlayerOption.track("v0", "1080p"),
                PlayerOption.track("v1", "720p"),
            ),
            chapters = emptyList(),
        )

        assertEquals(true, visibility.video)
    }
}
