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

/** Minimal settings: number of simultaneous connections per stream. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }
        val label = TextView(this).apply {
            text = "Connessioni simultanee per stream (1-16)"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        val connField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(Settings.getConnections(this@SettingsActivity).toString())
        }
        val save = Button(this).apply {
            text = "Salva"
            isFocusable = true
            setOnClickListener {
                val n = connField.text.toString().trim().toIntOrNull() ?: 3
                Settings.setConnections(this@SettingsActivity, n)
                Toast.makeText(this@SettingsActivity, "Salvato", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(label)
        root.addView(connField)
        root.addView(save)
        setContentView(root)
        root.applySystemBarInsets()
        save.requestFocus()
    }
}
