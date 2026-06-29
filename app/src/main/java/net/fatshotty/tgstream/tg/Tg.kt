package net.fatshotty.tgstream.tg

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fatshotty.tgstream.data.Settings
import net.fatshotty.tgstream.util.AppLog
import tgengine.Engine
import tgengine.LoginListener
import tgengine.Logger
import tgengine.Tgengine
import java.io.File

/**
 * Process-wide holder for the Go Telegram engine. The engine keeps a live
 * MTProto connection and runs the localhost HTTP proxy that ExoPlayer streams
 * from. Control calls go through here; bytes never cross JNI.
 */
object Tg {

    enum class Phase { IDLE, CONNECTING, NEED_PHONE, NEED_CODE, NEED_PASSWORD, AUTHORIZED, READY, ERROR }

    data class State(val phase: Phase = Phase.IDLE, val message: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile var proxyPort: Int = -1; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    @Volatile
    private var startRequested = false

    val isReady: Boolean get() = engine?.isReady == true && proxyPort > 0

    val decryptHardwareStatus: String
        get() = runCatching { Tgengine.decryptHardwareStatus() }.getOrDefault("unknown")

    private val logSink = Logger { level, msg ->
        when (level) {
            "E" -> AppLog.e("go", msg)
            "W" -> AppLog.w("go", msg)
            else -> AppLog.i("go", msg)
        }
    }

    private val listener = LoginListener { state, message ->
        // Called from a Go goroutine thread.
        val phase = when (state) {
            Tgengine.StateConnecting -> Phase.CONNECTING
            Tgengine.StateNeedPhone -> Phase.NEED_PHONE
            Tgengine.StateNeedCode -> Phase.NEED_CODE
            Tgengine.StateNeedPassword -> Phase.NEED_PASSWORD
            Tgengine.StateAuthorized -> Phase.AUTHORIZED
            Tgengine.StateError -> Phase.ERROR
            Tgengine.StateLoggedOut -> Phase.IDLE
            else -> Phase.IDLE
        }
        _state.value = State(phase, message ?: "")
        if (phase == Phase.ERROR) startRequested = false
        if (phase == Phase.AUTHORIZED) ensureProxy()
    }

    /** Initializes the engine from saved settings. Safe to call repeatedly. */
    @Synchronized
    fun init(ctx: Context): Boolean {
        Tgengine.setLogger(logSink)
        if (engine != null) return true
        if (!Settings.isConfigured(ctx)) return false
        val sessionFile = File(ctx.applicationContext.filesDir, "tg.session").absolutePath
        val e = Tgengine.newEngine(
            Settings.getApiId(ctx).toLong(),
            Settings.getApiHash(ctx),
            sessionFile,
        )
        e.setLoginListener(listener)
        engine = e
        return true
    }

    /** Starts the engine (connect + authorize). Safe to call repeatedly. */
    @Synchronized
    fun start(ctx: Context) {
        if (!init(ctx)) return
        if (startRequested) return
        startRequested = true
        scope.launch {
            try {
                engine?.start()
            } catch (t: Throwable) {
                startRequested = false
                _state.value = State(Phase.ERROR, t.message ?: "start failed")
            }
        }
    }

    private fun ensureProxy() {
        scope.launch {
            try {
                if (proxyPort <= 0) {
                    proxyPort = engine?.startProxy()?.toInt() ?: -1
                }
                if (proxyPort > 0) _state.value = State(Phase.READY, "")
            } catch (t: Throwable) {
                _state.value = State(Phase.ERROR, t.message ?: "proxy failed")
            }
        }
    }

    fun submitPhone(v: String) = scope.launch { runCatching { engine?.submitPhone(v) } }
    fun submitCode(v: String) = scope.launch { runCatching { engine?.submitCode(v) } }
    fun submitPassword(v: String) = scope.launch { runCatching { engine?.submitPassword(v) } }

    /** Builds the localhost proxy URL for a stream. */
    fun streamUrl(channelId: Long, messagesDash: String, conns: Int): String =
        "http://127.0.0.1:$proxyPort/stream?channel=$channelId&messages=$messagesDash&conns=$conns"

    /**
     * Uploads [text] as a .txt to the user's Telegram Saved Messages. Runs off the
     * main thread; [onResult] is posted back on the main thread with success and an
     * optional error message.
     */
    fun sendLog(name: String, text: String, onResult: (Boolean, String?) -> Unit) {
        val e = engine
        if (e == null) {
            onResult(false, "engine not initialized")
            return
        }
        scope.launch {
            val res = runCatching { e.sendLog(name, text) }
            withContext(Dispatchers.Main) { onResult(res.isSuccess, res.exceptionOrNull()?.message) }
        }
    }

    /** Suspends until the engine is authorized and the proxy is up, or timeout. */
    suspend fun awaitReady(timeoutMs: Long = 60_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isReady) return@withContext true
            Thread.sleep(200)
        }
        isReady
    }
}
