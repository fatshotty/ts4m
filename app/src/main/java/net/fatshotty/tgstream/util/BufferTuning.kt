package net.fatshotty.tgstream.util

import android.app.ActivityManager
import android.content.Context
import net.fatshotty.tgstream.data.Settings

/**
 * Playback buffer sizing. The user picks a tier in Settings (LOW/MID/HIGH/VHIGH)
 * or "dynamic" (ramps up during playback — ExoPlayer only, see [PlayerActivityExo]
 * / DynamicLoadControl). For a given byte/time budget the effective seconds buffered
 * = budget / bitrate (a light file buffers many seconds, a heavy 80 Mb/s file fewer).
 *
 * ExoPlayer's buffer lives on the Java heap so it is capped in BYTES (bounded to
 * avoid OOM even with largeHeap); libVLC's is native memory so it is sized by TIME
 * (= startup latency).
 */
object BufferTuning {

    enum class Tier { LOW, MID, HIGH, VHIGH }

    /** ExoPlayer target buffer in BYTES for a tier (Java heap — bounded). */
    fun exoBytes(tier: Tier): Int {
        val mb = when (tier) {
            Tier.LOW -> 48     // Fire Stick 1st gen etc.
            Tier.MID -> 128
            Tier.HIGH -> 256
            Tier.VHIGH -> 384  // aggressive heap ceiling — needs largeHeap + ~512MB heap
        }
        return mb * 1024 * 1024
    }

    /**
     * ExoPlayer allocates its progressive media buffer on the Java heap. Keep a
     * large reserve for app/runtime objects and subtitle samples, especially on
     * 32-bit TV boxes where largeHeap is commonly around 384 MB.
     */
    fun exoBytesForHeap(tier: Tier, maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
        val requested = exoBytes(tier)
        val safeCap = (maxHeapBytes * 35 / 100).toInt()
            .coerceAtLeast(exoBytes(Tier.LOW))
        return requested.coerceAtMost(safeCap)
    }

    /**
     * libVLC network-caching in ms for a tier (native memory; also startup latency
     * and post-seek refill wait — libVLC resumes only once the buffer hits 100% of
     * this). These are the v0.1.20 values: a larger cache gives noticeably smoother
     * playback on high-bitrate streams (the trade-off is a slower seek refill).
     */
    fun vlcCachingMs(tier: Tier): Int = when (tier) {
        Tier.LOW -> 3000
        Tier.MID -> 6000
        Tier.HIGH -> 10000
        Tier.VHIGH -> 15000
    }

    /**
     * Resolves the user-selected buffer mode to a concrete tier. "dynamic" has no
     * single tier: ExoPlayer ramps it live, while libVLC (cache fixed at init,
     * cannot resize at runtime) falls back to [dynamicFallbackTier].
     */
    fun tierForMode(mode: String): Tier = when (mode) {
        Settings.BUFFER_LOW -> Tier.LOW
        Settings.BUFFER_HIGH -> Tier.HIGH
        Settings.BUFFER_VHIGH -> Tier.VHIGH
        Settings.BUFFER_DYNAMIC -> dynamicFallbackTier
        else -> Tier.MID
    }

    /** What "dynamic" means on engines that can't ramp the buffer live (libVLC). */
    val dynamicFallbackTier = Tier.MID

    /** Auto-detect a tier from device RAM (kept for reference/fallback). */
    fun autoTier(ctx: Context): Tier {
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
