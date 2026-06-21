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
        if (!Settings.isConfigured(this)) {
            Toast.makeText(this, "Configura prima Telegram", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        Tg.start(this)

        lifecycleScope.launch {
            try {
                val provider = ProviderRegistry.candidates(uri).firstOrNull()
                    ?: ProviderRegistry.single
                    ?: throw IllegalStateException("Nessun provider configurato")

                val result: ResolveResult = withContext(Dispatchers.IO) { provider.resolve(uri) }

                if (!Tg.awaitReady()) {
                    fail("Telegram non pronto/loggato")
                    return@launch
                }

                launchPlayer(result)
                finish()
            } catch (t: Throwable) {
                AppLog.w(TAG, "resolve failed", t)
                fail(t.message ?: "Errore di risoluzione")
            }
        }
    }

    private fun launchPlayer(r: ResolveResult) {
        val conns = Settings.getConnections(this)
        val i = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, r.stream.channelId)
            putExtra(PlayerActivity.EXTRA_MESSAGES, r.stream.messagesDash)
            putExtra(PlayerActivity.EXTRA_CONNS, conns)
            putExtra(PlayerActivity.EXTRA_TITLE, r.stream.title)
            putExtra(PlayerActivity.EXTRA_START_MS, r.stream.startPositionMs)
            putExtra(PlayerActivity.EXTRA_DURATION_MS, r.stream.durationMs)
            putExtra(PlayerActivity.EXTRA_REPORT_KIND, r.reporting.kind)
            putExtra(PlayerActivity.EXTRA_SERVER, r.reporting.serverBase)
            putExtra(PlayerActivity.EXTRA_ITEM_ID, r.reporting.itemId)
            putExtra(PlayerActivity.EXTRA_TOKEN, r.reporting.token)
            putExtra(PlayerActivity.EXTRA_PLAY_SESSION, r.reporting.playSessionId)
            putExtra(PlayerActivity.EXTRA_DEVICE_ID, r.reporting.deviceId)
            putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE, r.reporting.mediaSourceId)
        }
        startActivity(i)
    }

    private fun fail(msg: String) {
        AppLog.w(TAG, "fail: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG = "TgStream"
    }
}
