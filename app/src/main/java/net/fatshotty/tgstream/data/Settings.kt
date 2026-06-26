package net.fatshotty.tgstream.data

import android.content.Context
import tgengine.Tgengine

/**
 * Simple persistent settings. For this test we keep it plain (no encryption) to
 * avoid Keystore-related crashes on odd devices/boxes; the api credentials are
 * the user's own and never leave the device.
 *
 * API credentials: we only ever store/return what the user typed (empty if none).
 * The fallback to the app's built-in defaults happens inside the Go engine's
 * newEngine (the obfuscated defaults never cross JNI). Kotlin only needs to know
 * IF a built-in default exists, to decide whether to show the credentials form —
 * that single boolean is [Tgengine.hasDefaultCredentials].
 */
object Settings {
    private const val PREFS = "tgstream"
    private const val KEY_API_ID = "api_id"
    private const val KEY_API_HASH = "api_hash"
    private const val KEY_CONNS = "connections"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_ENGINE = "player_engine"
    private const val KEY_BUFFER = "buffer_mode"
    private const val KEY_PREBUFFER = "prebuffer_seconds"
    private const val KEY_SERVER_ENABLED = "server_enabled" // phone acts as LAN proxy
    private const val KEY_LAN_TOKEN = "lan_token"           // token for LAN clients
    private const val KEY_REMOTE_HOST = "remote_host"       // box: "ip:port" of phone
    private const val KEY_REMOTE_TOKEN = "remote_token"     // box: token for that phone

    // Startup/seek pre-buffer (ExoPlayer dynamic mode). 0 = AUTO (bitrate-scaled,
    // up to 5s); otherwise a fixed number of seconds to bank before playback. A
    // bigger value buys a longer smooth run on marginal devices at the cost of a
    // longer wait at start/after seek.
    const val PREBUFFER_AUTO = 0
    val PREBUFFER_CHOICES = listOf(PREBUFFER_AUTO, 2, 5, 10, 15, 20, 30)

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

    /** Pre-buffer seconds: 0 = AUTO (bitrate-scaled), else a fixed value. */
    fun getPreBufferSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_PREBUFFER, PREBUFFER_AUTO)
    fun setPreBufferSeconds(ctx: Context, s: Int) {
        prefs(ctx).edit().putInt(KEY_PREBUFFER, s).apply()
    }

    // --- LAN sharing: phone "server mode" ---

    /** Whether this device serves its proxy to other devices on the WiFi. */
    fun getServerEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SERVER_ENABLED, false)
    fun setServerEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SERVER_ENABLED, on).apply()
    }

    /** Stable per-install LAN token, generated on first use. */
    fun getLanToken(ctx: Context): String {
        val cur = prefs(ctx).getString(KEY_LAN_TOKEN, "") ?: ""
        if (cur.isNotBlank()) return cur
        val gen = (100000..999999).random().toString() // 6-digit, easy to type on a TV
        prefs(ctx).edit().putString(KEY_LAN_TOKEN, gen).apply()
        return gen
    }

    // --- LAN sharing: box "client mode" (points at a phone's proxy) ---

    /** "ip:port" of the phone serving the proxy, or blank to use the local engine. */
    fun getRemoteHost(ctx: Context): String = prefs(ctx).getString(KEY_REMOTE_HOST, "") ?: ""
    fun setRemoteHost(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_REMOTE_HOST, v.trim()).apply()
    }

    fun getRemoteToken(ctx: Context): String = prefs(ctx).getString(KEY_REMOTE_TOKEN, "") ?: ""
    fun setRemoteToken(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_REMOTE_TOKEN, v.trim()).apply()
    }

    /** True when this device should stream from a remote phone instead of its own engine. */
    fun isRemoteClient(ctx: Context): Boolean = getRemoteHost(ctx).isNotBlank()

    // Configured when the user entered their own credentials, OR the build baked
    // in a default (the engine will use it). runCatching guards the JNI call so a
    // missing/odd native lib degrades to "no default" instead of crashing.
    fun isConfigured(ctx: Context): Boolean =
        (getApiId(ctx) > 0 && getApiHash(ctx).isNotBlank()) || hasDefaultCredentials()

    private fun hasDefaultCredentials(): Boolean =
        runCatching { Tgengine.hasDefaultCredentials() }.getOrDefault(false)

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
