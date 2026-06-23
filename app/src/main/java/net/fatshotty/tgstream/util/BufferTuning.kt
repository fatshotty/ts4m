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

    /**
     * ExoPlayer target buffer in BYTES (Java heap — bounded to avoid OOM).
     * Pushed to the practical heap ceiling per tier (largeHeap=true in the
     * manifest). At 80 Mb/s (=10 MB/s) these give: LOW ~4.8s, MID ~12.8s,
     * HIGH ~25.6s, VHIGH ~38.4s of look-ahead.
     */
    fun exoTargetBufferBytes(ctx: Context): Int {
        val mb = when (ramTier(ctx)) {
            Tier.LOW -> 48    // Fire Stick 1st gen etc.
            Tier.MID -> 128
            Tier.HIGH -> 256
            Tier.VHIGH -> 384 // aggressive heap ceiling — needs largeHeap + ~512MB heap
        }
        return mb * 1024 * 1024
    }

    /**
     * libVLC network-caching in ms (also the startup latency). Native memory,
     * so we can afford a big buffer; the cost is latency before first frame.
     * At 80 Mb/s these cost: LOW ~38MB, MID ~75MB, HIGH ~125MB, VHIGH ~188MB
     * of native RAM.
     */
    fun libVlcCachingMs(ctx: Context): Int = when (ramTier(ctx)) {
        Tier.LOW -> 3000
        Tier.MID -> 6000
        Tier.HIGH -> 10000
        Tier.VHIGH -> 15000
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
