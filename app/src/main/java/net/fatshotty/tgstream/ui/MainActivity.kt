package net.fatshotty.tgstream.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fatshotty.tgstream.BuildConfig
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.tg.Tg
import net.fatshotty.tgstream.update.Updater

/**
 * Launcher / idle screen. Once Telegram is configured the app just sits here,
 * ready to be invoked as an external player. No real navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var updateBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A pure client box (remote proxy set) needs no Telegram login of its own.
        if (!Settings.isConfigured(this) && !Settings.isRemoteClient(this)) {
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
            text = "tgstream  v${BuildConfig.VERSION_NAME}"
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
        val serverBtn = Button(this).apply {
            text = "Condivisione WiFi (server)"
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ServerActivity::class.java))
            }
        }
        updateBtn = Button(this).apply {
            text = "Aggiorna app"
            isFocusable = true
            setOnClickListener { doUpdate() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(logBtn)
        root.addView(speedBtn)
        root.addView(serverBtn)
        root.addView(updateBtn)
        setContentView(root)
        root.applySystemBarInsets()
        settingsBtn.requestFocus()

        if (Settings.isRemoteClient(this)) {
            status.text = "Modalità client: streaming dal telefono ${Settings.getRemoteHost(this)}."
        } else {
            Tg.start(this)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    Tg.state.collect { render(it) }
                }
            }
        }

        // Silent update check on launch; surfaces a hint if a newer build exists.
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.checkForUpdate() }
            if (rel != null) {
                updateBtn.text = "Aggiorna a v${rel.versionName} ▸"
                Toast.makeText(
                    this@MainActivity, "Aggiornamento disponibile: v${rel.versionName}", Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun doUpdate() {
        updateBtn.isEnabled = false
        updateBtn.text = "Controllo aggiornamenti…"
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.checkForUpdate() }
            if (rel == null) {
                updateBtn.text = "Sei aggiornato (v${BuildConfig.VERSION_NAME})"
                updateBtn.isEnabled = true
                return@launch
            }
            updateBtn.text = "Scarico v${rel.versionName}… 0%"
            val apk = withContext(Dispatchers.IO) {
                Updater.download(this@MainActivity) { p ->
                    runOnUiThread { updateBtn.text = "Scarico v${rel.versionName}… $p%" }
                }
            }
            if (apk == null) {
                updateBtn.text = "Download fallito — riprova"
                updateBtn.isEnabled = true
                return@launch
            }
            val launched = Updater.install(this@MainActivity, apk)
            if (!launched) {
                updateBtn.text = "Abilita 'Installa app sconosciute' e riprova"
                updateBtn.isEnabled = true
            } else {
                updateBtn.text = "Installazione avviata…"
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
