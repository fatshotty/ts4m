package net.fatshotty.tgstream.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import net.fatshotty.tgstream.data.Settings
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
        // After the last D-pad press, wait this long with no further press before
        // committing the real seek (debounce). Covers both held keys (auto-repeat)
        // and rapid taps: the position bar scrubs live, the player seeks once.
        const val SCRUB_COMMIT_DELAY_MS = 600L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var reporter: EmbyReporter? = null
    private var startMs: Long = 0
    private var startReported = false

    // scrub (D-pad seek preview): the bar moves while pressing, the actual seek
    // is committed once the user stops (debounced).
    private var scrubOverlay: View? = null
    private var scrubBar: ProgressBar? = null
    private var scrubPosLabel: TextView? = null
    private var scrubDurLabel: TextView? = null
    private var scrubbing = false
    private var scrubTargetMs = 0L
    private val scrubHandler = Handler(Looper.getMainLooper())
    private val commitScrubRunnable = Runnable { commitScrub() }

    // stats
    private var statsView: TextView? = null
    private var statsVisible = false
    private var title: String = ""
    private var conns: Int = 1
    private var durationMs: Long = 0
    private var dynamicLoadControl: DynamicLoadControl? = null
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
        durationMs = intent.getLongExtra(PlayerActivity.EXTRA_DURATION_MS, 0)
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

        // Scrub overlay (bottom): position — progress bar — duration. Shown only
        // while the user is seeking with the D-pad; hidden once the seek commits.
        val scrub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(40, 24, 40, 24)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM; setMargins(48, 0, 48, 48) }
        }
        val posLbl = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 0, 24, 0); text = "0:00"
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val durLbl = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 0, 0, 0); text = "0:00"
        }
        scrub.addView(posLbl); scrub.addView(bar); scrub.addView(durLbl)
        root.addView(scrub)
        scrubOverlay = scrub; scrubBar = bar; scrubPosLabel = posLbl; scrubDurLabel = durLbl

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

        // Buffer per the user-selected mode. Up to 300s ahead, capped by a byte
        // budget (Java-heap bound). On high-bitrate streams the byte cap binds
        // first; on light streams the time cap lets it buffer far ahead. In
        // "dynamic" mode the cap ramps LOW→HIGH→VHIGH over the first 20s of play.
        val bufferMode = Settings.getBufferMode(this)
        val diskCache = Settings.getDiskCache(this)
        // The buffer mode is independent of the disk cache. The disk cache is a Go
        // proxy feature (off-heap look-ahead); ExoPlayer's RAM buffer always follows
        // the user's chosen mode. Previously disk-cache ON forced a fixed LOW buffer
        // and IGNORED "dynamic", which (a) defeated the per-film adaptation and (b)
        // deadlocked at high bitrate (see minBufferMs note below).
        //
        // CRITICAL: minBufferMs must stay below what targetBufferBytes can hold at the
        // stream's bitrate. With prioritizeTimeOverSizeThresholds=false the byte cap
        // stops loading first; if it is reached before minBufferMs worth of media is
        // buffered (e.g. 48 MB ≈ 7.8s at 49 Mbps but minBuffer was 10–15s), ExoPlayer
        // can never satisfy the minimum and dies with "Playback stuck buffering and
        // not loading". 2.5s is reachable within 48 MB up to ~150 Mbps, so the byte
        // cap alone governs depth. (DynamicLoadControl is safe — its anti-stall clause
        // keeps loading below its minimum regardless of the ramped cap.)
        val minBufferMs = 2_500
        val loadControl = if (bufferMode == Settings.BUFFER_DYNAMIC) {
            DynamicLoadControl(
                lowBytes = BufferTuning.exoBytes(BufferTuning.Tier.LOW),
                highBytes = BufferTuning.exoBytes(BufferTuning.Tier.HIGH),
                vhighBytes = BufferTuning.exoBytes(BufferTuning.Tier.VHIGH),
            ).also { dynamicLoadControl = it; applyStartBuffer() }
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, 300_000, 1_000, 2_000)
                .setTargetBufferBytes(BufferTuning.exoBytes(BufferTuning.tierForMode(bufferMode)))
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }
        AppLog.i(TAG, "buffer mode: $bufferMode disk-cache: $diskCache")

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

        val url = Tg.streamUrl(channel, messages, conns, diskCache)
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

    /**
     * Sizes the dynamic-buffer start cushion from the stream bitrate: ~1.5s for
     * light files, up to ~5s for heavy (~90 Mb/s) ones. Bitrate = total/duration
     * (duration from the launch intent, total from the HEAD probe). The cushion
     * gates both the initial start and post-seek resume — longer wait, smoother
     * playback. No-op until both numbers are known, and on non-dynamic modes.
     */
    private fun applyStartBuffer() {
        val dlc = dynamicLoadControl ?: return
        val total = totalBytes
        val dur = durationMs
        if (total <= 0 || dur <= 0) return
        val mbps = total * 8.0 / (dur / 1000.0) / 1_000_000.0
        val ms = (mbps / 90.0 * 5000.0).coerceIn(1500.0, 5000.0)
        dlc.startBufferUs = (ms * 1000).toLong()
        AppLog.i(TAG, "start buffer ${ms.toInt()}ms (bitrate ${mbps.toInt()} Mbps)")
    }

    // D-pad UP/DOWN toggles the nerd panel — universal on every remote, no INFO
    // key needed. LEFT/RIGHT (and media rewind/ff) drive the scrub-then-commit
    // seek. All are intercepted here, before the PlayerView controller sees them,
    // because on some TV boxes the controller swallows the keys without seeking.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN &&
            (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            toggleStats()
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val forward = code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                onScrubKey(forward, event.repeatCount)
            }
            return true // consume DOWN and UP so the controller never also seeks
        }
        return super.dispatchKeyEvent(event)
    }

    // Step grows the longer the key is held (auto-repeat raises repeatCount), so a
    // long press covers ground fast while a single tap nudges 10s. Separate taps
    // each start at repeatCount 0 → a precise 10s nudge.
    private fun scrubStepMs(repeatCount: Int): Long = when {
        repeatCount >= 15 -> 60_000
        repeatCount >= 6 -> 30_000
        else -> 10_000
    }

    private fun onScrubKey(forward: Boolean, repeatCount: Int) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return // duration unknown (not prepared / live): can't scrub
        if (!scrubbing) {
            scrubbing = true
            scrubTargetMs = p.currentPosition.coerceIn(0, dur)
            scrubOverlay?.visibility = View.VISIBLE
        }
        val step = scrubStepMs(repeatCount)
        scrubTargetMs = (scrubTargetMs + if (forward) step else -step).coerceIn(0, dur)
        scrubBar?.progress = (scrubTargetMs * 1000 / dur).toInt()
        scrubPosLabel?.text = fmtTime(scrubTargetMs)
        scrubDurLabel?.text = fmtTime(dur)
        scrubHandler.removeCallbacks(commitScrubRunnable)
        scrubHandler.postDelayed(commitScrubRunnable, SCRUB_COMMIT_DELAY_MS)
    }

    private fun commitScrub() {
        if (!scrubbing) return
        scrubbing = false
        scrubOverlay?.visibility = View.GONE
        player?.seekTo(scrubTargetMs)
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
                applyStartBuffer()
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
        val af: Format? = p.audioFormat
        val bufPct = p.bufferedPercentage
        val bufAheadSec = p.totalBufferedDuration / 1000.0
        val state = when {
            p.isPlaying -> "in riproduzione"
            p.playbackState == Player.STATE_BUFFERING -> "buffering"
            p.playbackState == Player.STATE_ENDED -> "terminato"
            else -> "in pausa"
        }

        val sb = StringBuilder()
        sb.append("Motore: ExoPlayer\n")
        sb.append("Nome: ").append(if (title.isBlank()) "—" else title).append('\n')
        sb.append("Stato: ").append(state).append('\n')
        sb.append("Risoluzione: ").append(if (w > 0 && h > 0) "${w}x${h}" else "—").append('\n')
        // Video codec + fps
        val vcodec = fmt?.sampleMimeType ?: fmt?.codecs
        if (vcodec != null) {
            sb.append("Codec video: ").append(vcodec)
            val fps = fmt?.frameRate ?: Format.NO_VALUE.toFloat()
            if (fps > 0f) sb.append(String.format(" (%.2f fps)", fps))
            sb.append('\n')
        }
        // Audio codec (channels, rate, language)
        af?.let {
            val parts = StringBuilder(it.sampleMimeType ?: it.codecs ?: "?")
            if (it.channelCount > 0) parts.append(" ${it.channelCount}ch")
            if (it.sampleRate > 0) parts.append(" ${it.sampleRate}Hz")
            if (!it.language.isNullOrBlank()) parts.append(" [${it.language}]")
            sb.append("Codec audio: ").append(parts).append('\n')
        }
        sb.append("Bitrate video: ").append(humanRate(videoBitrate.toDouble()))
            .append(if (fromExo) " (decoder)" else " (media)").append('\n')
        sb.append("Banda live: ").append(humanRate(liveBps)).append('\n')
        sb.append("Banda media: ").append(humanRate(emaBps)).append('\n')
        sb.append("Scaricato: ").append(humanSize(totalBytes)).append('\n')
        sb.append("Buffer: ").append(bufPct).append("%  ").append(String.format("%.1fs", bufAheadSec)).append('\n')
        sb.append("Modalità buffer: ").append(Settings.getBufferMode(this)).append('\n')
        sb.append("Connessioni parallele: ").append(conns).append('\n')
        sb.append("Posizione: ").append(fmtTime(p.currentPosition)).append(" / ").append(fmtTime(p.duration))
        statsView?.text = sb
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) return String.format("%.2f GB", gb)
        return String.format("%.1f MB", bytes / 1_048_576.0)
    }

    /** bits/s → "X.XX MB/s" (megabytes per second). */
    private fun humanRate(bitsPerSec: Double): String {
        if (bitsPerSec <= 0) return "—"
        val mbs = bitsPerSec / 8.0 / 1_000_000.0
        return if (mbs >= 1.0) String.format("%.2f MB/s", mbs)
        else String.format("%.0f KB/s", bitsPerSec / 8.0 / 1000.0)
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
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
        scrubHandler.removeCallbacks(commitScrubRunnable)
        playerView?.player = null
        player?.release()
        player = null
        Tg.clearStreamCache()
    }
}
