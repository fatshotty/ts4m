package net.fatshotty.tgstream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.tg.Tg
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * "Server mode": lets this phone serve its Telegram proxy to other devices on the
 * same WiFi (the box runs as a thin client). The phone does the heavy work
 * (download + MTProto decrypt); the box only decodes. Access is gated by a token.
 */
class ServerActivity : AppCompatActivity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }
        root.addView(TextView(this).apply { text = "Condivisione WiFi (server)"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Questo telefono scarica e decifra da Telegram; un altro dispositivo " +
                "sulla stessa WiFi (il box) riproduce. Inserisci i dati qui sotto nel box, " +
                "in Impostazioni → Proxy remoto."
            textSize = 13f; setPadding(0, 12, 0, 20)
        })

        var enabled = Settings.getServerEnabled(this)
        val toggle = Button(this).apply { isFocusable = true }
        fun renderToggle() { toggle.text = if (enabled) "Server: ATTIVO — tocca per spegnere" else "Server: spento — tocca per attivare" }
        renderToggle()
        toggle.setOnClickListener {
            enabled = !enabled
            Settings.setServerEnabled(this@ServerActivity, enabled)
            applyServer(enabled)
            renderToggle()
            refresh()
        }
        root.addView(toggle)

        info = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setPadding(0, 28, 0, 12)
        }
        root.addView(info)

        val copyBtn = Button(this).apply {
            text = "Copia dati"
            isFocusable = true
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tgstream server", info.text))
                Toast.makeText(this@ServerActivity, "Copiato", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyBtn)

        setContentView(root)
        root.applySystemBarInsets()
        toggle.requestFocus()

        // Make sure the engine/proxy is up so we can show the port.
        Tg.start(this)
        applyServer(enabled)
        refresh()
    }

    private fun applyServer(enabled: Boolean) {
        Tg.setLanToken(if (enabled) Settings.getLanToken(this) else "")
    }

    private fun refresh() {
        if (!Settings.getServerEnabled(this)) {
            info.text = "Server spento."
            return
        }
        val ip = lanIp() ?: "?.?.?.?"
        val port = Tg.proxyPort
        val token = Settings.getLanToken(this)
        info.text = buildString {
            append("Indirizzo (host:porta):\n")
            append(if (port > 0) "$ip:$port" else "$ip:(avvio…)")
            append("\n\nCodice (token):\n")
            append(token)
        }
        // The port may not be ready on the first paint; poll briefly.
        if (port <= 0) info.postDelayed({ refresh() }, 500)
    }

    /** First site-local IPv4 of a non-loopback interface (the WiFi address). */
    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
