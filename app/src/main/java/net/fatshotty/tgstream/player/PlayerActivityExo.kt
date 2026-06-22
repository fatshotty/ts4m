package net.fatshotty.tgstream.player

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fatshotty.tgstream.emby.EmbyReporter
import net.fatshotty.tgstream.tg.Tg
import net.fatshotty.tgstream.util.AppLog
import net.fatshotty.tgstream.util.BufferTuning
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ExoPlayer/Media3 player engine (default). Great HDR/Dolby Vision support via the
 * platform decoder. Streams from the localhost Go proxy and reports to Emby. Uses
 * Media3's built-in controller (D-pad friendly); nerd-stats overlay via D-pad UP.
 * For containers ExoPlayer can't handle (e.g. large OpenDML AVI) the user can
 * switch to the libVLC engine ([PlayerActivity]) in Settings.
 *
 * Extras are shared with [PlayerActivity] (its companion).
 */
@UnstableApi
class PlayerActivityExo : AppCompatActivity() {

    private companion object {
        const val TAG = "TgStream"
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var reporter: EmbyReporter? = null
    private var startMs: Long = 0
    private var startReported = false

    // stats
    private var statsView: TextView? = null
    private var statsVisible = false
    private var title: String = ""
    private var conns: Int = 1
    private val bytesTransferred = AtomicLong(0)
    @Volatile private var totalBytes: Long = -1
    private var lastBytes = 0L
    private var lastTickNs = 0L
    private var emaBps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val channel = intent.getLongExtra(PlayerActivity.EXTRA_CHANNEL, 0)
        val messages = intent.getStringExtra(PlayerActivity.EXTRA_MESSAGES) ?: ""
        conns = intent.getIntExtra(PlayerActivity.EXTRA_CONNS, 3)
        startMs = intent.getLongExtra(PlayerActivity.EXTRA_START_MS, 0)
        title = intent.getStringExtra(PlayerActivity.EXTRA_TITLE) ?: ""

        if (channel == 0L || messages.isBlank() || Tg.proxyPort <= 0) {
            finish()
            return
        }

        if (intent.getStringExtra(PlayerActivity.EXTRA_REPORT_KIND) == "emby") {
            reporter = EmbyReporter(
                serverBase = intent.getStringExtra(PlayerActivity.EXTRA_SERVER) ?: "",
                token = intent.getStringExtra(PlayerActivity.EXTRA_TOKEN) ?: "",
                itemId = intent.getStringExtra(PlayerActivity.EXTRA_ITEM_ID) ?: "",
                playSessionId = intent.getStringExtra(PlayerActivity.EXTRA_PLAY_SESSION) ?: "",
                deviceId = intent.getStringExtra(PlayerActivity.EXTRA_DEVICE_ID) ?: "",
                mediaSourceId = intent.getStringExtra(PlayerActivity.EXTRA_MEDIA_SOURCE) ?: "",
            )
        }

        val root = FrameLayout(this)
        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            useController = true
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }
        playerView = view
        root.addView(view)

        statsView = TextView(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; setMargins(32, 32, 32, 32) }
        }
        root.addView(statsView)

        val infoBtn = Button(this).apply {
            text = "ⓘ"
            isFocusable = true
            alpha = 0.6f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, 24, 24, 0) }
            setOnClickListener { toggleStats() }
        }
        root.addView(infoBtn)
        setContentView(root)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(s: DataSource, d: DataSpec, n: Boolean) {}
                override fun onTransferStart(s: DataSource, d: DataSpec, n: Boolean) {}
                override fun onBytesTransferred(s: DataSource, d: DataSpec, n: Boolean, bytes: Int) {
                    if (n) bytesTransferred.addAndGet(bytes.toLong())
                }
                override fun onTransferEnd(s: DataSource, d: DataSpec, n: Boolean) {}
            })

        // Auto-sized buffer: up to 120s ahead, capped by a device-RAM-aware byte
        // budget (Java-heap bound). Resume after seek with little buffered.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 1_000, 1_500)
            .setTargetBufferBytes(BufferTuning.exoTargetBufferBytes(this))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Snap to nearest keyframe — far less to download/decode after a seek.
        exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player = exo
        view.player = exo

        val url = Tg.streamUrl(channel, messages, conns)
        AppLog.i(TAG, "stream url: $url (engine=exo, ready=${Tg.isReady})")
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = true
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)

        exo.addListener(object : Player.Listener {
            // Player.Listener callbacks run on the application thread, so reading
            // exo.currentPosition here is correct; we capture it and pass the
            // value to the IO reporter (never read the player off-thread).
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> state.toString()
                }
                AppLog.i(TAG, "playback state: $name")
                if (state == Player.STATE_READY && !startReported) {
                    startReported = true
                    reportStart(exo.currentPosition)
                }
                if (state == Player.STATE_ENDED) reportStopped(exo.currentPosition)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                reportProgress(exo.currentPosition, !isPlaying)
            }
            override fun onPositionDiscontinuity(
                oldP: Player.PositionInfo, newP: Player.PositionInfo, reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK)
                    reportProgress(newP.positionMs, !exo.isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val detail = when (cause) {
                    is HttpDataSource.InvalidResponseCodeException ->
                        "HTTP ${cause.responseCode} dal proxy: ${String(cause.responseBody).take(200)}"
                    is HttpDataSource.HttpDataSourceException ->
                        "proxy I/O: ${cause.javaClass.simpleName}: ${cause.message}"
                    else -> "${cause?.javaClass?.simpleName ?: error.javaClass.simpleName}: ${cause?.message ?: error.message}"
                }
                AppLog.e(TAG, "player error [${error.errorCodeName}] $detail", error)
                runCatching {
                    android.widget.Toast.makeText(
                        this@PlayerActivityExo, "Errore player: $detail", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        })

        fetchTotalSize(url)
        startProgressLoop()
        startStatsLoop()
    }

    private fun toggleStats() {
        statsVisible = !statsVisible
        statsView?.visibility = if (statsVisible) View.VISIBLE else View.GONE
    }

    // D-pad UP/DOWN toggles the nerd panel — universal on every remote, no INFO
    // key needed. Intercepted before the PlayerView controller sees it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            toggleStats()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_PROG_YELLOW
        ) {
            toggleStats()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fetchTotalSize(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    val len = r.header("Content-Length")?.toLongOrNull()
                    val cr = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    totalBytes = cr ?: len ?: -1
                }
            }
        }
    }

    private fun startStatsLoop() {
        lastTickNs = System.nanoTime()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateStats()
                    delay(1000)
                }
            }
        }
    }

    private fun updateStats() {
        val p = player ?: return
        val now = System.nanoTime()
        val cur = bytesTransferred.get()
        val dtSec = ((now - lastTickNs).coerceAtLeast(1)) / 1_000_000_000.0
        val liveBps = (cur - lastBytes) * 8.0 / dtSec
        emaBps = if (emaBps == 0.0) liveBps else emaBps * 0.7 + liveBps * 0.3
        lastBytes = cur
        lastTickNs = now

        if (!statsVisible) return

        val fmt: Format? = p.videoFormat
        val w = fmt?.width ?: p.videoSize.width
        val h = fmt?.height ?: p.videoSize.height
        val declaredBitrate = fmt?.bitrate ?: Format.NO_VALUE
        val fromExo = declaredBitrate > 0
        val videoBitrate = when {
            fromExo -> declaredBitrate.toLong()
            totalBytes > 0 && p.duration > 0 -> totalBytes * 8 * 1000 / p.duration
            else -> -1
        }
        val bufPct = p.bufferedPercentage
        val bufAheadSec = p.totalBufferedDuration / 1000.0

        val sb = StringBuilder()
        sb.append("Motore: ExoPlayer\n")
        sb.append("Nome: ").append(if (title.isBlank()) "—" else title).append('\n')
        sb.append("Size: ").append(humanSize(totalBytes)).append('\n')
        sb.append("Risoluzione: ").append(if (w > 0 && h > 0) "${w}x${h}" else "—").append('\n')
        sb.append("Bitrate video: ").append(humanRate(videoBitrate.toDouble()))
            .append(if (fromExo) " (decoder)" else " (media)").append('\n')
        sb.append("Banda live: ").append(humanRate(liveBps)).append('\n')
        sb.append("Banda media: ").append(humanRate(emaBps)).append('\n')
        sb.append("Buffer: ").append(bufPct).append("%  ").append(String.format("%.1fs", bufAheadSec)).append('\n')
        sb.append("Connessioni parallele: ").append(conns)
        statsView?.text = sb
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) return String.format("%.2f GB", gb)
        return String.format("%.1f MB", bytes / 1_048_576.0)
    }

    private fun humanRate(bitsPerSec: Double): String {
        if (bitsPerSec <= 0) return "—"
        val mbps = bitsPerSec / 1_000_000.0
        if (mbps >= 1.0) return String.format("%.2f Mbps", mbps)
        return String.format("%.0f kbps", bitsPerSec / 1000.0)
    }

    private fun startProgressLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val p = player
                    if (p != null && p.isPlaying) reportProgress(p.currentPosition, false)
                    delay(10_000)
                }
            }
        }
    }

    // Position is captured by the caller (application thread) and passed as a
    // primitive — never read inside the IO coroutine.
    private fun reportStart(posMs: Long) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.start(posMs) } }
    }

    private fun reportProgress(posMs: Long, paused: Boolean) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.progress(posMs, paused) } }
    }

    private fun reportStopped(posMs: Long) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.stopped(posMs) } }
    }

    override fun onStop() {
        super.onStop()
        val pos = player?.currentPosition ?: 0
        val r = reporter
        if (r != null) Thread { runCatching { r.stopped(pos) } }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerView?.player = null
        player?.release()
        player = null
    }
}
