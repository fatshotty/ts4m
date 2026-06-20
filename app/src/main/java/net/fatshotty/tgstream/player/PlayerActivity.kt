package net.fatshotty.tgstream.player

import android.graphics.Color
import android.net.Uri
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

        val vlc = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect", "--no-stats"))
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        mediaPlayer = mp
        mp.attachViews(videoLayout!!, null, false, false)

        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
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
                        report { it.progress(mp.time, !mp.isPlaying) }
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
                AppLog.i(TAG, "libVLC: Playing (t=${mediaPlayer?.time})")
                if (startMs > 0 && !seekedToStart) {
                    mediaPlayer?.time = startMs
                    seekedToStart = true
                }
                if (!startReported) {
                    startReported = true
                    report { it.start(mediaPlayer?.time ?: 0) }
                }
                report { it.progress(mediaPlayer?.time ?: 0, false) }
            }
            MediaPlayer.Event.Paused ->
                report { it.progress(mediaPlayer?.time ?: 0, true) }
            MediaPlayer.Event.EndReached -> {
                report { it.stopped(mediaPlayer?.time ?: 0) }
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
                        if (statsVisible) updateStats(mp)
                        if (mp.isPlaying && tick % 10 == 9) report { it.progress(mp.time, false) }
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

    private fun updateStats(mp: MediaPlayer) {
        val sb = StringBuilder()
        sb.append("Nome: ").append(if (title.isBlank()) "—" else title).append('\n')
        val state = when {
            mp.isPlaying -> "in riproduzione"
            bufferingPct < 100.0 -> "buffering ${bufferingPct.toInt()}%"
            else -> "in pausa"
        }
        sb.append("Stato: ").append(state).append('\n')
        runCatching {
            val vt = mp.currentVideoTrack
            if (vt != null && vt.width > 0) sb.append("Risoluzione: ${vt.width}x${vt.height}\n")
        }
        sb.append("Posizione: ").append(fmtTime(mp.time)).append(" / ").append(fmtTime(durationMs)).append('\n')
        sb.append("Connessioni parallele: ").append(conns).append('\n')
        sb.append("(banda nel Log: 'download: … MB/s')")
        statsView?.text = sb
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
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
                mediaPlayer?.let { it.time = (it.time - 10_000).coerceAtLeast(0); showControls() }; return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                mediaPlayer?.let { it.time = it.time + 10_000; showControls() }; return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun report(block: (EmbyReporter) -> Unit) {
        val r = reporter ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { block(r) } }
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
