package net.fatshotty.tgstream.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
import net.fatshotty.tgstream.player.controls.PlayerOption
import net.fatshotty.tgstream.player.controls.PlayerOptionKind
import net.fatshotty.tgstream.player.controls.TouchPlaybackOverlay
import net.fatshotty.tgstream.player.controls.TvPlayerAdapter
import net.fatshotty.tgstream.player.controls.TvPlayerControlsView
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
        // Debounce before a D-pad seek is actually applied (see PlayerActivityExo).
        private const val SCRUB_COMMIT_DELAY_MS = 600L
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_MESSAGES = "messages"
        const val EXTRA_STREAM_URL = "stream_url" // prebuilt local proxy URL
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

        // MX Player result-intent keys — the de-facto Android external-player
        // protocol that Stremio/Nuvio read on return to update Continue Watching.
        // On exit we set RESULT_OK with end_by="playback_completion" (watched to the
        // end) or end_by="user" + position/duration (ms) so the launcher can resume.
        const val MX_POSITION = "position"
        const val MX_DURATION = "duration"
        const val MX_END_BY = "end_by"
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

    private var tvControls: TvPlayerControlsView? = null

    // scrub (D-pad seek preview): the seekbar moves while pressing; the actual
    // seek is committed once the user stops (debounced).
    private var scrubbing = false
    private var scrubTargetMs = 0L
    private val scrubHandler = Handler(Looper.getMainLooper())
    private val commitScrubRunnable = Runnable { commitScrub() }

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

        val url = intent.getStringExtra(EXTRA_STREAM_URL)
            ?: Tg.streamUrl(channel, messages, conns)
        AppLog.i(TAG, "stream url: ${AppLog.redactUrl(url)} (engineReady=${Tg.isReady})")

        // libVLC's network cache is fixed at media init and cannot be resized at
        // runtime, so "dynamic" can't ramp here — it falls back to a fixed tier.
        val bufferMode = net.fatshotty.tgstream.data.Settings.getBufferMode(this)
        val bufferTier = net.fatshotty.tgstream.util.BufferTuning.tierForMode(bufferMode)
        val cachingMs = net.fatshotty.tgstream.util.BufferTuning.vlcCachingMs(bufferTier)
        AppLog.i(TAG, "buffer mode: $bufferMode → tier=$bufferTier caching=${cachingMs}ms")
        val vlc = LibVLC(this, arrayListOf("--network-caching=$cachingMs", "--http-reconnect"))
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
        val useHw = !isEmulator
        media.setHWDecoderEnabled(useHw, false)
        AppLog.i(TAG, "HW decode=$useHw (emulator=$isEmulator)")
        media.addOption(":network-caching=$cachingMs")
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

        val controlsAdapter = VlcTvPlayerAdapter()
        tvControls = TvPlayerControlsView(
            context = this,
            adapter = controlsAdapter,
            onSeekKey = { forward, repeatCount -> onScrubKey(forward, repeatCount) },
        )
        root.addView(
            TouchPlaybackOverlay(
                context = this,
                adapter = controlsAdapter,
                controls = { tvControls },
                onSeekKey = { forward, repeatCount -> onScrubKey(forward, repeatCount) },
            ),
        )

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

        root.addView(tvControls)

        setContentView(root)
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
                playbackCompleted = true
                runOnUiThread { finish() }
            }
            MediaPlayer.Event.EncounteredError -> {
                AppLog.e(TAG, "libVLC encountered error")
                runOnUiThread {
                    runCatching {
                        Toast.makeText(this, "Playback error", Toast.LENGTH_LONG).show()
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
                        tvControls?.refresh()
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

    private fun toggleStats() {
        statsVisible = !statsVisible
        statsView?.visibility = if (statsVisible) View.VISIBLE else View.GONE
        mediaPlayer?.let { if (statsVisible) updateStats(it) }
        tvControls?.refresh()
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
        sb.append("Name: ").append(if (title.isBlank()) "-" else title).append('\n')
        val state = when {
            mp.isPlaying -> "playing"
            bufferingPct < 100.0 -> "buffering ${bufferingPct.toInt()}%"
            else -> "paused"
        }
        sb.append("State: ").append(state).append('\n')

        // Video track (resolution, codec, bitrate, fps)
        runCatching {
            val vt = mp.currentVideoTrack
            if (vt != null && vt.width > 0) {
                sb.append("Resolution: ${vt.width}x${vt.height}\n")
                val fps = if (vt.frameRateDen > 0) vt.frameRateNum.toDouble() / vt.frameRateDen else 0.0
                sb.append("Video codec: ").append(vt.codec ?: "?")
                if (vt.bitrate > 0) sb.append(" @ ").append(humanRate(vt.bitrate.toDouble()))
                if (fps > 0) sb.append(String.format(" (%.2f fps)", fps))
                sb.append('\n')
            }
        }

        // Audio track (codec, channels, rate, language) — cached once parsed.
        if (audioInfo == null) audioInfo = readAudioInfo(mp)
        audioInfo?.let { sb.append("Audio codec: ").append(it).append('\n') }

        // Download bandwidth (our own measurement from readBytes)
        sb.append("Live bandwidth: ").append(humanBytesRate(liveBytesPerSec)).append('\n')
        sb.append("Average bandwidth: ").append(humanBytesRate(emaBytesPerSec)).append('\n')
        sb.append("Downloaded: ").append(humanSize(totalBytes)).append('\n')

        lastStats?.let { st ->
            // libVLC reports these as a scaled float where value*8 ≈ Mbps.
            sb.append("inputBitrate: ").append(humanRate(st.inputBitrate * 8.0 * 1_000_000.0)).append('\n')
            sb.append("demuxBitrate (content): ").append(humanRate(st.demuxBitrate * 8.0 * 1_000_000.0)).append('\n')
        }
        sb.append("Buffering: ").append(bufferingPct.toInt()).append("%\n")
        sb.append("Buffer mode: ").append(net.fatshotty.tgstream.data.Settings.getBufferMode(this)).append('\n')
        sb.append("Parallel connections: ").append(conns).append('\n')
        sb.append("Position: ").append(fmtTime(mp.time)).append(" / ").append(fmtTime(durationMs))
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

    /** bits/s → "X.XX MB/s (Y Mbps)" — MB primary, Mbps for reference. */
    private fun humanRate(bitsPerSec: Double): String {
        if (bitsPerSec <= 0) return "—"
        val mbs = bitsPerSec / 8.0 / 1_000_000.0
        val mbps = bitsPerSec / 1_000_000.0
        return if (mbs >= 1.0) String.format("%.2f MB/s (%.0f Mbps)", mbs, mbps)
        else String.format("%.0f KB/s (%.1f Mbps)", bitsPerSec / 8.0 / 1000.0, mbps)
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvControls?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                toggleStats(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Step grows the longer the key is held (auto-repeat raises repeatCount); a
    // single tap nudges 10s. Mirrors PlayerActivityExo.
    private fun scrubStepMs(repeatCount: Int): Long = when {
        repeatCount >= 15 -> 60_000
        repeatCount >= 6 -> 30_000
        else -> 10_000
    }

    private fun onScrubKey(forward: Boolean, repeatCount: Int) {
        val dur = durationMs
        if (dur <= 0) return // duration unknown yet: can't scrub
        if (!scrubbing) {
            scrubbing = true
            scrubTargetMs = (mediaPlayer?.time ?: 0).coerceIn(0, dur)
            if (tvControls?.visibility != View.VISIBLE) tvControls?.showAndFocus()
        }
        val step = scrubStepMs(repeatCount)
        scrubTargetMs = (scrubTargetMs + if (forward) step else -step).coerceIn(0, dur)
        tvControls?.refresh()
        scrubHandler.removeCallbacks(commitScrubRunnable)
        scrubHandler.postDelayed(commitScrubRunnable, SCRUB_COMMIT_DELAY_MS)
    }

    private fun commitScrub() {
        if (!scrubbing) return
        scrubbing = false
        val mp = mediaPlayer ?: return
        mp.time = scrubTargetMs
        reportProgress(scrubTargetMs, !mp.isPlaying, "TimeUpdate")
        tvControls?.refresh()
    }

    private inner class VlcTvPlayerAdapter : TvPlayerAdapter {
        override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

        override fun play() {
            mediaPlayer?.play()
        }

        override fun pause() {
            mediaPlayer?.pause()
        }

        override fun durationMs(): Long = durationMs

        override fun positionMs(): Long {
            return if (scrubbing) scrubTargetMs else mediaPlayer?.time ?: 0
        }

        override fun seekTo(positionMs: Long) {
            val mp = mediaPlayer ?: return
            val target = positionMs.coerceAtLeast(0)
            mp.time = target
            reportProgress(target, !mp.isPlaying, "TimeUpdate")
        }

        override fun audioOptions(): List<PlayerOption> {
            val mp = mediaPlayer ?: return emptyList()
            val selectedId = runCatching { mp.getAudioTrack() }.getOrDefault(Int.MIN_VALUE)
            return runCatching {
                mp.getAudioTracks()
                    ?.map { track ->
                        PlayerOption.track(
                            id = track.id.toString(),
                            title = track.name.orEmpty(),
                            selected = track.id == selectedId,
                        )
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        override fun subtitleOptions(): List<PlayerOption> {
            val mp = mediaPlayer ?: return listOf(PlayerOption.off(selected = true))
            val selectedId = runCatching { mp.getSpuTrack() }.getOrDefault(-1)
            val tracks = runCatching {
                mp.getSpuTracks()
                    ?.filter { it.id >= 0 }
                    ?.map { track ->
                        PlayerOption.track(
                            id = track.id.toString(),
                            title = track.name.orEmpty(),
                            selected = track.id == selectedId,
                        )
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
            return listOf(PlayerOption.off(id = "-1", selected = selectedId < 0)) + tracks
        }

        override fun videoOptions(): List<PlayerOption> {
            val mp = mediaPlayer ?: return emptyList()
            val selectedId = runCatching { mp.getVideoTrack() }.getOrDefault(Int.MIN_VALUE)
            return runCatching {
                mp.getVideoTracks()
                    ?.filter { it.id >= 0 }
                    ?.map { track ->
                        PlayerOption.track(
                            id = track.id.toString(),
                            title = track.name.orEmpty(),
                            selected = track.id == selectedId,
                        )
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        override fun chapterOptions(): List<PlayerOption> {
            val mp = mediaPlayer ?: return emptyList()
            val titleIndex = runCatching { mp.getTitle() }.getOrDefault(-1)
            if (titleIndex < 0) return emptyList()
            val selectedChapter = runCatching { mp.getChapter() }.getOrDefault(-1)
            return runCatching {
                mp.getChapters(titleIndex)
                    ?.mapIndexed { index, chapter ->
                        PlayerOption.chapter(
                            id = index.toString(),
                            index = index,
                            name = chapter.name.orEmpty(),
                            timeOffsetMs = chapter.timeOffset,
                            selected = index == selectedChapter,
                        )
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        override fun selectOption(kind: PlayerOptionKind, id: String) {
            val mp = mediaPlayer ?: return
            val trackId = id.toIntOrNull() ?: return
            when (kind) {
                PlayerOptionKind.AUDIO -> mp.setAudioTrack(trackId)
                PlayerOptionKind.SUBTITLE -> mp.setSpuTrack(trackId)
                PlayerOptionKind.VIDEO -> mp.setVideoTrack(trackId)
                PlayerOptionKind.CHAPTER -> {
                    mp.setChapter(trackId)
                    reportProgress(mp.time, !mp.isPlaying, "TimeUpdate")
                }
            }
            tvControls?.refresh()
        }

        override fun isStatsVisible(): Boolean = statsVisible

        override fun setStatsVisible(visible: Boolean) {
            statsVisible = visible
            statsView?.visibility = if (visible) View.VISIBLE else View.GONE
            mediaPlayer?.let { if (visible) updateStats(it) }
        }
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

    @Volatile private var playbackCompleted = false
    private var resultRelayed = false

    /**
     * Relay an MX-style result to whoever launched us (via PlayerEntryActivity) so
     * it can persist the resume position. Harmless when started without a result.
     */
    override fun finish() {
        if (!resultRelayed) {
            resultRelayed = true
            val data = Intent()
            if (playbackCompleted) {
                data.putExtra(MX_END_BY, "playback_completion")
            } else {
                data.putExtra(MX_END_BY, "user")
                data.putExtra(MX_POSITION, (mediaPlayer?.time ?: 0L).toInt())
                if (durationMs > 0) data.putExtra(MX_DURATION, durationMs.toInt())
            }
            setResult(RESULT_OK, data)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scrubHandler.removeCallbacks(commitScrubRunnable)
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
