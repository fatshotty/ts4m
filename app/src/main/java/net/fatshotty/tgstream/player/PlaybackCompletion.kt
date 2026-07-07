package net.fatshotty.tgstream.player

object PlaybackCompletion {
    private const val WATCHED_THRESHOLD_PERCENT = 90L

    fun shouldMarkWatched(positionMs: Long, durationMs: Long, completed: Boolean): Boolean {
        if (completed) return true
        if (positionMs <= 0 || durationMs <= 0) return false
        return positionMs * 100L >= durationMs * WATCHED_THRESHOLD_PERCENT
    }
}
