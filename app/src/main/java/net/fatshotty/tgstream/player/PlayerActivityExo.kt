package net.fatshotty.tgstream.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import net.fatshotty.tgstream.player.controls.PlayerOption
import net.fatshotty.tgstream.player.controls.PlayerOptionKind
import net.fatshotty.tgstream.player.controls.TvPlayerAdapter
import net.fatshotty.tgstream.player.controls.TvPlayerControlsView
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
 * shared TV controls for playback, seeking, track selection, and stats.
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
    private var tvControls: TvPlayerControlsView? = null
    private var startMs: Long = 0
    private var startReported = false
    private var exoChapters: List<ExoChapter> = emptyList()

    // scrub (D-pad seek preview): the bar moves while pressing, the actual seek
    // is committed once the user stops (debounced).
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
        // Prebuilt by PlayerEntryActivity.
        val streamUrl = intent.getStringExtra(PlayerActivity.EXTRA_STREAM_URL)
            ?: Tg.streamUrl(channel, messages, conns)

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
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }
        playerView = view
        root.addView(view)

        val catcher = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener { tvControls?.showAndFocus() }
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
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; setMargins(32, 32, 32, 32) }
        }
        root.addView(statsView)

        tvControls = TvPlayerControlsView(
            context = this,
            adapter = ExoTvPlayerAdapter(),
            onSeekKey = { forward, repeatCount -> onScrubKey(forward, repeatCount) },
        )
        root.addView(tvControls)

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
        AppLog.i(TAG, "buffer mode: $bufferMode")

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

        val url = streamUrl
        AppLog.i(TAG, "stream url: ${AppLog.redactUrl(url)} (engine=exo, ready=${Tg.isReady})")
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
                if (state == Player.STATE_ENDED) {
                    playbackCompleted = true
                    reportStopped(exo.currentPosition)
                }
                tvControls?.refresh()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                reportProgress(exo.currentPosition, !isPlaying)
                tvControls?.refresh()
            }
            override fun onPositionDiscontinuity(
                oldP: Player.PositionInfo, newP: Player.PositionInfo, reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK)
                    reportProgress(newP.positionMs, !exo.isPlaying)
                tvControls?.refresh()
            }
            override fun onTracksChanged(tracks: Tracks) {
                tvControls?.refresh()
            }
            override fun onMetadata(metadata: Metadata) {
                val parsedChapters = ExoChapterMetadata.extract(metadata)
                if (parsedChapters.isEmpty()) return
                val mergedChapters = ExoChapterMetadata.merge(exoChapters, parsedChapters)
                if (mergedChapters != exoChapters) {
                    exoChapters = mergedChapters
                    AppLog.i(TAG, "Exo chapters detected: ${exoChapters.size}")
                    tvControls?.refresh()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val detail = when (cause) {
                    is HttpDataSource.InvalidResponseCodeException ->
                        "HTTP ${cause.responseCode} from proxy: ${String(cause.responseBody).take(200)}"
                    is HttpDataSource.HttpDataSourceException ->
                        "proxy I/O: ${cause.javaClass.simpleName}: ${cause.message}"
                    else -> "${cause?.javaClass?.simpleName ?: error.javaClass.simpleName}: ${cause?.message ?: error.message}"
                }
                AppLog.e(TAG, "player error [${error.errorCodeName}] $detail", error)
                runCatching {
                    android.widget.Toast.makeText(
                        this@PlayerActivityExo, "Player error: $detail", android.widget.Toast.LENGTH_LONG,
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
        if (statsVisible) updateStats()
        tvControls?.refresh()
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
        val pref = Settings.getPreBufferSeconds(this)
        val total = totalBytes
        val dur = durationMs
        var ms: Double = if (pref > 0) {
            pref * 1000.0 // fixed user choice
        } else {
            // AUTO: scale on bitrate (needs total+duration), ~1.5s..5s.
            if (total <= 0 || dur <= 0) return
            val mbps = total * 8.0 / (dur / 1000.0) / 1_000_000.0
            (mbps / 90.0 * 5000.0).coerceIn(1500.0, 5000.0)
        }
        // Clamp so the cushion fits ExoPlayer's RAM cap at this bitrate; otherwise a
        // large fixed value at high bitrate could never be reached → never starts.
        if (total > 0 && dur > 0) {
            val bytesPerSec = total.toDouble() / (dur / 1000.0)
            val maxMs = 0.85 * BufferTuning.exoBytes(BufferTuning.Tier.VHIGH) / bytesPerSec * 1000.0
            if (ms > maxMs) ms = maxMs
        }
        dlc.startBufferUs = (ms * 1000).toLong()
        AppLog.i(TAG, "start buffer ${ms.toInt()}ms (pref=${if (pref > 0) "${pref}s" else "auto"})")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvControls?.handleKey(event) == true) return true
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
        player?.seekTo(scrubTargetMs)
        tvControls?.refresh()
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
                    tvControls?.refresh()
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
            p.isPlaying -> "playing"
            p.playbackState == Player.STATE_BUFFERING -> "buffering"
            p.playbackState == Player.STATE_ENDED -> "ended"
            else -> "paused"
        }

        val sb = StringBuilder()
        sb.append("Engine: ExoPlayer\n")
        sb.append("Name: ").append(if (title.isBlank()) "-" else title).append('\n')
        sb.append("State: ").append(state).append('\n')
        sb.append("Resolution: ").append(if (w > 0 && h > 0) "${w}x${h}" else "-").append('\n')
        // Video codec + fps
        val vcodec = fmt?.sampleMimeType ?: fmt?.codecs
        if (vcodec != null) {
            sb.append("Video codec: ").append(vcodec)
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
            sb.append("Audio codec: ").append(parts).append('\n')
        }
        sb.append("Video bitrate: ").append(humanRate(videoBitrate.toDouble()))
            .append(if (fromExo) " (decoder)" else " (media)").append('\n')
        sb.append("Live bandwidth: ").append(humanRate(liveBps)).append('\n')
        sb.append("Average bandwidth: ").append(humanRate(emaBps)).append('\n')
        sb.append("Downloaded: ").append(humanSize(totalBytes)).append('\n')
        sb.append("Buffer: ").append(bufPct).append("%  ").append(String.format("%.1fs", bufAheadSec)).append('\n')
        sb.append("Buffer mode: ").append(Settings.getBufferMode(this)).append('\n')
        sb.append("Parallel connections: ").append(conns).append('\n')
        sb.append("Position: ").append(fmtTime(p.currentPosition)).append(" / ").append(fmtTime(p.duration))
        statsView?.text = sb
    }

    private inner class ExoTvPlayerAdapter : TvPlayerAdapter {
        override fun isPlaying(): Boolean = player?.isPlaying == true

        override fun play() {
            player?.play()
        }

        override fun pause() {
            player?.pause()
        }

        override fun durationMs(): Long {
            val dur = player?.duration ?: durationMs
            return if (dur > 0) dur else durationMs
        }

        override fun positionMs(): Long {
            return if (scrubbing) scrubTargetMs else player?.currentPosition ?: 0
        }

        override fun seekTo(positionMs: Long) {
            val p = player ?: return
            p.seekTo(positionMs.coerceAtLeast(0))
            tvControls?.refresh()
        }

        override fun audioOptions(): List<PlayerOption> {
            return trackOptions(C.TRACK_TYPE_AUDIO)
        }

        override fun subtitleOptions(): List<PlayerOption> {
            val tracks = trackOptions(C.TRACK_TYPE_TEXT)
            if (tracks.isEmpty()) return emptyList()
            return listOf(PlayerOption.off(selected = tracks.none { it.selected })) + tracks
        }

        override fun videoOptions(): List<PlayerOption> {
            return trackOptions(C.TRACK_TYPE_VIDEO)
        }

        override fun chapterOptions(): List<PlayerOption> {
            val duration = durationMs()
            val position = positionMs()
            val chapters = exoChapters
                .filter { chapter -> duration <= 0 || chapter.startMs <= duration }
                .sortedBy { chapter -> chapter.startMs }
            if (chapters.isEmpty()) return emptyList()

            val selectedIndex = chapters.indexOfLast { chapter -> chapter.startMs <= position }
                .coerceAtLeast(0)
            return chapters.mapIndexed { index, chapter ->
                PlayerOption.chapter(
                    id = chapter.startMs.toString(),
                    index = index,
                    name = chapter.title,
                    timeOffsetMs = chapter.startMs,
                    selected = index == selectedIndex,
                )
            }
        }

        override fun selectOption(kind: PlayerOptionKind, id: String) {
            val p = player ?: return
            if (kind == PlayerOptionKind.CHAPTER) {
                val targetMs = id.toLongOrNull() ?: return
                p.seekTo(targetMs)
                reportProgress(targetMs, !p.isPlaying)
                tvControls?.refresh()
                return
            }
            val trackType = when (kind) {
                PlayerOptionKind.AUDIO -> C.TRACK_TYPE_AUDIO
                PlayerOptionKind.SUBTITLE -> C.TRACK_TYPE_TEXT
                PlayerOptionKind.VIDEO -> C.TRACK_TYPE_VIDEO
                PlayerOptionKind.CHAPTER -> return
            }
            val builder = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(trackType)

            if (kind == PlayerOptionKind.SUBTITLE && id == "off") {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                p.trackSelectionParameters = builder.build()
                tvControls?.refresh()
                return
            }

            val trackId = parseTrackId(id) ?: return
            val group = p.currentTracks.groups.getOrNull(trackId.groupIndex) ?: return
            if (group.type != trackType || trackId.trackIndex !in 0 until group.length) return

            builder
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        group.mediaTrackGroup,
                        listOf(trackId.trackIndex),
                    ),
                )
            p.trackSelectionParameters = builder.build()
            tvControls?.refresh()
        }

        override fun isStatsVisible(): Boolean = statsVisible

        override fun setStatsVisible(visible: Boolean) {
            statsVisible = visible
            statsView?.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) updateStats()
            tvControls?.refresh()
        }

        private fun trackOptions(trackType: Int): List<PlayerOption> {
            val p = player ?: return emptyList()
            return p.currentTracks.groups.flatMapIndexed { groupIndex, group ->
                if (group.type != trackType) {
                    emptyList()
                } else {
                    (0 until group.length).map { trackIndex ->
                        val format = group.getTrackFormat(trackIndex)
                        PlayerOption.track(
                            id = trackId(groupIndex, trackIndex),
                            title = trackTitle(format, trackType, trackIndex),
                            language = format.language.orEmpty(),
                            details = trackDetails(format, trackType),
                            selected = group.isTrackSelected(trackIndex),
                            enabled = group.isTrackSupported(trackIndex),
                        )
                    }
                }
            }
        }

        private fun trackId(groupIndex: Int, trackIndex: Int): String {
            return "$groupIndex:$trackIndex"
        }

        private fun parseTrackId(id: String): ExoTrackId? {
            val parts = id.split(':')
            if (parts.size != 2) return null
            val groupIndex = parts[0].toIntOrNull() ?: return null
            val trackIndex = parts[1].toIntOrNull() ?: return null
            return ExoTrackId(groupIndex, trackIndex)
        }

        private fun trackTitle(format: Format, trackType: Int, trackIndex: Int): String {
            format.label?.takeIf { it.isNotBlank() }?.let { return it }
            return when (trackType) {
                C.TRACK_TYPE_VIDEO -> {
                    if (format.width > 0 && format.height > 0) {
                        "${format.width}x${format.height}"
                    } else {
                        "Video ${trackIndex + 1}"
                    }
                }
                C.TRACK_TYPE_AUDIO ->
                    format.language?.takeIf { it.isNotBlank() } ?: "Audio ${trackIndex + 1}"
                C.TRACK_TYPE_TEXT ->
                    format.language?.takeIf { it.isNotBlank() } ?: "Subtitle ${trackIndex + 1}"
                else -> "Track ${trackIndex + 1}"
            }
        }

        private fun trackDetails(format: Format, trackType: Int): String {
            val parts = mutableListOf<String>()
            val mime = format.sampleMimeType ?: format.codecs
            if (!mime.isNullOrBlank()) parts += mime
            when (trackType) {
                C.TRACK_TYPE_VIDEO -> {
                    if (format.frameRate > 0f) parts += String.format("%.2f fps", format.frameRate)
                    if (format.bitrate > 0) parts += humanRate(format.bitrate.toDouble())
                }
                C.TRACK_TYPE_AUDIO -> {
                    if (format.channelCount > 0) parts += "${format.channelCount}ch"
                    if (format.sampleRate > 0) parts += "${format.sampleRate}Hz"
                    if (format.bitrate > 0) parts += humanRate(format.bitrate.toDouble())
                }
                C.TRACK_TYPE_TEXT -> {
                    if (!format.codecs.isNullOrBlank() && format.codecs != mime) parts += format.codecs!!
                }
            }
            return parts.joinToString(" ")
        }
    }

    private data class ExoTrackId(
        val groupIndex: Int,
        val trackIndex: Int,
    )

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
                    tvControls?.refresh()
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
                data.putExtra(PlayerActivity.MX_END_BY, "playback_completion")
            } else {
                val pos = player?.currentPosition ?: 0L
                val dur = (player?.duration ?: 0L).let { if (it > 0) it else durationMs }
                data.putExtra(PlayerActivity.MX_END_BY, "user")
                data.putExtra(PlayerActivity.MX_POSITION, pos.toInt())
                if (dur > 0) data.putExtra(PlayerActivity.MX_DURATION, dur.toInt())
            }
            setResult(RESULT_OK, data)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scrubHandler.removeCallbacks(commitScrubRunnable)
        playerView?.player = null
        player?.release()
        player = null
    }
}
