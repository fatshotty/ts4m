package net.fatshotty.tgstream.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.provider.ProviderRegistry
import net.fatshotty.tgstream.tg.Tg
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure download speed test. Resolves an Emby URL to channel+messages, then drains
 * the localhost proxy /stream AS FAST AS POSSIBLE (no player, no playback buffer)
 * and reports live/avg/peak MB/s at the configured connection count. This isolates
 * the engine's real download throughput from libVLC's consumption/backpressure.
 */
class SpeedTestActivity : AppCompatActivity() {

    private val running = AtomicBoolean(false)
    @Volatile private var call: Call? = null

    private lateinit var urlField: EditText
    private lateinit var startBtn: Button
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply { text = "Test velocità download"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Scarica dal proxy a massima velocità (senza player) e misura i MB/s reali."
            textSize = 13f; setPadding(0, 12, 0, 12)
        })
        urlField = EditText(this).apply {
            hint = "URL Emby (es. http://server/emby/videos/8/stream.mkv?api_key=...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Settings.getLastUrl(this@SpeedTestActivity))
            textSize = 12f
        }
        root.addView(urlField)
        root.addView(TextView(this).apply {
            text = "Connessioni: ${Settings.getConnections(this@SpeedTestActivity)} (modificale in Impostazioni)"
            textSize = 13f; setPadding(0, 12, 0, 12)
        })
        startBtn = Button(this).apply {
            text = "Avvia test"
            isFocusable = true
            setOnClickListener { if (running.get()) stop() else start() }
        }
        root.addView(startBtn)
        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 15f; setPadding(0, 24, 0, 0); text = "—"
        }
        root.addView(out)

        setContentView(root)
        root.applySystemBarInsets()
        startBtn.requestFocus()
    }

    private fun start() {
        val urlStr = urlField.text.toString().trim()
        if (urlStr.isBlank()) { out.text = "Inserisci un URL Emby"; return }
        running.set(true)
        startBtn.text = "Stop"
        Tg.start(this)
        lifecycleScope.launch { runTest(urlStr) }
    }

    private fun stop() {
        running.set(false)
        call?.cancel()
        startBtn.text = "Avvia test"
    }

    private suspend fun runTest(urlStr: String) {
        try {
            setOut("Attendo l'engine Telegram…")
            if (!Tg.awaitReady()) { setOut("Telegram non pronto/loggato"); return }
            val conns = Settings.getConnections(this)
            val (channel, messages) = withContext(Dispatchers.IO) {
                val uri = Uri.parse(urlStr)
                val provider = ProviderRegistry.candidates(uri).firstOrNull()
                    ?: ProviderRegistry.single
                    ?: throw IllegalStateException("nessun provider")
                val r = provider.resolve(uri)
                r.stream.channelId to r.stream.messagesDash
            }
            val proxyUrl = Tg.streamUrl(channel, messages, conns)
            setOut("Risolto. Scarico… (conns=$conns)")
            withContext(Dispatchers.IO) { drain(proxyUrl, conns) }
        } catch (t: Throwable) {
            setOut("Errore: ${t.message}")
        } finally {
            running.set(false)
            runOnUiThread { startBtn.text = "Avvia test" }
        }
    }

    private fun drain(proxyUrl: String, conns: Int) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        // Cap at 2 GiB so we never try to pull a whole multi-GB file.
        val cap = 2L * 1024 * 1024 * 1024
        val req = Request.Builder().url(proxyUrl)
            .header("Range", "bytes=0-${cap - 1}")
            .build()
        val c = client.newCall(req)
        call = c
        c.execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                setOut("HTTP ${resp.code} dal proxy"); return
            }
            val ins = resp.body?.byteStream() ?: run { setOut("nessun corpo risposta"); return }
            val buf = ByteArray(256 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            var lastT = t0
            var lastTotal = 0L
            var peak = 0.0
            while (running.get()) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                val now = System.nanoTime()
                if (now - lastT >= 500_000_000) {
                    val live = (total - lastTotal) / 1e6 / ((now - lastT) / 1e9)
                    if (live > peak) peak = live
                    setOut(report(conns, live, total, t0, now, peak))
                    lastT = now; lastTotal = total
                }
                if (total >= cap) break
            }
            val now = System.nanoTime()
            setOut(report(conns, 0.0, total, t0, now, peak) + "\n\n--- FINE ---")
        }
    }

    private fun report(conns: Int, liveMBs: Double, total: Long, t0: Long, now: Long, peak: Double): String {
        val el = (now - t0) / 1e9
        val avg = if (el > 0) total / 1e6 / el else 0.0
        val sb = StringBuilder()
        sb.append("Connessioni: ").append(conns).append('\n')
        sb.append("Scaricato:   ").append(String.format("%.0f MB", total / 1e6))
            .append(String.format(" in %.1fs", el)).append('\n')
        sb.append("Media:       ").append(String.format("%.1f MB/s (%.0f Mbps)", avg, avg * 8)).append('\n')
        if (liveMBs > 0) sb.append("Live:        ").append(String.format("%.1f MB/s", liveMBs)).append('\n')
        sb.append("Picco:       ").append(String.format("%.1f MB/s (%.0f Mbps)", peak, peak * 8))
        return sb.toString()
    }

    private fun setOut(s: String) = runOnUiThread { out.text = s }

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        call?.cancel()
    }
}
