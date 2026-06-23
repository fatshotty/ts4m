package net.fatshotty.tgstream.data

import android.content.Context

/**
 * Simple persistent settings. For this test we keep it plain (no encryption) to
 * avoid Keystore-related crashes on odd devices/boxes; the api credentials are
 * the user's own and never leave the device.
 */
object Settings {
    private const val PREFS = "tgstream"
    private const val KEY_API_ID = "api_id"
    private const val KEY_API_HASH = "api_hash"
    private const val KEY_CONNS = "connections"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_ENGINE = "player_engine"
    private const val KEY_BUFFER = "buffer_mode"
    private const val KEY_SW_DECODE = "force_sw_decode"

    const val ENGINE_EXO = "exo"
    const val ENGINE_VLC = "vlc"

    // Buffer size tiers + "dynamic" (ramps up during playback on ExoPlayer).
    const val BUFFER_LOW = "low"
    const val BUFFER_MID = "mid"
    const val BUFFER_HIGH = "high"
    const val BUFFER_VHIGH = "vhigh"
    const val BUFFER_DYNAMIC = "dynamic"
    val BUFFER_MODES = listOf(BUFFER_LOW, BUFFER_MID, BUFFER_HIGH, BUFFER_VHIGH, BUFFER_DYNAMIC)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiId(ctx: Context): Int = prefs(ctx).getInt(KEY_API_ID, 0)
    fun getApiHash(ctx: Context): String = prefs(ctx).getString(KEY_API_HASH, "") ?: ""
    fun getConnections(ctx: Context): Int = prefs(ctx).getInt(KEY_CONNS, 3)

    fun setApiCredentials(ctx: Context, apiId: Int, apiHash: String) {
        prefs(ctx).edit()
            .putInt(KEY_API_ID, apiId)
            .putString(KEY_API_HASH, apiHash.trim())
            .apply()
    }

    fun setConnections(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_CONNS, n.coerceIn(1, 16)).apply()
    }

    fun getLastUrl(ctx: Context): String = prefs(ctx).getString(KEY_LAST_URL, "") ?: ""
    fun setLastUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_LAST_URL, url).apply()
    }

    /** Playback engine: ENGINE_EXO (default) or ENGINE_VLC. */
    fun getPlayerEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_EXO) ?: ENGINE_EXO
    fun setPlayerEngine(ctx: Context, engine: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine).apply()
    }

    /**
     * Buffer mode: one of BUFFER_* (default BUFFER_HIGH). Applies to both engines.
     * HIGH matches v0.1.20's effective behaviour on typical TV boxes (256 MB on
     * ExoPlayer, 10s libVLC cache) — noticeably smoother than MID on high-bitrate
     * streams.
     */
    fun getBufferMode(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_BUFFER, BUFFER_HIGH) ?: BUFFER_HIGH
        return if (v in BUFFER_MODES) v else BUFFER_HIGH
    }
    fun setBufferMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_BUFFER, mode).apply()
    }

    /**
     * Force software video decoding on libVLC (default false = hardware). Useful to
     * test Dolby Vision / HDR 10-bit content, where the HW decoder can produce a
     * green screen; SW decode renders correct colours but is much heavier on CPU.
     * No effect on ExoPlayer (always uses the platform decoder).
     */
    fun getForceSwDecode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SW_DECODE, false)
    fun setForceSwDecode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SW_DECODE, on).apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        getApiId(ctx) > 0 && getApiHash(ctx).isNotBlank()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
