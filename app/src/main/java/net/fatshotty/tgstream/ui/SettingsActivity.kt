package net.fatshotty.tgstream.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.fatshotty.tgstream.data.Settings

/** Minimal settings: connections per stream + playback engine. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Connessioni simultanee per stream (1-16)"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })
        val connField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(Settings.getConnections(this@SettingsActivity).toString())
        }
        root.addView(connField)

        root.addView(TextView(this).apply {
            text = "Motore di riproduzione"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Toggles between ExoPlayer (default; best HDR/Dolby Vision) and libVLC
        // (compatibile; plays containers ExoPlayer can't, e.g. large OpenDML AVI).
        var engine = Settings.getPlayerEngine(this)
        val engineBtn = Button(this).apply { isFocusable = true }
        fun renderEngine() {
            engineBtn.text = if (engine == Settings.ENGINE_VLC)
                "VLC (compatibile) — tocca per ExoPlayer"
            else
                "ExoPlayer (HDR/Dolby Vision) — tocca per VLC"
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
        // Cycles LOW → MID → HIGH → VHIGH → DYNAMIC. Applies to BOTH engines.
        // DYNAMIC ramps the buffer up during playback (ExoPlayer only; on VLC it
        // falls back to a fixed tier — the cache can't be resized at runtime).
        var buffer = Settings.getBufferMode(this)
        val bufferBtn = Button(this).apply { isFocusable = true }
        fun bufferLabel(m: String) = when (m) {
            Settings.BUFFER_LOW -> "LOW (~2s, seek rapido)"
            Settings.BUFFER_HIGH -> "HIGH (buffer ampio)"
            Settings.BUFFER_VHIGH -> "VHIGH (massimo)"
            Settings.BUFFER_DYNAMIC -> "DYNAMIC (cresce durante il play)"
            else -> "MID (predefinito)"
        }
        fun renderBuffer() { bufferBtn.text = "Buffer: ${bufferLabel(buffer)} — tocca per cambiare" }
        renderBuffer()
        bufferBtn.setOnClickListener {
            val modes = Settings.BUFFER_MODES
            buffer = modes[(modes.indexOf(buffer) + 1) % modes.size]
            renderBuffer()
        }
        root.addView(bufferBtn)

        val save = Button(this).apply {
            text = "Salva"
            isFocusable = true
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                val n = connField.text.toString().trim().toIntOrNull() ?: 3
                Settings.setConnections(this@SettingsActivity, n)
                Settings.setPlayerEngine(this@SettingsActivity, engine)
                Settings.setBufferMode(this@SettingsActivity, buffer)
                Toast.makeText(this@SettingsActivity, "Salvato", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(TextView(this).apply { setPadding(0, 24, 0, 0) }) // spacer
        root.addView(save)

        setContentView(root)
        root.applySystemBarInsets()
        save.requestFocus()
    }
}
