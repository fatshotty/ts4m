package org.tgstream.app.player.controls

data class ControlVisibility(
    val audio: Boolean,
    val subtitles: Boolean,
    val video: Boolean,
    val chapters: Boolean,
    val stats: Boolean = true,
) {
    companion object {
        fun fromOptions(
            audio: List<PlayerOption>,
            subtitles: List<PlayerOption>,
            video: List<PlayerOption>,
            chapters: List<PlayerOption>,
        ): ControlVisibility {
            return ControlVisibility(
                audio = audio.any { it.enabled },
                subtitles = subtitles.any { it.enabled },
                video = video.count { it.enabled } > 1,
                chapters = chapters.any { it.enabled },
            )
        }
    }
}
