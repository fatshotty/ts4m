package net.fatshotty.tgstream.player

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame

data class ExoChapter(
    val id: String,
    val title: String,
    val startMs: Long,
)

object ExoChapterMetadata {
    fun extract(metadata: Metadata): List<ExoChapter> {
        return (0 until metadata.length())
            .mapNotNull { index -> metadata.get(index).toChapter() }
            .sortedBy { it.startMs }
    }

    fun merge(existing: List<ExoChapter>, incoming: List<ExoChapter>): List<ExoChapter> {
        if (incoming.isEmpty()) return existing
        val byStartMs = LinkedHashMap<Long, ExoChapter>()
        existing.forEach { chapter -> byStartMs[chapter.startMs] = chapter }
        incoming.forEach { chapter -> byStartMs[chapter.startMs] = chapter }
        return byStartMs.values.sortedBy { it.startMs }
    }

    private fun Metadata.Entry.toChapter(): ExoChapter? {
        val frame = this as? ChapterFrame ?: return null
        if (frame.startTimeMs < 0) return null
        return ExoChapter(
            id = frame.chapterId,
            title = frame.title(),
            startMs = frame.startTimeMs.toLong(),
        )
    }

    private fun ChapterFrame.title(): String {
        return (0 until getSubFrameCount())
            .map { getSubFrame(it) }
            .filterIsInstance<TextInformationFrame>()
            .firstNotNullOfOrNull { frame ->
                if (frame.id == "TIT2") {
                    frame.values.firstOrNull { it.isNotBlank() }
                } else {
                    null
                }
            }
            .orEmpty()
    }
}
