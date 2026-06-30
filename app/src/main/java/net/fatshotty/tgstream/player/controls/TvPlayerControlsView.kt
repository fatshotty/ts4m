package net.fatshotty.tgstream.player.controls

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import net.fatshotty.tgstream.R

class TvPlayerControlsView(
    context: Context,
    private val adapter: TvPlayerAdapter,
    private val onSeekKey: (forward: Boolean, repeatCount: Int) -> Unit,
) : FrameLayout(context) {
    private val playPauseButton = controlButton(R.drawable.ic_player_play, "Play")
    private val seekBackButton = controlButton(R.drawable.ic_player_rewind, "Back 10 seconds")
    private val seekForwardButton = controlButton(R.drawable.ic_player_forward, "Forward 10 seconds")
    private val audioButton = controlButton(R.drawable.ic_player_audio, "Audio")
    private val subtitlesButton = controlButton(R.drawable.ic_player_subtitles, "Subtitles")
    private val videoButton = controlButton(R.drawable.ic_player_video, "Video")
    private val chaptersButton = controlButton(R.drawable.ic_player_chapters, "Chapters")
    private val statsButton = controlButton(R.drawable.ic_player_stats, "Stats")
    private val positionView = timeTextView()
    private val durationView = timeTextView()
    private val consumedKeyCodes = mutableSetOf<Int>()
    private val remoteScrubSession = RemoteScrubSession()
    private var seekBarScrubbing = false
    private val seekBar = SeekBar(context).apply {
        isFocusable = true
        max = SEEK_BAR_MAX
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return

                val duration = adapter.durationMs()
                if (duration > 0) {
                    adapter.seekTo(duration * progress / SEEK_BAR_MAX)
                    refresh()
                    scheduleAutoHide()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                scheduleAutoHide()
            }
        })
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                seekBarScrubbing = false
                isSelected = false
            }
        }
    }
    private val hideRunnable = Runnable { hide() }

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        visibility = View.GONE
        isFocusable = false
        isClickable = false

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.argb(196, 0, 0, 0))
                setPadding(dp(18), dp(10), dp(18), dp(10))

                addView(playPauseButton)
                addView(seekBackButton)
                addView(positionView)
                addView(seekBar)
                addView(durationView)
                addView(seekForwardButton)
                addView(audioButton)
                addView(subtitlesButton)
                addView(videoButton)
                addView(chaptersButton)
                addView(statsButton)
            },
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        playPauseButton.setOnClickListener {
            togglePlay()
            scheduleAutoHide()
        }
        seekBackButton.setOnClickListener {
            seekFromButton(false, seekBackButton)
        }
        seekForwardButton.setOnClickListener {
            seekFromButton(true, seekForwardButton)
        }
        audioButton.setOnClickListener {
            openOptions("Audio", PlayerOptionKind.AUDIO, adapter.audioOptions(), audioButton)
        }
        subtitlesButton.setOnClickListener {
            openOptions("Subtitles", PlayerOptionKind.SUBTITLE, adapter.subtitleOptions(), subtitlesButton)
        }
        videoButton.setOnClickListener {
            openOptions("Video", PlayerOptionKind.VIDEO, adapter.videoOptions(), videoButton)
        }
        chaptersButton.setOnClickListener {
            openOptions("Chapters", PlayerOptionKind.CHAPTER, adapter.chapterOptions(), chaptersButton)
        }
        statsButton.setOnClickListener {
            toggleStats()
            scheduleAutoHide()
        }

        refresh()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (remoteScrubSession.finishIfReleased(event.keyCode, event.action)) {
                setSeekBarScrubbing(false)
            }
            if (consumedKeyCodes.remove(event.keyCode)) {
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && seekBar.hasFocus()) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                setSeekBarScrubbing(!seekBarScrubbing)
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && seekBarScrubbing) {
                setSeekBarScrubbing(false)
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            if (!seekBarScrubbing && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                seekBackButton.requestFocus()
                scheduleAutoHide()
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            if (!seekBarScrubbing && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekForwardButton.requestFocus()
                scheduleAutoHide()
                consumedKeyCodes.add(event.keyCode)
                return true
            }
        }

        val controlsWereHidden = visibility != View.VISIBLE
        val consumed = when (
            PlaybackKeyPolicy.actionFor(
                keyCode = event.keyCode,
                action = event.action,
                controlsVisible = visibility == View.VISIBLE,
                seekFocused = seekBar.hasFocus(),
                seekScrubbing = seekBarScrubbing,
            )
        ) {
            PlaybackKeyAction.SHOW_CONTROLS -> {
                showAndFocus()
                true
            }
            PlaybackKeyAction.PLAY -> {
                adapter.play()
                refresh()
                scheduleAutoHideIfVisible()
                true
            }
            PlaybackKeyAction.PAUSE -> {
                adapter.pause()
                refresh()
                scheduleAutoHideIfVisible()
                true
            }
            PlaybackKeyAction.TOGGLE_PLAY -> {
                togglePlay()
                scheduleAutoHideIfVisible()
                true
            }
            PlaybackKeyAction.SEEK_BACK -> {
                onSeekKey(false, event.repeatCount)
                showAndFocus()
                true
            }
            PlaybackKeyAction.SEEK_FORWARD -> {
                onSeekKey(true, event.repeatCount)
                showAndFocus()
                true
            }
            PlaybackKeyAction.SCRUB_BACK -> {
                scrubFromFocusedSeekBar(
                    forward = false,
                    repeatCount = event.repeatCount,
                    keyCode = event.keyCode,
                    startRemoteSession = controlsWereHidden,
                )
                true
            }
            PlaybackKeyAction.SCRUB_FORWARD -> {
                scrubFromFocusedSeekBar(
                    forward = true,
                    repeatCount = event.repeatCount,
                    keyCode = event.keyCode,
                    startRemoteSession = controlsWereHidden,
                )
                true
            }
            PlaybackKeyAction.TOGGLE_STATS -> {
                toggleStats()
                scheduleAutoHideIfVisible()
                true
            }
            PlaybackKeyAction.HIDE_CONTROLS -> {
                hide()
                true
            }
            PlaybackKeyAction.IGNORE -> false
        }
        if (event.action == KeyEvent.ACTION_DOWN && consumed) {
            consumedKeyCodes.add(event.keyCode)
        }
        return consumed
    }

    fun showAndFocus() {
        visibility = View.VISIBLE
        bringToFront()
        remoteScrubSession.reset()
        setSeekBarScrubbing(false)
        refresh()
        playPauseButton.requestFocus()
        scheduleAutoHide()
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        remoteScrubSession.reset()
        setSeekBarScrubbing(false)
        visibility = View.GONE
    }

    fun refresh() {
        val audioOptions = adapter.audioOptions()
        val subtitleOptions = adapter.subtitleOptions()
        val videoOptions = adapter.videoOptions()
        val chapterOptions = adapter.chapterOptions()
        val focusedOptionButton = optionButtons().firstOrNull { it.hasFocus() }
        val controlVisibility = ControlVisibility.fromOptions(
            audio = audioOptions,
            subtitles = subtitleOptions,
            video = videoOptions,
            chapters = chapterOptions,
        )

        playPauseButton.setImageResource(
            if (adapter.isPlaying()) R.drawable.ic_player_pause else R.drawable.ic_player_play,
        )
        playPauseButton.contentDescription = if (adapter.isPlaying()) "Pause" else "Play"
        positionView.text = formatTime(adapter.positionMs())
        durationView.text = formatTime(adapter.durationMs())
        updateSeekBar()
        statsButton.contentDescription = if (adapter.isStatsVisible()) "Hide stats" else "Show stats"
        statsButton.isSelected = adapter.isStatsVisible()

        audioButton.visibility = if (controlVisibility.audio) View.VISIBLE else View.GONE
        subtitlesButton.visibility = if (controlVisibility.subtitles) View.VISIBLE else View.GONE
        videoButton.visibility = if (controlVisibility.video) View.VISIBLE else View.GONE
        chaptersButton.visibility = if (controlVisibility.chapters) View.VISIBLE else View.GONE
        statsButton.visibility = View.VISIBLE

        if (focusedOptionButton != null && focusedOptionButton.visibility != View.VISIBLE) {
            playPauseButton.requestFocus()
        }
    }

    private fun togglePlay() {
        if (adapter.isPlaying()) {
            adapter.pause()
        } else {
            adapter.play()
        }
        refresh()
    }

    private fun toggleStats() {
        adapter.setStatsVisible(!adapter.isStatsVisible())
        refresh()
    }

    private fun seekFromButton(forward: Boolean, button: ImageButton) {
        onSeekKey(forward, 0)
        refresh()
        button.requestFocus()
        scheduleAutoHide()
    }

    private fun scrubFromFocusedSeekBar(
        forward: Boolean,
        repeatCount: Int,
        keyCode: Int,
        startRemoteSession: Boolean,
    ) {
        visibility = View.VISIBLE
        bringToFront()
        if (startRemoteSession && remoteScrubSession.begin(keyCode)) {
            setSeekBarScrubbing(true)
        }
        onSeekKey(forward, repeatCount)
        refresh()
        seekBar.requestFocus()
        scheduleAutoHide()
    }

    private fun setSeekBarScrubbing(scrubbing: Boolean) {
        seekBarScrubbing = scrubbing
        seekBar.isSelected = scrubbing
        if (scrubbing) removeCallbacks(hideRunnable) else scheduleAutoHide()
    }

    private fun openOptions(
        title: String,
        kind: PlayerOptionKind,
        options: List<PlayerOption>,
        invokingButton: ImageButton,
    ) {
        if (options.none { it.enabled }) return

        removeCallbacks(hideRunnable)
        TrackSelectionDialog.show(
            context = context,
            title = title,
            options = options,
            onDismiss = {
                refresh()
                if (invokingButton.visibility == View.VISIBLE) {
                    invokingButton.requestFocus()
                }
                scheduleAutoHide()
            },
        ) { option ->
            adapter.selectOption(kind, option.id)
        }
    }

    private fun optionButtons(): List<ImageButton> {
        return listOf(audioButton, subtitlesButton, videoButton, chaptersButton)
    }

    private fun scheduleAutoHideIfVisible() {
        if (visibility == View.VISIBLE) {
            scheduleAutoHide()
        }
    }

    private fun updateSeekBar() {
        val duration = adapter.durationMs()
        val position = adapter.positionMs().coerceAtLeast(0)
        seekBar.progress = if (duration > 0) {
            (position.coerceAtMost(duration) * SEEK_BAR_MAX / duration).toInt()
        } else {
            0
        }
    }

    private fun scheduleAutoHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    private fun controlButton(@DrawableRes iconRes: Int, label: String): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = label
            background = controlButtonBackground()
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = true
            isClickable = true
            minimumWidth = dp(50)
            minimumHeight = dp(46)
            setPadding(dp(11), dp(9), dp(11), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                dp(54),
                dp(48),
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    }

    private fun controlButtonBackground(): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedColor(Color.argb(230, 42, 95, 145)))
            addState(intArrayOf(android.R.attr.state_selected), roundedColor(Color.argb(205, 70, 70, 70)))
            addState(intArrayOf(android.R.attr.state_pressed), roundedColor(Color.argb(235, 62, 122, 184)))
            addState(intArrayOf(), roundedColor(Color.argb(72, 255, 255, 255)))
        }
    }

    private fun roundedColor(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(color)
        }
    }

    private fun timeTextView(): TextView {
        return TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(58)
            text = "0:00"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val AUTO_HIDE_DELAY_MS = 5_000L
        private const val SEEK_BAR_MAX = 1_000
    }
}
