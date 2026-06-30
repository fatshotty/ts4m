package net.fatshotty.tgstream.player.controls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

class TouchPlaybackOverlay(
    context: Context,
    private val adapter: TvPlayerAdapter,
    private val controls: () -> TvPlayerControlsView?,
    private val onSeekKey: (forward: Boolean, repeatCount: Int) -> Unit,
) : FrameLayout(context) {
    private val feedbackView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(175, 0, 0, 0))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 24f
        visibility = View.GONE
        minWidth = dp(104)
        minHeight = dp(64)
        setPadding(dp(24), 0, dp(24), 0)
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }
    private val hideFeedbackRunnable = Runnable { feedbackView.visibility = View.GONE }
    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleDoubleTap(e.x)
                return true
            }
        },
    )

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isClickable = true
        isFocusable = false
        addView(feedbackView)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return detector.onTouchEvent(event) || true
    }

    private fun handleSingleTap() {
        if (TouchGesturePolicy.singleTapAction() == TouchGestureAction.TOGGLE_PLAY) {
            if (adapter.isPlaying()) {
                adapter.pause()
                showFeedback("Pause")
            } else {
                adapter.play()
                showFeedback("Play")
            }
            controls()?.showAndFocus()
            controls()?.refresh()
        }
    }

    private fun handleDoubleTap(x: Float) {
        when (TouchGesturePolicy.doubleTapAction(width, x)) {
            TouchGestureAction.SEEK_BACK -> {
                onSeekKey(false, 0)
                controls()?.showAndFocus()
                controls()?.refresh()
                showFeedback("-10s", left = true)
            }
            TouchGestureAction.SEEK_FORWARD -> {
                onSeekKey(true, 0)
                controls()?.showAndFocus()
                controls()?.refresh()
                showFeedback("+10s", left = false)
            }
            TouchGestureAction.TOGGLE_PLAY -> handleSingleTap()
        }
    }

    private fun showFeedback(label: String, left: Boolean? = null) {
        removeCallbacks(hideFeedbackRunnable)
        feedbackView.text = label
        feedbackView.visibility = View.VISIBLE
        val gravity = when (left) {
            true -> Gravity.CENTER_VERTICAL or Gravity.START
            false -> Gravity.CENTER_VERTICAL or Gravity.END
            null -> Gravity.CENTER
        }
        feedbackView.layoutParams = (feedbackView.layoutParams as LayoutParams).apply {
            this.gravity = gravity
            marginStart = dp(96)
            marginEnd = dp(96)
        }
        postDelayed(hideFeedbackRunnable, FEEDBACK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideFeedbackRunnable)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val FEEDBACK_MS = 650L
    }
}
