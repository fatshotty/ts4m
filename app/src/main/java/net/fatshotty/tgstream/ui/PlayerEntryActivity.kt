package net.fatshotty.tgstream.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fatshotty.tgstream.data.PlaybackHistoryItem
import net.fatshotty.tgstream.data.PlaybackHistoryStore
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.player.PlayerActivity
import net.fatshotty.tgstream.player.PlayerActivityExo
import net.fatshotty.tgstream.provider.ProviderRegistry
import net.fatshotty.tgstream.provider.ResolveResult
import net.fatshotty.tgstream.tg.Tg
import net.fatshotty.tgstream.util.AppLog

/**
 * Invisible entry point invoked by other apps (Emby) as an external player.
 * Resolves the provider, waits for the engine, then launches the real player.
 * Must never crash — failures end with a toast and finish().
 */
class PlayerEntryActivity : AppCompatActivity() {
    private var playerLaunched = false

    private val playerResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        playerLaunched = false
        setResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerLaunched = savedInstanceState?.getBoolean(STATE_PLAYER_LAUNCHED) == true
        if (playerLaunched) {
            AppLog.i(TAG, "Player already launched; waiting for result")
            return
        }

        // Fresh log per playback so the screen shows only this session.
        AppLog.clear()
        val uri = intent?.data
        AppLog.i(TAG, "Launch URI: ${uri?.let { AppLog.redactUrl(it.toString()) }}")
        if (uri == null) {
            fail("No URL received")
            return
        }
        Settings.setLastUrl(this, uri.toString()) // for the in-app speed test
        if (!Settings.isConfigured(this)) {
            Toast.makeText(this, "Configure Telegram first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        Tg.start(this)

        lifecycleScope.launch {
            try {
                val provider = ProviderRegistry.candidates(uri).firstOrNull()
                    ?: throw IllegalStateException("Unsupported URL")
                val source = intent.getStringExtra(EXTRA_HISTORY_SOURCE)
                    ?.takeIf { it.isNotBlank() }
                    ?: provider.id

                val result: ResolveResult = withContext(Dispatchers.IO) { provider.resolve(uri) }

                if (!Tg.awaitReady()) {
                    fail("Telegram is not ready/logged in")
                    return@launch
                }

                launchPlayer(result, source)
                // Do NOT finish here: stay alive invisibly behind the player so we
                // can relay its result intent (MX-style position/duration) back to the
                // external launcher (Stremio/Nuvio) through playerResultLauncher.
            } catch (t: Throwable) {
                AppLog.w(TAG, "resolve failed", t)
                fail(t.message ?: "Unable to resolve URL")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PLAYER_LAUNCHED, playerLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun launchPlayer(r: ResolveResult, source: String) {
        val conns = Settings.getConnections(this)
        val streamUrl = Tg.streamUrl(r.stream.channelId, r.stream.messagesDash, conns)
        AppLog.i(TAG, "stream url: ${AppLog.redactUrl(streamUrl)}")
        // Resume position: source providers own their launch semantics. For
        // direct/no-reporting launches, an MX-style inbound `position` extra can
        // still resume external-player flows such as Stremio/Nuvio.
        val inboundMs = inboundResumeMs()
        val startMs = StartPositionResolver.choose(r.reporting.kind, inboundMs, r.stream.startPositionMs)
        AppLog.i(TAG, "start position: ${startMs}ms (inbound=$inboundMs, provider=${r.stream.startPositionMs})")
        PlaybackHistoryStore.record(
            this,
            PlaybackHistoryItem(
                channelId = r.stream.channelId,
                messagesDash = r.stream.messagesDash,
                title = r.stream.title,
                source = source,
                lastPlayedAtMs = System.currentTimeMillis(),
                startPositionMs = startMs,
            ),
        )
        val playerClass = if (Settings.getPlayerEngine(this) == Settings.ENGINE_VLC)
            PlayerActivity::class.java else PlayerActivityExo::class.java
        val i = Intent(this, playerClass).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, r.stream.channelId)
            putExtra(PlayerActivity.EXTRA_MESSAGES, r.stream.messagesDash)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CONNS, conns)
            putExtra(PlayerActivity.EXTRA_TITLE, r.stream.title)
            putExtra(PlayerActivity.EXTRA_START_MS, startMs)
            putExtra(PlayerActivity.EXTRA_DURATION_MS, r.stream.durationMs)
            putExtra(PlayerActivity.EXTRA_REPORT_KIND, r.reporting.kind)
            putExtra(PlayerActivity.EXTRA_SERVER, r.reporting.serverBase)
            putExtra(PlayerActivity.EXTRA_ITEM_ID, r.reporting.itemId)
            putExtra(PlayerActivity.EXTRA_TOKEN, r.reporting.token)
            putExtra(PlayerActivity.EXTRA_PLAY_SESSION, r.reporting.playSessionId)
            putExtra(PlayerActivity.EXTRA_DEVICE_ID, r.reporting.deviceId)
            putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE, r.reporting.mediaSourceId)
        }
        // Launch for result so the player's MX-style result intent
        // (position/duration/end_by) can be relayed back to whoever launched us.
        playerLaunched = true
        playerResultLauncher.launch(i)
    }

    /**
     * Inbound resume position (ms) from the launching intent's MX-style `position`
     * extra, or -1 if absent. Launchers send it as int (MX) but tolerate long.
     * NOTE: Stremio sends it on the external-player (url) path, not on an opaque
     * `externalUris` deep link; there resume must come from our own `pos=` param
     * or, in future, the Stremio-as-provider lookup.
     */
    private fun inboundResumeMs(): Long {
        val i = intent ?: return -1
        val asInt = i.getIntExtra(PlayerActivity.MX_POSITION, -1)
        if (asInt >= 0) return asInt.toLong()
        return i.getLongExtra(PlayerActivity.MX_POSITION, -1L)
    }

    private fun fail(msg: String) {
        AppLog.w(TAG, "fail: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_HISTORY_SOURCE = "history_source"
        private const val TAG = "TgStream"
        private const val STATE_PLAYER_LAUNCHED = "player_launched"
    }
}
