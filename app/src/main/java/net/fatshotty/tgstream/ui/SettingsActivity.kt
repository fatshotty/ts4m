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

/** Minimal settings: connections per stream + playback engine. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Connessioni max per stream (1-16)"
            textSize = 16f
            setPadding(0, 0, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Tetto massimo. L'app ne usa meno per i file piccoli e scala " +
                "fino a questo valore solo per i file grandi (~1 ogni 20 GB)."
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
            Settings.BUFFER_LOW -> "LOW (~3s, seek rapido)"
            Settings.BUFFER_HIGH -> "HIGH (predefinito, buffer ampio)"
            Settings.BUFFER_VHIGH -> "VHIGH (massimo)"
            Settings.BUFFER_DYNAMIC -> "DYNAMIC (cresce durante il play)"
            else -> "MID"
        }
        fun renderBuffer() { bufferBtn.text = "Buffer: ${bufferLabel(buffer)} — tocca per cambiare" }
        renderBuffer()
        bufferBtn.setOnClickListener {
            val modes = Settings.BUFFER_MODES
            buffer = modes[(modes.indexOf(buffer) + 1) % modes.size]
            renderBuffer()
        }
        root.addView(bufferBtn)

        root.addView(TextView(this).apply {
            text = "Pre-buffer avvio/seek (solo buffer dinamico)"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Seconds of media to bank before playback starts (and after a seek). AUTO
        // scales it on the stream bitrate (up to ~5s); a fixed value banks a bigger
        // reserve — longer wait at start, smoother run on marginal devices.
        var prebuffer = Settings.getPreBufferSeconds(this)
        val prebufBtn = Button(this).apply { isFocusable = true }
        fun prebufLabel(s: Int) = if (s == Settings.PREBUFFER_AUTO) "AUTO (alto, calcolato)" else "${s}s"
        fun renderPrebuf() { prebufBtn.text = "Pre-buffer: ${prebufLabel(prebuffer)} — tocca per cambiare" }
        renderPrebuf()
        prebufBtn.setOnClickListener {
            val c = Settings.PREBUFFER_CHOICES
            prebuffer = c[(c.indexOf(prebuffer).coerceAtLeast(0) + 1) % c.size]
            renderPrebuf()
        }
        root.addView(prebufBtn)

        root.addView(TextView(this).apply {
            text = "Proxy remoto (streaming da un altro telefono sulla WiFi)"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Lascia vuoto per usare questo dispositivo. Per usare un telefono-server, " +
                "inserisci host:porta e codice mostrati lì in 'Condivisione WiFi'."
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        val remoteHostField = EditText(this).apply {
            hint = "host:porta (es. 192.168.1.50:8770)"
            isSingleLine = true
            setText(Settings.getRemoteHost(this@SettingsActivity))
        }
        root.addView(remoteHostField)
        val remoteTokenField = EditText(this).apply {
            hint = "codice (token)"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(Settings.getRemoteToken(this@SettingsActivity))
        }
        root.addView(remoteTokenField)

        root.addView(TextView(this).apply {
            text = "Credenziali Telegram (avanzato)"
            textSize = 16f
            setPadding(0, 40, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Le tue api_id/api_hash da my.telegram.org. Lascia vuoto per usare " +
                "quelle predefinite dell'app. Dopo la modifica, riavvia l'app."
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
            text = "Salva"
            isFocusable = true
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                // Credentials override: both empty → clear (use the app default);
                // both valid → store the user's own; anything else → warn and stop.
                val idStr = apiIdField.text.toString().trim()
                val hashStr = apiHashField.text.toString().trim()
                if (idStr.isEmpty() && hashStr.isEmpty()) {
                    Settings.setApiCredentials(this@SettingsActivity, 0, "")
                } else {
                    val id = idStr.toIntOrNull()
                    if (id == null || id <= 0 || hashStr.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "api_id/api_hash non validi", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    Settings.setApiCredentials(this@SettingsActivity, id, hashStr)
                }
                val n = connField.text.toString().trim().toIntOrNull() ?: 3
                Settings.setConnections(this@SettingsActivity, n)
                Settings.setPlayerEngine(this@SettingsActivity, engine)
                Settings.setBufferMode(this@SettingsActivity, buffer)
                Settings.setPreBufferSeconds(this@SettingsActivity, prebuffer)
                Settings.setRemoteHost(this@SettingsActivity, remoteHostField.text.toString())
                Settings.setRemoteToken(this@SettingsActivity, remoteTokenField.text.toString())
                Toast.makeText(this@SettingsActivity, "Salvato", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(TextView(this).apply { setPadding(0, 24, 0, 0) }) // spacer
        root.addView(save)

        // Wrap in a ScrollView: the content is taller than the screen on smaller
        // devices and the Salva button fell off-screen. isFillViewport keeps the
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
}
