package org.tgstream.app.player.controls

enum class PlayerOptionKind {
    AUDIO,
    SUBTITLE,
    VIDEO,
    CHAPTER,
}

data class PlayerOption(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val selected: Boolean = false,
    val enabled: Boolean = true,
) {
    companion object {
        fun track(
            id: String,
            title: String,
            language: String = "",
            details: String = "",
            selected: Boolean = false,
            enabled: Boolean = true,
        ): PlayerOption {
            val fallbackTitle = title.ifBlank { language.ifBlank { "Track" } }
            val subtitle = listOf(language, details)
                .filter { it.isNotBlank() && it != fallbackTitle }
                .joinToString(" - ")
            return PlayerOption(
                id = id,
                title = fallbackTitle,
                subtitle = subtitle,
                selected = selected,
                enabled = enabled,
            )
        }

        fun chapter(
            id: String,
            index: Int,
            name: String,
            timeOffsetMs: Long,
            selected: Boolean,
        ): PlayerOption {
            return PlayerOption(
                id = id,
                title = name.ifBlank { "Chapter ${index + 1}" },
                subtitle = formatTime(timeOffsetMs),
                selected = selected,
            )
        }

        fun off(id: String = "off", selected: Boolean): PlayerOption =
            PlayerOption(id = id, title = "Off", selected = selected)
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
