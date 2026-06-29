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
            text = "tgstream  v${BuildConfig.VERSION_NAME}"
            textSize = 28f
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }
        val settingsBtn = Button(this).apply {
            text = "Settings"
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
            text = "Speed test"
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SpeedTestActivity::class.java))
            }
        }
        updateBtn = Button(this).apply {
            text = "Update app"
            isFocusable = true
            setOnClickListener { doUpdate() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(logBtn)
        root.addView(speedBtn)
        root.addView(updateBtn)
        setContentView(root)
        root.applySystemBarInsets()
        settingsBtn.requestFocus()

        Tg.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { render(it) }
            }
        }

        // Silent update check on launch; surfaces a hint if a newer build exists.
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.checkForUpdate() }
            if (rel != null) {
                updateBtn.text = "Update to v${rel.versionName} >"
                Toast.makeText(
                    this@MainActivity, "Update available: v${rel.versionName}", Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun doUpdate() {
        updateBtn.isEnabled = false
        updateBtn.text = "Checking for updates..."
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.checkForUpdate() }
            if (rel == null) {
                updateBtn.text = "Up to date (v${BuildConfig.VERSION_NAME})"
                updateBtn.isEnabled = true
                return@launch
            }
            updateBtn.text = "Downloading v${rel.versionName}... 0%"
            val apk = withContext(Dispatchers.IO) {
                Updater.download(this@MainActivity) { p ->
                    runOnUiThread { updateBtn.text = "Downloading v${rel.versionName}... $p%" }
                }
            }
            if (apk == null) {
                updateBtn.text = "Download failed - try again"
                updateBtn.isEnabled = true
                return@launch
            }
            val launched = Updater.install(this@MainActivity, apk)
            if (!launched) {
                updateBtn.text = "Enable 'Install unknown apps' and try again"
                updateBtn.isEnabled = true
            } else {
                updateBtn.text = "Installation started..."
            }
        }
    }

    private fun render(s: Tg.State) {
        status.text = when (s.phase) {
            Tg.Phase.READY -> "Ready. Use me as an external player from Emby."
            Tg.Phase.AUTHORIZED -> "Authorized, starting proxy..."
            Tg.Phase.CONNECTING -> "Connecting to Telegram..."
            Tg.Phase.NEED_PHONE, Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD -> {
                startActivity(Intent(this, LoginActivity::class.java))
                "Login required..."
            }
            Tg.Phase.ERROR -> "Error: ${s.message}"
            else -> "..."
        }
    }
}
