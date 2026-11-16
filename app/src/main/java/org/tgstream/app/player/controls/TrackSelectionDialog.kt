package org.tgstream.app.player.controls

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object TrackSelectionDialog {
    private val normalBackground = Color.TRANSPARENT
    private val focusedBackground = Color.rgb(58, 58, 58)
    private val selectedBackground = Color.rgb(50, 75, 95)
    private val selectedFocusedBackground = Color.rgb(74, 112, 142)

    fun show(
        context: Context,
        title: String,
        options: List<PlayerOption>,
        onDismiss: () -> Unit = {},
        onSelected: (PlayerOption) -> Unit,
    ) {
        val enabledOptions = options.filter { it.enabled }
        if (enabledOptions.isEmpty()) return

        val selectedIndex = enabledOptions.indexOfFirst { it.selected }.let { if (it >= 0) it else 0 }
        val maxListHeightPx = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        val dialog = AlertDialog.Builder(context).create()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
            setBackgroundColor(Color.rgb(24, 24, 24))
        }
        root.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(context.dp(12), 0, context.dp(12), context.dp(18))
        })

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val boundedHeightSpec = View.MeasureSpec.makeMeasureSpec(
                    maxListHeightPx,
                    View.MeasureSpec.AT_MOST,
                )
                super.onMeasure(widthMeasureSpec, boundedHeightSpec)
            }
        }.apply {
            isFillViewport = false
            addView(
                rows,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        enabledOptions.forEachIndexed { index, option ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isSelected = option.selected
                contentDescription = if (option.selected) {
                    listOf("Selected", option.title, option.subtitle)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                } else {
                    listOf(option.title, option.subtitle)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
                setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
                updateRowBackground(this, option.selected, hasFocus())
                setOnFocusChangeListener { view, hasFocus ->
                    updateRowBackground(view, option.selected, hasFocus)
                }
                setOnClickListener {
                    onSelected(option)
                    dialog.dismiss()
                }
            }
            row.addView(TextView(context).apply {
                text = if (option.selected) "* ${option.title}" else option.title
                setTextColor(Color.WHITE)
                textSize = 17f
            })
            if (option.subtitle.isNotBlank()) {
                row.addView(TextView(context).apply {
                    text = option.subtitle
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                    setPadding(context.dp(28), context.dp(2), 0, 0)
                })
            }
            rows.addView(row)
            if (index == selectedIndex) {
                row.post { row.requestFocus() }
            }
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setView(root)
        dialog.setOnShowListener {
            dialog.window?.setDimAmount(0.65f)
        }
        dialog.setOnDismissListener {
            onDismiss()
        }
        dialog.show()
    }

    private fun updateRowBackground(row: View, selected: Boolean, focused: Boolean) {
        row.setBackgroundColor(
            when {
                selected && focused -> selectedFocusedBackground
                focused -> focusedBackground
                selected -> selectedBackground
                else -> normalBackground
            },
        )
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
