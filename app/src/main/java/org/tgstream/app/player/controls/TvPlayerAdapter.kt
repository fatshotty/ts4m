package org.tgstream.app.player.controls

interface TvPlayerAdapter {
    fun isPlaying(): Boolean
    fun play()
    fun pause()

    fun durationMs(): Long
    fun positionMs(): Long
    fun seekTo(positionMs: Long)

    fun audioOptions(): List<PlayerOption>
    fun subtitleOptions(): List<PlayerOption>
    fun videoOptions(): List<PlayerOption>
    fun chapterOptions(): List<PlayerOption>
    fun selectOption(kind: PlayerOptionKind, id: String)

    fun isStatsVisible(): Boolean
    fun setStatsVisible(visible: Boolean)
}
