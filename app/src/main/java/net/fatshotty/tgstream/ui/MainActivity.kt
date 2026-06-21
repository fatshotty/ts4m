package net.fatshotty.tgstream.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.tg.Tg

/**
 * Launcher / idle screen. Once Telegram is configured the app just sits here,
 * ready to be invoked as an external player. No real navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.isConfigured(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        val title = TextView(this).apply {
            text = "tgstream"
            textSize = 28f
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }
        val settingsBtn = Button(this).apply {
            text = "Impostazioni"
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val logBtn = Button(this).apply {
            text = "Log"
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LogActivity::class.java))
            }
        }
        val speedBtn = Button(this).apply {
            text = "Test velocità"
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SpeedTestActivity::class.java))
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(logBtn)
        root.addView(speedBtn)
        setContentView(root)
        root.applySystemBarInsets()
        settingsBtn.requestFocus()

        Tg.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { render(it) }
            }
        }
    }

    private fun render(s: Tg.State) {
        status.text = when (s.phase) {
            Tg.Phase.READY -> "Pronto. Usami come player esterno da Emby."
            Tg.Phase.AUTHORIZED -> "Autorizzato, avvio proxy…"
            Tg.Phase.CONNECTING -> "Connessione a Telegram…"
            Tg.Phase.NEED_PHONE, Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD -> {
                startActivity(Intent(this, LoginActivity::class.java))
                "Login richiesto…"
            }
            Tg.Phase.ERROR -> "Errore: ${s.message}"
            else -> "…"
        }
    }
}
