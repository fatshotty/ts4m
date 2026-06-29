package net.fatshotty.tgstream.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.tg.Tg

/** Minimal settings: connections per stream + playback engine. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Max connections per stream (1-16)"
            textSize = 16f
            setPadding(0, 0, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Upper limit. The app uses fewer connections for small files and scales " +
                "up to this value only for large files (~1 per 20 GB)."
            textSize = 12f
            setPadding(0, 0, 0, 16)
        })
        val connField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(Settings.getConnections(this@SettingsActivity).toString())
        }
        root.addView(connField)

        root.addView(TextView(this).apply {
            text = "Playback engine"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Toggles between ExoPlayer (default; best HDR/Dolby Vision) and libVLC
        // (compatible; plays containers ExoPlayer can't, e.g. large OpenDML AVI).
        var engine = Settings.getPlayerEngine(this)
        val engineBtn = Button(this).apply { isFocusable = true }
        fun renderEngine() {
            engineBtn.text = if (engine == Settings.ENGINE_VLC)
                "VLC (compatible) - tap for ExoPlayer"
            else
                "ExoPlayer (HDR/Dolby Vision) - tap for VLC"
        }
        renderEngine()
        engineBtn.setOnClickListener {
            engine = if (engine == Settings.ENGINE_VLC) Settings.ENGINE_EXO else Settings.ENGINE_VLC
            renderEngine()
        }
        root.addView(engineBtn)

        root.addView(TextView(this).apply {
            text = "Buffer"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Cycles LOW -> MID -> HIGH -> VHIGH -> DYNAMIC. Applies to BOTH engines.
        // DYNAMIC ramps the buffer up during playback (ExoPlayer only; on VLC it
        // falls back to a fixed tier because the cache can't be resized at runtime.
        var buffer = Settings.getBufferMode(this)
        val bufferBtn = Button(this).apply { isFocusable = true }
        fun bufferLabel(m: String) = when (m) {
            Settings.BUFFER_LOW -> "LOW (~3s, fast seek)"
            Settings.BUFFER_HIGH -> "HIGH (default, larger buffer)"
            Settings.BUFFER_VHIGH -> "VHIGH (maximum)"
            Settings.BUFFER_DYNAMIC -> "DYNAMIC (ramps during playback)"
            else -> "MID"
        }
        fun renderBuffer() { bufferBtn.text = "Buffer: ${bufferLabel(buffer)} - tap to change" }
        renderBuffer()
        bufferBtn.setOnClickListener {
            val modes = Settings.BUFFER_MODES
            buffer = modes[(modes.indexOf(buffer) + 1) % modes.size]
            renderBuffer()
        }
        root.addView(bufferBtn)

        root.addView(TextView(this).apply {
            text = "Startup/seek pre-buffer (dynamic buffer only)"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Seconds of media to bank before playback starts (and after a seek). AUTO
        // scales it on the stream bitrate (up to ~5s); a fixed value banks a bigger
        // reserve: longer wait at start, smoother run on marginal devices.
        var prebuffer = Settings.getPreBufferSeconds(this)
        val prebufBtn = Button(this).apply { isFocusable = true }
        fun prebufLabel(s: Int) = if (s == Settings.PREBUFFER_AUTO) "AUTO (high, calculated)" else "${s}s"
        fun renderPrebuf() { prebufBtn.text = "Pre-buffer: ${prebufLabel(prebuffer)} - tap to change" }
        renderPrebuf()
        prebufBtn.setOnClickListener {
            val c = Settings.PREBUFFER_CHOICES
            prebuffer = c[(c.indexOf(prebuffer).coerceAtLeast(0) + 1) % c.size]
            renderPrebuf()
        }
        root.addView(prebufBtn)

        decryptWarningText()?.let { warning ->
            root.addView(TextView(this).apply {
                text = warning
                textSize = 13f
                setPadding(0, 40, 0, 0)
            })
        }

        root.addView(TextView(this).apply {
            text = "Telegram credentials (advanced)"
            textSize = 16f
            setPadding(0, 40, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Your api_id/api_hash from my.telegram.org. Leave both empty to use " +
                "the app defaults. Restart the app after changing them."
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        val apiIdField = EditText(this).apply {
            hint = "api_id"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            val cur = Settings.getApiId(this@SettingsActivity)
            if (cur > 0) setText(cur.toString())
        }
        root.addView(apiIdField)
        val apiHashField = EditText(this).apply {
            hint = "api_hash"
            isSingleLine = true
            setText(Settings.getApiHash(this@SettingsActivity))
        }
        root.addView(apiHashField)

        val save = Button(this).apply {
            text = "Save"
            isFocusable = true
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                // Credentials override: both empty -> clear (use the app default);
                // both valid -> store the user's own; anything else -> warn and stop.
                val idStr = apiIdField.text.toString().trim()
                val hashStr = apiHashField.text.toString().trim()
                if (idStr.isEmpty() && hashStr.isEmpty()) {
                    Settings.setApiCredentials(this@SettingsActivity, 0, "")
                } else {
                    val id = idStr.toIntOrNull()
                    if (id == null || id <= 0 || hashStr.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "Invalid api_id/api_hash", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    Settings.setApiCredentials(this@SettingsActivity, id, hashStr)
                }
                val n = connField.text.toString().trim().toIntOrNull() ?: 3
                Settings.setConnections(this@SettingsActivity, n)
                Settings.setPlayerEngine(this@SettingsActivity, engine)
                Settings.setBufferMode(this@SettingsActivity, buffer)
                Settings.setPreBufferSeconds(this@SettingsActivity, prebuffer)
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(TextView(this).apply { setPadding(0, 24, 0, 0) }) // spacer
        root.addView(save)

        // Wrap in a ScrollView: the content is taller than the screen on smaller
        // devices and the Save button fell off-screen. isFillViewport keeps the
        // layout filling short screens.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        // Insets on the ScrollView so its bottom padding lets the last row scroll
        // clear of the navigation bar; root keeps its own content padding.
        scroll.applySystemBarInsets()
        connField.requestFocus()
    }

    private fun decryptWarningText(): String? = when (Tg.decryptHardwareStatus) {
        "software" -> "Warning: this device uses software AES decrypt. " +
            "High-bitrate streams may stutter."
        "unknown" -> "Warning: AES hardware support could not be verified. " +
            "If high-bitrate streams stutter, decrypt may be the bottleneck."
        else -> null
    }
}
