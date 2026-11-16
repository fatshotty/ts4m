package org.tgstream.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tgstream.app.BuildConfig
import org.tgstream.app.data.PlaybackHistoryItem
import org.tgstream.app.data.PlaybackHistoryStore
import org.tgstream.app.data.Settings
import org.tgstream.app.provider.DirectLinkCodec
import org.tgstream.app.provider.StreamSpec
import org.tgstream.app.tg.Tg
import org.tgstream.app.tg.LoginNavigation
import org.tgstream.app.update.Updater

/**
 * Launcher / idle screen. Once Telegram is configured the app just sits here,
 * ready to be invoked as an external player. No real navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var updateBtn: Button
    private lateinit var recentList: LinearLayout
    private var loginOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.isConfigured(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
        val manualPlayBtn = Button(this).apply {
            text = "Manual play"
            isFocusable = true
            setOnClickListener { showManualPlayDialog() }
        }
        updateBtn = Button(this).apply {
            text = "Update app"
            isFocusable = true
            setOnClickListener { doUpdate() }
        }
        val recentTitle = TextView(this).apply {
            text = "Recent"
            textSize = 20f
            setPadding(0, 32, 0, 8)
        }
        recentList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(logBtn)
        root.addView(speedBtn)
        root.addView(manualPlayBtn)
        root.addView(updateBtn)
        root.addView(recentTitle)
        root.addView(recentList)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.applySystemBarInsets()
        settingsBtn.requestFocus()
        renderRecent()

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

    override fun onResume() {
        super.onResume()
        loginOpened = false
        if (::recentList.isInitialized) {
            renderRecent()
        }
    }

    private fun renderRecent() {
        recentList.removeAllViews()
        val items = PlaybackHistoryStore.recent(this)
        if (items.isEmpty()) {
            recentList.addView(TextView(this).apply {
                text = "No recent streams"
                textSize = 14f
            })
            return
        }
        items.forEach { item ->
            recentList.addView(Button(this).apply {
                text = historyLabel(item)
                isFocusable = true
                isAllCaps = false
                setOnClickListener { launchHistoryItem(item) }
            })
        }
    }

    private fun historyLabel(item: PlaybackHistoryItem): String {
        val title = item.title.ifBlank { "${item.channelId} ; ${item.messagesDash}" }
        return if (item.source.isBlank()) title else "$title\n${item.source}"
    }

    private fun launchHistoryItem(item: PlaybackHistoryItem) {
        val uri = DirectLinkCodec.build(
            StreamSpec(
                channelId = item.channelId,
                messagesDash = item.messagesDash,
                title = item.title,
            ),
        )
        startActivity(
            Intent(this, PlayerEntryActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(uri)
            },
        )
    }

    private fun showManualPlayDialog() {
        val channelField = manualField("channel_id")
        val messagesField = manualField("message_ids (10-11 or 10,11)")
        val titleField = manualField("title (optional)")
        val positionField = manualField("position seconds (optional)")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(channelField)
            addView(messagesField)
            addView(titleField)
            addView(positionField)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this)
            .setTitle("Manual play")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Play", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val uri = try {
                            ManualPlayRequest.toDirectLink(
                                channelText = channelField.text.toString(),
                                messagesText = messagesField.text.toString(),
                                titleText = titleField.text.toString(),
                                positionSecondsText = positionField.text.toString(),
                            )
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, e.message ?: "Invalid manual input", Toast.LENGTH_LONG)
                                .show()
                            return@setOnClickListener
                        }
                        dismiss()
                        startActivity(
                            Intent(this@MainActivity, PlayerEntryActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                data = Uri.parse(uri)
                                putExtra(PlayerEntryActivity.EXTRA_HISTORY_SOURCE, "manual")
                            },
                        )
                    }
                }
                show()
            }
    }

    private fun manualField(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            isSingleLine = true
            isFocusable = true
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
            Tg.Phase.READY -> "Ready. Use me as an external player or start Manual play."
            Tg.Phase.AUTHORIZED -> "Authorized, starting proxy..."
            Tg.Phase.CONNECTING -> "Connecting to Telegram..."
            Tg.Phase.NEED_LOGIN_METHOD, Tg.Phase.WAIT_QR,
            Tg.Phase.NEED_PHONE, Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD -> {
                if (!loginOpened && Tg.automaticLoginAllowed && LoginNavigation.needsLogin(s.phase)) {
                    loginOpened = true
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                "Login required..."
            }
            Tg.Phase.LOGGED_OUT -> "Disconnected. Open Settings > Telegram account to log in."
            Tg.Phase.LOGGING_OUT -> "Logging out of Telegram..."
            Tg.Phase.LOGOUT_CLEANUP -> "Logout cleanup required. Open Settings > Telegram account."
            Tg.Phase.ERROR -> "Error: ${s.message}"
            else -> "..."
        }
    }
}
