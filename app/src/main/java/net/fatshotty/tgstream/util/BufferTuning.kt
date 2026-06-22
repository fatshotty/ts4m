package net.fatshotty.tgstream.util

import android.app.ActivityManager
import android.content.Context

/**
 * Auto-sizes the playback buffer by device RAM. The effective buffer adapts to
 * the video automatically: for a given byte cap, seconds buffered = cap / bitrate
 * (a light file buffers many seconds, a heavy 4K file fewer). ExoPlayer's buffer
 * lives on the Java heap so it must stay bounded (hence the byte cap); libVLC's
 * is native memory so we size it by time (= startup latency, kept modest).
 */
object BufferTuning {

    private enum class Tier { LOW, MID, HIGH, VHIGH }

    /** ExoPlayer target buffer in BYTES (Java heap — bounded to avoid OOM). */
    fun exoTargetBufferBytes(ctx: Context): Int {
        val mb = when (ramTier(ctx)) {
            Tier.LOW -> 32    // Fire Stick 1st gen etc.
            Tier.MID -> 96
            Tier.HIGH -> 192
            Tier.VHIGH -> 256 // practical ceiling for the Java heap, even w/ largeHeap
        }
        return mb * 1024 * 1024
    }

    /** libVLC network-caching in ms (also the startup latency — keep modest). */
    fun libVlcCachingMs(ctx: Context): Int = when (ramTier(ctx)) {
        Tier.LOW -> 1500
        else -> 3000
    }

    private fun ramTier(ctx: Context): Tier {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return Tier.LOW
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        return when {
            gb < 1.5 -> Tier.LOW
            gb < 3 -> Tier.MID
            gb < 6 -> Tier.HIGH
            else -> Tier.VHIGH
        }
    }
}
