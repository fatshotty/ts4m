package org.tgstream.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.tgstream.app.tg.Tg
import org.tgstream.app.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log viewer. Shows the in-memory [AppLog] buffer, live, with copy
 * and clear actions, so the log can be read on the phone without adb.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val copyBtn = Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tgstream log", AppLog.snapshot()))
                Toast.makeText(this@LogActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }
        val clearBtn = Button(this).apply {
            text = "Clear"
            setOnClickListener { AppLog.clear() }
        }
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val log = AppLog.snapshot()
                if (log.isBlank()) {
                    Toast.makeText(this@LogActivity, "Log is empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                isEnabled = false
                text = "Sending..."
                Tg.sendLog("tgstream-log-$stamp.txt", log) { ok, err ->
                    isEnabled = true
                    text = "Send"
                    val msg = if (ok) "Log sent to Saved Messages"
                              else "Send failed: ${err ?: "error"}"
                    Toast.makeText(this@LogActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        buttons.addView(copyBtn)
        buttons.addView(clearBtn)
        buttons.addView(sendBtn)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(buttons)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        root.applySystemBarInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLog.lines.collect { lines ->
                    logView.text = if (lines.isEmpty()) "(no log)" else lines.joinToString("\n")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }
}
