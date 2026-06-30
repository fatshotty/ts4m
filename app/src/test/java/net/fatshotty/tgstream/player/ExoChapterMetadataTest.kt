package net.fatshotty.tgstream.player

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class ExoChapterMetadataTest {
    @Test fun extractsSortedId3ChapterFramesWithTitles() {
        val metadata = Metadata(
            chapter("chapter-2", 20_000, "Second"),
            chapter("chapter-1", 5_000, "First"),
        )

        val chapters = ExoChapterMetadata.extract(metadata)

        assertEquals(
            listOf(
                ExoChapter("chapter-1", "First", 5_000),
                ExoChapter("chapter-2", "Second", 20_000),
            ),
            chapters,
        )
    }

    @Test fun ignoresChaptersWithoutValidStartTimes() {
        val metadata = Metadata(
            chapter("missing-start", -1, "Broken"),
            chapter("valid", 10_000, "Valid"),
        )

        val chapters = ExoChapterMetadata.extract(metadata)

        assertEquals(listOf(ExoChapter("valid", "Valid", 10_000)), chapters)
    }

    @Test fun mergeKeepsExistingChaptersAndReplacesDuplicateStartTimes() {
        val existing = listOf(
            ExoChapter("old-0", "Old intro", 0),
            ExoChapter("old-1", "Old scene", 15_000),
        )
        val incoming = listOf(
            ExoChapter("new-1", "New scene", 15_000),
            ExoChapter("new-2", "Ending", 45_000),
        )

        val merged = ExoChapterMetadata.merge(existing, incoming)

        assertEquals(
            listOf(
                ExoChapter("old-0", "Old intro", 0),
                ExoChapter("new-1", "New scene", 15_000),
                ExoChapter("new-2", "Ending", 45_000),
            ),
            merged,
        )
    }

    private fun chapter(id: String, startMs: Int, title: String): ChapterFrame {
        return ChapterFrame(
            id,
            startMs,
            -1,
            -1,
            -1,
            arrayOf<Id3Frame>(
                TextInformationFrame("TIT2", null, listOf(title)),
            ),
        )
    }
}
