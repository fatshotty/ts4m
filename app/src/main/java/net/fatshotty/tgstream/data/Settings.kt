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

    const val ENGINE_EXO = "exo"
    const val ENGINE_VLC = "vlc"

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

    fun isConfigured(ctx: Context): Boolean =
        getApiId(ctx) > 0 && getApiHash(ctx).isNotBlank()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
