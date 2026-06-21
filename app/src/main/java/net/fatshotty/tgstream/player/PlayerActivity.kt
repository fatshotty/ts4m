package net.fatshotty.tgstream.player

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fatshotty.tgstream.emby.EmbyReporter
import net.fatshotty.tgstream.tg.Tg
import net.fatshotty.tgstream.util.AppLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Internal player backed by libVLC — plays/seeks virtually any container/codec
 * straight from the localhost Go proxy, including large OpenDML AVI that
 * ExoPlayer can't seek. Reports progress to the source app (Emby). Works with
 * touch and a TV D-pad (custom overlay controls + key handling).
 */
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

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var reporter: EmbyReporter? = null

    private var startMs: Long = 0
    private var seekedToStart = false
    private var startReported = false
    private var title: String = ""
    private var conns: Int = 1
    @Volatile private var durationMs: Long = 0
    @Volatile private var bufferingPct: Double = 100.0

    // controls
    private var controlsBar: View? = null
    private var seekBar: SeekBar? = null
    private var positionLabel: TextView? = null
    private var durationLabel: TextView? = null
    private var playPauseBtn: Button? = null
    private var userSeeking = false
    private val hideControls = Runnable { controlsBar?.visibility = View.GONE }

    // stats
    private var statsView: TextView? = null
    private var statsVisible = false
    private var lastReadBytes = 0
    private var lastStatsNs = 0L
    private var liveBytesPerSec = 0.0
    private var emaBytesPerSec = 0.0
    private var totalBytes = 0L
    @Volatile private var lastStats: IMedia.Stats? = null
    private var audioInfo: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val channel = intent.getLongExtra(EXTRA_CHANNEL, 0)
        val messages = intent.getStringExtra(EXTRA_MESSAGES) ?: ""
        conns = intent.getIntExtra(EXTRA_CONNS, 3)
        startMs = intent.getLongExtra(EXTRA_START_MS, 0)
        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0)
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

        buildUi()

        val url = Tg.streamUrl(channel, messages, conns)
        AppLog.i(TAG, "stream url: $url (engineReady=${Tg.isReady})")

        val vlc = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect"))
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        mediaPlayer = mp
        mp.attachViews(videoLayout!!, null, false, false)

        val media = Media(vlc, Uri.parse(url))
        // HW decode on real devices; the Android emulator's MediaCodec deadlocks
        // ("buffer deadlock prevented") freezing the video, so use SW there.
        val isEmulator = Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk")
        media.setHWDecoderEnabled(!isEmulator, false)
        AppLog.i(TAG, "HW decode=${!isEmulator} (emulator=$isEmulator)")
        media.addOption(":network-caching=1500")
        mp.media = media
        media.release()

        mp.setEventListener { ev -> onVlcEvent(ev) }
        mp.play()

        startUiLoop()
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        val video = VLCVideoLayout(this)
        video.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
        videoLayout = video
        root.addView(video)

        // Transparent catcher to toggle controls on tap (above video, below bars).
        val catcher = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener { toggleControls() }
        }
        root.addView(catcher)

        statsView = TextView(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; setMargins(32, 32, 32, 32) }
        }
        root.addView(statsView)

        val infoBtn = Button(this).apply {
            text = "ⓘ"
            isFocusable = true
            alpha = 0.6f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, 24, 24, 0) }
            setOnClickListener { toggleStats() }
        }
        root.addView(infoBtn)

        // Bottom control bar: play/pause, position, seekbar, duration.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(24, 16, 24, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM }
        }
        playPauseBtn = Button(this).apply {
            text = "❚❚"
            isFocusable = true
            setOnClickListener { togglePlay() }
        }
        positionLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 0, 16, 0); text = "0:00"
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionLabel?.text = fmtTime((progress / 1000.0 * durationMs).toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; showControls() }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    mediaPlayer?.let { mp ->
                        mp.position = sb.progress / 1000f
                        reportProgress(mp.time, !mp.isPlaying, "TimeUpdate")
                    }
                    userSeeking = false
                }
            })
        }
        durationLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 0, 0, 0); text = "0:00"
        }
        bar.addView(playPauseBtn)
        bar.addView(positionLabel)
        bar.addView(seekBar)
        bar.addView(durationLabel)
        controlsBar = bar
        root.addView(bar)

        setContentView(root)
        showControls()
    }

    /** libVLC events fire on a VLC thread; only do thread-safe work or post to UI. */
    private fun onVlcEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                val mp = mediaPlayer
                if (startMs > 0 && !seekedToStart) {
                    mp?.time = startMs
                    seekedToStart = true
                }
                val pos = mp?.time ?: 0 // captured here, never read off-thread
                AppLog.i(TAG, "libVLC: Playing (t=$pos)")
                if (!startReported) {
                    startReported = true
                    reportStart(pos)
                } else {
                    reportProgress(pos, false, "Unpause")
                }
            }
            MediaPlayer.Event.Paused ->
                reportProgress(mediaPlayer?.time ?: 0, true, "Pause")
            MediaPlayer.Event.EndReached -> {
                reportStopped(mediaPlayer?.time ?: 0)
                runOnUiThread { finish() }
            }
            MediaPlayer.Event.EncounteredError -> {
                AppLog.e(TAG, "libVLC encountered error")
                runOnUiThread {
                    runCatching {
                        Toast.makeText(this, "Errore di riproduzione", Toast.LENGTH_LONG).show()
                    }
                }
            }
            MediaPlayer.Event.Buffering -> bufferingPct = ev.buffering.toDouble()
        }
    }

    private fun startUiLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tick = 0
                while (true) {
                    val mp = mediaPlayer
                    if (mp != null) {
                        val len = mp.length
                        if (len > 0) durationMs = len
                        if (!userSeeking && durationMs > 0) {
                            seekBar?.progress = (mp.position * 1000).toInt()
                            positionLabel?.text = fmtTime(mp.time)
                            durationLabel?.text = fmtTime(durationMs)
                        }
                        playPauseBtn?.text = if (mp.isPlaying) "❚❚" else "▶"
                        sampleBandwidth(mp)
                        if (statsVisible) updateStats(mp)
                        if (mp.isPlaying && tick % 10 == 9) reportProgress(mp.time, false, "TimeUpdate")
                    }
                    tick++
                    delay(1000)
                }
            }
        }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
        showControls()
    }

    private fun toggleControls() {
        val bar = controlsBar ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            bar.removeCallbacks(hideControls)
        } else {
            showControls()
        }
    }

    private fun showControls() {
        val bar = controlsBar ?: return
        bar.visibility = View.VISIBLE
        bar.removeCallbacks(hideControls)
        bar.postDelayed(hideControls, 4000)
    }

    private fun toggleStats() {
        statsVisible = !statsVisible
        statsView?.visibility = if (statsVisible) View.VISIBLE else View.GONE
    }

    /** Samples libVLC's read counter to compute our own download rate. Runs
     *  every second regardless of overlay visibility. readBytes is a 32-bit
     *  counter, so deltas are computed with wrap-around handling. */
    private fun sampleBandwidth(mp: MediaPlayer) {
        val media = mp.media ?: return
        val stats = runCatching { media.stats }.getOrNull()
        runCatching { media.release() }
        lastStats = stats ?: return
        val now = System.nanoTime()
        if (lastStatsNs == 0L) {
            lastStatsNs = now; lastReadBytes = stats.readBytes; return
        }
        val dt = (now - lastStatsNs).coerceAtLeast(1) / 1_000_000_000.0
        val rawDelta = stats.readBytes - lastReadBytes // Int, wraps correctly
        val delta = if (rawDelta >= 0) rawDelta.toLong() else rawDelta.toLong() + 0x1_0000_0000L
        liveBytesPerSec = delta / dt
        emaBytesPerSec = if (emaBytesPerSec == 0.0) liveBytesPerSec else emaBytesPerSec * 0.7 + liveBytesPerSec * 0.3
        totalBytes += delta
        lastReadBytes = stats.readBytes
        lastStatsNs = now
    }

    private fun updateStats(mp: MediaPlayer) {
        val sb = StringBuilder()
        sb.append("Nome: ").append(if (title.isBlank()) "—" else title).append('\n')
        val state = when {
            mp.isPlaying -> "in riproduzione"
            bufferingPct < 100.0 -> "buffering ${bufferingPct.toInt()}%"
            else -> "in pausa"
        }
        sb.append("Stato: ").append(state).append('\n')

        // Video track (resolution, codec, bitrate, fps)
        runCatching {
            val vt = mp.currentVideoTrack
            if (vt != null && vt.width > 0) {
                sb.append("Risoluzione: ${vt.width}x${vt.height}\n")
                val fps = if (vt.frameRateDen > 0) vt.frameRateNum.toDouble() / vt.frameRateDen else 0.0
                sb.append("Codec video: ").append(vt.codec ?: "?")
                if (vt.bitrate > 0) sb.append(" @ ").append(humanRate(vt.bitrate.toDouble()))
                if (fps > 0) sb.append(String.format(" (%.2f fps)", fps))
                sb.append('\n')
            }
        }

        // Audio track (codec, channels, rate, language) — cached once parsed.
        if (audioInfo == null) audioInfo = readAudioInfo(mp)
        audioInfo?.let { sb.append("Codec audio: ").append(it).append('\n') }

        // Download bandwidth (our own measurement from readBytes)
        sb.append("Banda live: ").append(humanBytesRate(liveBytesPerSec)).append('\n')
        sb.append("Banda media: ").append(humanBytesRate(emaBytesPerSec)).append('\n')
        sb.append("Scaricato: ").append(humanSize(totalBytes)).append('\n')

        lastStats?.let { st ->
            // libVLC reports these as a scaled float where value*8 ≈ Mbps.
            sb.append(String.format("inputBitrate: %.1f Mbps\n", st.inputBitrate * 8))
            sb.append(String.format("demuxBitrate (contenuto): %.1f Mbps\n", st.demuxBitrate * 8))
        }
        sb.append("Buffering: ").append(bufferingPct.toInt()).append("%\n")
        sb.append("Connessioni parallele: ").append(conns).append('\n')
        sb.append("Posizione: ").append(fmtTime(mp.time)).append(" / ").append(fmtTime(durationMs))
        statsView?.text = sb
    }

    private fun readAudioInfo(mp: MediaPlayer): String? {
        val media = mp.media ?: return null
        var info: String? = null
        runCatching {
            for (i in 0 until media.trackCount) {
                val t = media.getTrack(i)
                if (t.type == IMedia.Track.Type.Audio) {
                    val a = t as IMedia.AudioTrack
                    val parts = StringBuilder(a.codec ?: "?")
                    if (a.channels > 0) parts.append(" ${a.channels}ch")
                    if (a.rate > 0) parts.append(" ${a.rate}Hz")
                    if (!a.language.isNullOrBlank()) parts.append(" [${a.language}]")
                    info = parts.toString()
                    break
                }
            }
        }
        runCatching { media.release() }
        return info
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) return String.format("%.2f GB", gb)
        return String.format("%.1f MB", bytes / 1_048_576.0)
    }

    /** bytes/s → "X.X MB/s (Y Mbps)". */
    private fun humanBytesRate(bytesPerSec: Double): String {
        if (bytesPerSec <= 0) return "—"
        val mbs = bytesPerSec / 1_000_000.0
        val mbps = bytesPerSec * 8 / 1_000_000.0
        return if (mbs >= 1.0) String.format("%.2f MB/s (%.0f Mbps)", mbs, mbps)
        else String.format("%.0f KB/s (%.1f Mbps)", bytesPerSec / 1000.0, mbps)
    }

    /** bits/s → Mbps/kbps (for declared track bitrate). */
    private fun humanRate(bitsPerSec: Double): String {
        if (bitsPerSec <= 0) return "—"
        val mbps = bitsPerSec / 1_000_000.0
        return if (mbps >= 1.0) String.format("%.2f Mbps", mbps) else String.format("%.0f kbps", bitsPerSec / 1000.0)
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    // D-pad UP toggles the nerd/info panel — universal on every remote (no INFO
    // key needed). Intercepted before view dispatch so it works even while the
    // on-screen controls are focused. UP/DOWN aren't used for anything else.
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
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                toggleStats(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (controlsBar?.visibility != View.VISIBLE) { togglePlay(); return true }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                mediaPlayer?.let {
                    it.time = (it.time - 10_000).coerceAtLeast(0)
                    reportProgress(it.time, !it.isPlaying, "TimeUpdate"); showControls()
                }; return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                mediaPlayer?.let {
                    it.time = it.time + 10_000
                    reportProgress(it.time, !it.isPlaying, "TimeUpdate"); showControls()
                }; return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Position is always captured by the caller (player thread) and passed in as
    // a primitive — never read inside the IO coroutine — so reports always carry
    // the real/seeked position, not a stale/off-thread value.
    private fun reportStart(posMs: Long) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.start(posMs) } }
    }

    private fun reportProgress(posMs: Long, paused: Boolean, event: String) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.progress(posMs, paused, event) } }
    }

    private fun reportStopped(posMs: Long) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { r.stopped(posMs) } }
    }

    override fun onStop() {
        super.onStop()
        val pos = mediaPlayer?.time ?: 0
        val r = reporter
        if (r != null) Thread { runCatching { r.stopped(pos) } }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.detachViews() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        runCatching { libVlc?.release() }
        libVlc = null
    }
}
