package net.fatshotty.tgstream.player

import android.os.SystemClock
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * "Dynamic" buffer mode for ExoPlayer. The buffer target is normally fixed when the
 * LoadControl is built, but the interface-level lever [shouldContinueLoading] is
 * polled continuously by the player — so we ramp an effective BYTE cap by elapsed
 * playback time:
 *
 *   0-10s   -> LOW    (fast startup, little memory committed)
 *   10-20s  -> HIGH
 *   20s+    -> VHIGH  (full stability for high-bitrate, e.g. ~80 Mb/s, streams)
 *
 * Below the minimum playable buffer we always keep loading (anti-stall), regardless
 * of the current cap. Everything else delegates to a [DefaultLoadControl] sized at
 * the VHIGH ceiling, so allocator/back-buffer/start-playback behaviour is unchanged.
 *
 * Note: libVLC's network cache is fixed at media init and cannot be resized live, so
 * this dynamic ramp is ExoPlayer-only (libVLC uses a fixed tier — see [BufferTuning]).
 */
@UnstableApi
class DynamicLoadControl(
    private val lowBytes: Int,
    private val highBytes: Int,
    private val vhighBytes: Int,
) : LoadControl {

    private val inner: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, 1_000, 1_500)
        .setTargetBufferBytes(vhighBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    @Volatile private var startedAtMs = 0L

    /** Effective byte cap for the elapsed playback time. */
    fun currentCapBytes(): Int {
        val started = startedAtMs
        if (started == 0L) return lowBytes
        val elapsed = SystemClock.elapsedRealtime() - started
        return when {
            elapsed < RAMP_TO_HIGH_MS -> lowBytes
            elapsed < RAMP_TO_VHIGH_MS -> highBytes
            else -> vhighBytes
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (startedAtMs == 0L) startedAtMs = SystemClock.elapsedRealtime()
        // Always fill up to the minimum playable buffer to avoid stalls.
        if (parameters.bufferedDurationUs < MIN_BUFFER_MS * 1_000L) {
            return inner.shouldContinueLoading(parameters)
        }
        // Past the current ramp cap → stop loading (acts like target reached).
        if (inner.allocator.totalBytesAllocated >= currentCapBytes()) return false
        return inner.shouldContinueLoading(parameters)
    }

    // ---- everything else delegates to the inner DefaultLoadControl ----

    override fun getAllocator(): Allocator = inner.allocator

    override fun onPrepared(playerId: PlayerId) {
        startedAtMs = SystemClock.elapsedRealtime()
        inner.onPrepared(playerId)
    }

    override fun onStopped(playerId: PlayerId) {
        startedAtMs = 0L
        inner.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        startedAtMs = 0L
        inner.onReleased(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onTracksSelected(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = inner.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        inner.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        inner.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 300_000
        const val RAMP_TO_HIGH_MS = 10_000L
        const val RAMP_TO_VHIGH_MS = 20_000L
    }
}
