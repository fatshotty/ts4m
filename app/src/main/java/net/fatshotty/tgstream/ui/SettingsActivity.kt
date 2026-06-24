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
            text = "Decodifica video (solo VLC)"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Forces software decoding on libVLC. Use to test Dolby Vision / HDR 10-bit
        // content where the HW decoder shows a green screen. Heavier on CPU.
        var forceSw = Settings.getForceSwDecode(this)
        val swBtn = Button(this).apply { isFocusable = true }
        fun renderSw() {
            swBtn.text = if (forceSw)
                "Decodifica SW Dolby Vision: ON — tocca per HW"
            else
                "Decodifica SW Dolby Vision: OFF (HW) — tocca per SW"
        }
        renderSw()
        swBtn.setOnClickListener { forceSw = !forceSw; renderSw() }
        root.addView(swBtn)

        root.addView(TextView(this).apply {
            text = "Cache su disco (streaming ad alto bitrate)"
            textSize = 16f
            setPadding(0, 40, 0, 8)
        })
        // Optional disk write-ahead cache in the Go proxy: a small circular file
        // (50–250 MB, sized by bitrate, deleted on stop) filled ahead of playback
        // to absorb jitter on heavy ~80 Mb/s streams, off the Java heap.
        var diskCache = Settings.getDiskCache(this)
        val cacheBtn = Button(this).apply { isFocusable = true }
        fun renderCache() {
            cacheBtn.text = if (diskCache)
                "Cache su disco: ON — tocca per disattivare"
            else
                "Cache su disco: OFF — tocca per attivare"
        }
        renderCache()
        cacheBtn.setOnClickListener { diskCache = !diskCache; renderCache() }
        root.addView(cacheBtn)

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

        val save = Button(this).apply {
            text = "Salva"
            isFocusable = true
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                val n = connField.text.toString().trim().toIntOrNull() ?: 3
                Settings.setConnections(this@SettingsActivity, n)
                Settings.setPlayerEngine(this@SettingsActivity, engine)
                Settings.setBufferMode(this@SettingsActivity, buffer)
                Settings.setForceSwDecode(this@SettingsActivity, forceSw)
                Settings.setDiskCache(this@SettingsActivity, diskCache)
                Settings.setPreBufferSeconds(this@SettingsActivity, prebuffer)
                Settings.setRemoteHost(this@SettingsActivity, remoteHostField.text.toString())
                Settings.setRemoteToken(this@SettingsActivity, remoteTokenField.text.toString())
                Toast.makeText(this@SettingsActivity, "Salvato", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(TextView(this).apply { setPadding(0, 24, 0, 0) }) // spacer
        root.addView(save)

        // Wrap in a ScrollView: with the disk-cache row added, the content is
        // taller than the screen on smaller devices and the Salva button fell
        // off-screen. isFillViewport keeps the layout filling short screens.
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
