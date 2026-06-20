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
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.datasource.HttpDataSource
import net.fatshotty.tgstream.emby.EmbyReporter
import net.fatshotty.tgstream.tg.Tg
import net.fatshotty.tgstream.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Internal player. Streams from the localhost Go proxy and reports progress to
 * the source app (Emby). Works with touch and with a TV D-pad remote; the
 * default Media3 controller exposes a scrubbable seek bar plus rewind/forward.
 *
 * A "nerd stats" overlay (toggle: MENU/INFO key or the ⓘ button) shows file
 * name/size, resolution, live bitrate, live download bandwidth and the number
 * of parallel connections.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TgStream"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_MESSAGES = "messages"
        const val EXTRA_CONNS = "conns"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_REPORT_KIND = "report_kind"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PLAY_SESSION = "play_session"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_MEDIA_SOURCE = "media_source"
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

        val channel = intent.getLongExtra(EXTRA_CHANNEL, 0)
        val messages = intent.getStringExtra(EXTRA_MESSAGES) ?: ""
        conns = intent.getIntExtra(EXTRA_CONNS, 3)
        startMs = intent.getLongExtra(EXTRA_START_MS, 0)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        if (channel == 0L || messages.isBlank() || Tg.proxyPort <= 0) {
            finish()
            return
        }

        if (intent.getStringExtra(EXTRA_REPORT_KIND) == "emby") {
            reporter = EmbyReporter(
                serverBase = intent.getStringExtra(EXTRA_SERVER) ?: "",
                token = intent.getStringExtra(EXTRA_TOKEN) ?: "",
                itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: "",
                playSessionId = intent.getStringExtra(EXTRA_PLAY_SESSION) ?: "",
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "",
                mediaSourceId = intent.getStringExtra(EXTRA_MEDIA_SOURCE) ?: "",
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

        // Resume playback after a seek with little buffered (default is 5s),
        // so seeks feel near-instant instead of waiting on a big buffer fill.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Snap to the nearest keyframe instead of decoding from the previous
        // keyframe to the exact frame — far less to download/decode after a seek,
        // so playback resumes much faster (standard streaming-player behavior).
        exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player = exo
        view.player = exo

        val url = Tg.streamUrl(channel, messages, conns)
        AppLog.i(TAG, "stream url: $url (engineReady=${Tg.isReady})")
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = true
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)

        exo.addListener(object : Player.Listener {
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
                    report { it.start(exo.currentPosition) }
                }
                if (state == Player.STATE_ENDED) report { it.stopped(exo.currentPosition) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                report { it.progress(exo.currentPosition, !isPlaying) }
            }
            override fun onPositionDiscontinuity(
                oldP: Player.PositionInfo, newP: Player.PositionInfo, reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK)
                    report { it.progress(newP.positionMs, !exo.isPlaying) }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val detail = when (cause) {
                    is HttpDataSource.InvalidResponseCodeException ->
                        "HTTP ${cause.responseCode} dal proxy: ${
                            String(cause.responseBody).take(200)
                        }"
                    is HttpDataSource.HttpDataSourceException ->
                        "proxy I/O: ${cause.javaClass.simpleName}: ${cause.message}"
                    else -> "${cause?.javaClass?.simpleName ?: error.javaClass.simpleName}: ${cause?.message ?: error.message}"
                }
                AppLog.e(TAG, "player error [${error.errorCodeName}] $detail", error)
                runCatching {
                    android.widget.Toast.makeText(
                        this@PlayerActivity, "Errore player: $detail", android.widget.Toast.LENGTH_LONG,
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
        val deltaBits = (cur - lastBytes) * 8.0
        val liveBps = deltaBits / dtSec
        emaBps = if (emaBps == 0.0) liveBps else emaBps * 0.7 + liveBps * 0.3
        lastBytes = cur
        lastTickNs = now

        if (!statsVisible) return

        val fmt: Format? = p.videoFormat
        val w = fmt?.width ?: p.videoSize.width
        val h = fmt?.height ?: p.videoSize.height
        // Bitrate from ExoPlayer's selected video track; fall back to file average.
        val declaredBitrate = fmt?.bitrate ?: Format.NO_VALUE
        val fromExo = declaredBitrate > 0
        val videoBitrate = when {
            fromExo -> declaredBitrate.toLong()
            totalBytes > 0 && p.duration > 0 -> totalBytes * 8 * 1000 / p.duration
            else -> -1
        }

        // Buffer state: ExoPlayer reports buffered time; estimate bytes from bitrate.
        val bufPct = p.bufferedPercentage
        val bufAheadSec = p.totalBufferedDuration / 1000.0
        val bufBytes = if (videoBitrate > 0) (bufAheadSec * videoBitrate / 8.0).toLong() else -1

        val sb = StringBuilder()
        sb.append("Nome: ").append(if (title.isBlank()) "—" else title).append('\n')
        sb.append("Size: ").append(humanSize(totalBytes)).append('\n')
        sb.append("Risoluzione: ").append(if (w > 0 && h > 0) "${w}x${h}" else "—").append('\n')
        sb.append("Bitrate video: ").append(humanRate(videoBitrate.toDouble()))
            .append(if (fromExo) " (ExoPlayer)" else " (media)").append('\n')
        sb.append("Banda live: ").append(humanRate(liveBps)).append('\n')
        sb.append("Banda media: ").append(humanRate(emaBps)).append('\n')
        sb.append("Buffer: ").append(bufPct).append("%  ")
            .append(String.format("%.1fs", bufAheadSec))
        if (bufBytes > 0) sb.append("  ~").append(humanSize(bufBytes))
        sb.append('\n')
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
                    if (p != null && p.isPlaying) report { it.progress(p.currentPosition, false) }
                    delay(10_000)
                }
            }
        }
    }

    private fun report(block: (EmbyReporter) -> Unit) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { block(r) } }
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
