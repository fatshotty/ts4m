package org.tgstream.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tgstream.app.data.Settings
import org.tgstream.app.tg.LoginNavigation
import org.tgstream.app.tg.QrCodeData
import org.tgstream.app.tg.Tg

/** Telegram account management and QR/phone login, backed by the Go engine. */
class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACCOUNT_MANAGEMENT = "account_management"
    }

    private lateinit var root: LinearLayout
    private lateinit var info: TextView
    private var management = false
    private var observing = false
    private var completed = false
    private var lastPhase: Tg.Phase? = null
    private var lastPromptRevision = -1L
    private var inputField: EditText? = null
    private var submitButton: Button? = null
    private var decodeJob: Job? = null
    private var expiryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        management = savedInstanceState?.getBoolean(EXTRA_ACCOUNT_MANAGEMENT)
            ?: intent.getBooleanExtra(EXTRA_ACCOUNT_MANAGEMENT, false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        info = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(16))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        scroll.applySystemBarInsets()
        onBackPressedDispatcher.addCallback(this) {
            if (Tg.state.value.phase == Tg.Phase.LOGGING_OUT) return@addCallback
            if (LoginNavigation.shouldCancelOnExit(Tg.state.value.phase)) Tg.cancelLogin()
            finish()
        }
        if (!Settings.isConfigured(this)) showCredentialsForm()
        else { Tg.start(this); observe() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_ACCOUNT_MANAGEMENT, management)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun clear() {
        decodeJob?.cancel()
        expiryJob?.cancel()
        inputField = null
        submitButton = null
        root.removeAllViews()
        root.addView(info)
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { this.hint = hint; inputType = type; isSingleLine = true }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = true
        setOnClickListener { onClick() }
        root.addView(this, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun showCredentialsForm() {
        clear()
        info.text = "Enter your Telegram credentials (my.telegram.org)."
        val idField = field("api_id", InputType.TYPE_CLASS_NUMBER)
        val hashField = field("api_hash")
        root.addView(idField)
        root.addView(hashField)
        button("Save and continue") {
            val id = idField.text.toString().trim().toIntOrNull()
            val hash = hashField.text.toString().trim()
            if (id == null || id <= 0 || hash.isBlank()) {
                Toast.makeText(this, "Invalid api_id/api_hash", Toast.LENGTH_LONG).show()
            } else {
                Settings.setApiCredentials(this, id, hash)
                Tg.start(this)
                observe()
            }
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { Tg.state.collect { render(it) } }
        }
    }

    private fun render(s: Tg.State) {
        if (completed) return
        if (LoginNavigation.shouldFinish(management, s.phase)) {
            completed = true
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }
        val inputPhase = s.phase in setOf(Tg.Phase.NEED_PHONE, Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD)
        if (inputPhase && lastPhase == s.phase && lastPromptRevision == s.revision) {
            renderPendingInput(s)
            return
        }
        lastPhase = s.phase
        lastPromptRevision = s.revision
        when (s.phase) {
            Tg.Phase.NEED_LOGIN_METHOD, Tg.Phase.LOGGED_OUT -> showLoginMethods()
            Tg.Phase.WAIT_QR -> showQrCode(s.qrCode)
            Tg.Phase.NEED_PHONE -> promptSingle("Phone number (+country code)", InputType.TYPE_CLASS_PHONE, s) { Tg.submitPhone(it) }
            Tg.Phase.NEED_CODE -> promptSingle("Code received in Telegram", InputType.TYPE_CLASS_NUMBER, s) { Tg.submitCode(it) }
            Tg.Phase.NEED_PASSWORD -> promptSingle(
                "2FA password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                s, password = true,
            ) { Tg.submitPassword(it) }
            Tg.Phase.AUTHORIZED, Tg.Phase.READY -> showConnectedAccount(s)
            Tg.Phase.LOGGING_OUT -> { clear(); info.text = "Logging out of Telegram..." }
            Tg.Phase.LOGOUT_CLEANUP -> {
                clear()
                info.text = s.message
                button("Retry logout") { Tg.logout() }
            }
            Tg.Phase.ERROR -> {
                clear()
                info.text = s.message
                button("Retry") { Tg.retry(applicationContext) }
            }
            else -> { clear(); info.text = "Connecting to Telegram..." }
        }
    }

    private fun showLoginMethods() {
        clear()
        info.text = "Log in to Telegram"
        button("Log in with QR code") { Tg.selectLoginMethod("qr") }.requestFocus()
        button("Log in with phone number") { Tg.selectLoginMethod("phone") }
    }

    private fun showConnectedAccount(s: Tg.State) {
        clear()
        info.text = if (s.message.isNotBlank()) "Connected to Telegram.\n${s.message}"
            else if (s.phase == Tg.Phase.READY) "Connected to Telegram"
            else "Connected to Telegram. Starting player proxy..."
        button("Log out") { Tg.logout() }.requestFocus()
    }

    private fun showQrCode(data: QrCodeData?) {
        clear()
        info.text = "Open Telegram on another device, go to Settings > Devices > Link Desktop Device, and scan this code."
        val image = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Telegram login QR code"
        }
        val size = minOf(dp(320), resources.displayMetrics.widthPixels - dp(48),
            resources.displayMetrics.heightPixels / 2).coerceAtLeast(dp(96))
        root.addView(image, LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(16) })
        button("Use phone number instead") { Tg.selectLoginMethod("phone") }.requestFocus()
        button("Choose login method") { Tg.cancelLogin() }
        if (data == null || data.expiresAtUnix * 1000 <= System.currentTimeMillis()) {
            info.text = "Generating a new QR code..."
            return
        }
        decodeJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val original = BitmapFactory.decodeByteArray(data.pngData, 0, data.pngData.size)
                    ?: return@withContext null
                // The Go PNG uses eight pixels per module, including its quiet zone.
                val modules = (original.width / 8).coerceAtLeast(1)
                val pixels = modules * (size / modules).coerceAtLeast(1)
                Bitmap.createScaledBitmap(original, pixels, pixels, false)
            }
            if (Tg.state.value.phase == Tg.Phase.WAIT_QR && Tg.state.value.qrCode === data &&
                data.expiresAtUnix * 1000 > System.currentTimeMillis()) {
                image.setImageDrawable(bitmap?.let { BitmapDrawable(resources, it).apply { paint.isFilterBitmap = false } })
                if (bitmap == null) info.text = "Unable to display QR code. Choose a login method to retry."
            }
        }
        expiryJob = lifecycleScope.launch {
            delay((data.expiresAtUnix * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
            image.setImageDrawable(null)
            info.text = "Refreshing QR code..."
        }
    }

    private fun renderPendingInput(s: Tg.State) {
        inputField?.isEnabled = !s.inputPending
        submitButton?.isEnabled = !s.inputPending
        if (s.inputPending) info.text = "Verifying..."
    }

    private fun promptSingle(hint: String, type: Int, state: Tg.State, password: Boolean = false, submit: (String) -> Unit) {
        clear()
        info.text = if (state.message.isBlank()) hint else "$hint\n${state.message}"
        val input = field(hint, type)
        root.addView(input)
        lateinit var send: Button
        send = button("Send") {
            val raw = input.text.toString()
            val value = if (password) raw else raw.trim()
            if (value.isNotEmpty() && !Tg.state.value.inputPending) {
                input.isEnabled = false
                send.isEnabled = false
                info.text = "Verifying..."
                submit(value)
            }
        }
        inputField = input
        submitButton = send
        renderPendingInput(state)
        button("Choose login method") { Tg.cancelLogin() }
        input.requestFocus()
    }
}
