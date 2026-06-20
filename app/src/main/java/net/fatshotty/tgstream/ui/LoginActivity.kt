package net.fatshotty.tgstream.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.tg.Tg

/**
 * Drives first-time setup (api_id/api_hash) and the Telegram login flow
 * (phone -> code -> optional 2FA), wired to the Go engine via [Tg].
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }
        info = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 24) }
        setContentView(root)
        root.applySystemBarInsets()

        if (!Settings.isConfigured(this)) {
            showCredentialsForm()
        } else {
            Tg.start(this)
            observe()
        }
    }

    private fun clear() {
        root.removeAllViews()
        root.addView(info)
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            isSingleLine = true
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun showCredentialsForm() {
        clear()
        info.text = "Inserisci le tue credenziali Telegram (my.telegram.org)."
        val idField = field("api_id", InputType.TYPE_CLASS_NUMBER)
        val hashField = field("api_hash")
        val save = button("Salva e continua") {
            val id = idField.text.toString().trim().toIntOrNull()
            val hash = hashField.text.toString().trim()
            if (id == null || id <= 0 || hash.isBlank()) {
                toast("api_id/api_hash non validi")
                return@button
            }
            Settings.setApiCredentials(this, id, hash)
            Tg.start(this)
            observe()
        }
        root.addView(idField)
        root.addView(hashField)
        root.addView(save)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { render(it) }
            }
        }
    }

    private fun render(s: Tg.State) {
        when (s.phase) {
            Tg.Phase.NEED_PHONE -> promptSingle(
                "Numero di telefono (+39…)", InputType.TYPE_CLASS_PHONE
            ) { Tg.submitPhone(it) }
            Tg.Phase.NEED_CODE -> promptSingle(
                "Codice ricevuto su Telegram", InputType.TYPE_CLASS_NUMBER
            ) { Tg.submitCode(it) }
            Tg.Phase.NEED_PASSWORD -> promptSingle(
                "Password 2FA", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ) { Tg.submitPassword(it) }
            Tg.Phase.AUTHORIZED, Tg.Phase.READY -> {
                toast("Login completato")
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            Tg.Phase.CONNECTING -> { clear(); info.text = "Connessione…" }
            Tg.Phase.ERROR -> { clear(); info.text = "Errore: ${s.message}" }
            else -> {}
        }
    }

    private var lastPrompt: Tg.Phase? = null
    private fun promptSingle(hint: String, type: Int, submit: (String) -> Unit) {
        // avoid rebuilding the form repeatedly for the same phase
        clear()
        info.text = hint
        val f = field(hint, type)
        val b = button("Invia") {
            val v = f.text.toString().trim()
            if (v.isNotBlank()) {
                submit(v)
                clear(); info.text = "Verifica in corso…"
            }
        }
        root.addView(f)
        root.addView(b)
        f.requestFocus()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
