package net.fatshotty.tgstream.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fresh log per playback so the screen shows only this session.
        AppLog.clear()
        val uri = intent?.data
        AppLog.i(TAG, "Launch URI: $uri")
        if (uri == null) {
            fail("Nessun URL ricevuto")
            return
        }
        Settings.setLastUrl(this, uri.toString()) // for the in-app speed test
        val remote = Settings.isRemoteClient(this)
        // Client mode streams from a paired phone's proxy → no local engine/login.
        if (!remote && !Settings.isConfigured(this)) {
            Toast.makeText(this, "Configura prima Telegram", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (!remote) Tg.start(this)

        lifecycleScope.launch {
            try {
                val provider = ProviderRegistry.candidates(uri).firstOrNull()
                    ?: ProviderRegistry.single
                    ?: throw IllegalStateException("Nessun provider configurato")

                val result: ResolveResult = withContext(Dispatchers.IO) { provider.resolve(uri) }

                if (!remote && !Tg.awaitReady()) {
                    fail("Telegram non pronto/loggato")
                    return@launch
                }

                launchPlayer(result)
                // Do NOT finish here: stay alive (invisibly) behind the player so we
                // can relay its result intent (MX-style position/duration) back to the
                // external launcher (Stremio/Nuvio) in onActivityResult.
            } catch (t: Throwable) {
                AppLog.w(TAG, "resolve failed", t)
                fail(t.message ?: "Errore di risoluzione")
            }
        }
    }

    private fun launchPlayer(r: ResolveResult) {
        val conns = Settings.getConnections(this)
        val streamUrl = if (Settings.isRemoteClient(this)) {
            Tg.remoteStreamUrl(
                Settings.getRemoteHost(this), Settings.getRemoteToken(this),
                r.stream.channelId, r.stream.messagesDash, conns,
            )
        } else {
            Tg.streamUrl(r.stream.channelId, r.stream.messagesDash, conns)
        }
        AppLog.i(TAG, "stream url: $streamUrl (remote=${Settings.isRemoteClient(this)})")
        // Resume position: an MX-style inbound `position` extra (ms) from the
        // launcher (Stremio/Nuvio external-player path) wins; otherwise the value
        // the provider resolved (e.g. Emby's saved position).
        val startMs = inboundResumeMs().takeIf { it >= 0 } ?: r.stream.startPositionMs
        AppLog.i(TAG, "start position: ${startMs}ms (inbound=${inboundResumeMs()}, provider=${r.stream.startPositionMs})")
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
        // Launch for result so the player's MX-style result intent (position/
        // duration/end_by) can be relayed back to whoever launched us.
        startActivityForResult(i, REQ_PLAYER)
    }

    /**
     * Inbound resume position (ms) from the launching intent's MX-style `position`
     * extra, or -1 if absent. Launchers send it as int (MX) but tolerate long.
     * NOTE: Stremio sends it on the external-player (url) path, not on an opaque
     * `externalUris` deep link — there resume must come from our own `pos=` param
     * or, in future, the Stremio-as-provider lookup.
     */
    private fun inboundResumeMs(): Long {
        val i = intent ?: return -1
        val asInt = i.getIntExtra(PlayerActivity.MX_POSITION, -1)
        if (asInt >= 0) return asInt.toLong()
        return i.getLongExtra(PlayerActivity.MX_POSITION, -1L)
    }

    // Relay the player's result verbatim to the external launcher, then finish.
    @Deprecated("startActivityForResult relay; simplest transparent forwarding")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PLAYER) {
            setResult(resultCode, data)
            finish()
        }
    }

    private fun fail(msg: String) {
        AppLog.w(TAG, "fail: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG = "TgStream"
        const val REQ_PLAYER = 1001
    }
}
