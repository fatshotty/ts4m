package net.fatshotty.tgstream.player.controls

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class TvPlayerControlsView(
    context: Context,
    private val adapter: TvPlayerAdapter,
    private val onSeekKey: (forward: Boolean, repeatCount: Int) -> Unit,
) : FrameLayout(context) {
    private val playPauseButton = controlButton("Play")
    private val seekBackButton = controlButton("-10s")
    private val seekForwardButton = controlButton("+10s")
    private val audioButton = controlButton("Audio")
    private val subtitlesButton = controlButton("Subtitles")
    private val videoButton = controlButton("Video")
    private val chaptersButton = controlButton("Chapters")
    private val statsButton = controlButton("Stats").apply {
        minWidth = dp(104)
    }
    private val positionView = timeTextView()
    private val durationView = timeTextView()
    private val consumedKeyCodes = mutableSetOf<Int>()
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
                setBackgroundColor(Color.argb(224, 0, 0, 0))
                setPadding(dp(16), dp(12), dp(16), dp(12))

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
        if (event.action == KeyEvent.ACTION_UP && consumedKeyCodes.remove(event.keyCode)) {
            return true
        }

        val consumed = when (
            PlaybackKeyPolicy.actionFor(
                keyCode = event.keyCode,
                action = event.action,
                controlsVisible = visibility == View.VISIBLE,
                seekFocused = seekBar.hasFocus(),
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
                scrubFromFocusedSeekBar(false, event.repeatCount)
                true
            }
            PlaybackKeyAction.SCRUB_FORWARD -> {
                scrubFromFocusedSeekBar(true, event.repeatCount)
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
        refresh()
        playPauseButton.requestFocus()
        scheduleAutoHide()
    }

    fun hide() {
        removeCallbacks(hideRunnable)
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

        playPauseButton.text = if (adapter.isPlaying()) "Pause" else "Play"
        positionView.text = formatTime(adapter.positionMs())
        durationView.text = formatTime(adapter.durationMs())
        updateSeekBar()
        statsButton.text = if (adapter.isStatsVisible()) "Hide Stats" else "Stats"

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

    private fun seekFromButton(forward: Boolean, button: Button) {
        onSeekKey(forward, 0)
        refresh()
        button.requestFocus()
        scheduleAutoHide()
    }

    private fun scrubFromFocusedSeekBar(forward: Boolean, repeatCount: Int) {
        visibility = View.VISIBLE
        bringToFront()
        onSeekKey(forward, repeatCount)
        refresh()
        seekBar.requestFocus()
        scheduleAutoHide()
    }

    private fun openOptions(
        title: String,
        kind: PlayerOptionKind,
        options: List<PlayerOption>,
        invokingButton: Button,
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

    private fun optionButtons(): List<Button> {
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

    private fun controlButton(label: String): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            isFocusable = true
            minWidth = dp(64)
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
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
