package org.tgstream.app.tg

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tgstream.app.data.Settings
import org.tgstream.app.util.AppLog
import tgengine.Engine
import tgengine.LoginListener
import tgengine.Logger
import tgengine.QRLoginListener
import kotlinx.coroutines.sync.withLock
import tgengine.Tgengine
import java.io.File

/**
 * Process-wide holder for the Go Telegram engine. The engine keeps a live
 * MTProto connection and runs the localhost HTTP proxy that ExoPlayer streams
 * from. Control calls go through here; bytes never cross JNI.
 */
object Tg {

    enum class Phase {
        IDLE, CONNECTING, NEED_LOGIN_METHOD, WAIT_QR, NEED_PHONE, NEED_CODE,
        NEED_PASSWORD, AUTHORIZED, READY, LOGGING_OUT, LOGGED_OUT, LOGOUT_CLEANUP, ERROR,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val qrCode: QrCodeData? = null,
        val revision: Long = 0,
        val inputPending: Boolean = false,
    )

    private val lock = Any()
    private val login = LoginState()
    private val operations = kotlinx.coroutines.sync.Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile var proxyPort: Int = -1; private set
    @Volatile var automaticLoginAllowed = true; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var appContext: Context? = null
    private var startRequested = false
    private var pendingMethod: String? = null

    val isReady: Boolean get() = synchronized(lock) {
        login.current.phase == Phase.READY && engine?.isReady == true && proxyPort > 0
    }

    val decryptHardwareStatus: String
        get() = runCatching { Tgengine.decryptHardwareStatus() }.getOrDefault("unknown")

    private val logSink = Logger { level, msg ->
        when (level) {
            "E" -> AppLog.e("go", msg)
            "W" -> AppLog.w("go", msg)
            else -> AppLog.i("go", msg)
        }
    }

    private fun publish() { _state.value = login.current }

    /** Initializes the process engine; listeners are fenced by engine generation. */
    fun init(ctx: Context): Boolean = synchronized(lock) {
        appContext = ctx.applicationContext
        if (engine != null) return@synchronized true
        if (!Settings.isConfigured(ctx)) return@synchronized false
        Tgengine.setLogger(logSink)
        val generation = login.newEngine()
        val e = Tgengine.newEngine(
            Settings.getApiId(ctx).toLong(), Settings.getApiHash(ctx),
            File(ctx.applicationContext.filesDir, "tg.session").absolutePath,
        )
        e.setLoginListener(LoginListener { state, message ->
            val phase = when (state) {
                Tgengine.StateConnecting -> Phase.CONNECTING
                Tgengine.StateNeedLoginMethod -> Phase.NEED_LOGIN_METHOD
                Tgengine.StateWaitQR -> Phase.WAIT_QR
                Tgengine.StateNeedPhone -> Phase.NEED_PHONE
                Tgengine.StateNeedCode -> Phase.NEED_CODE
                Tgengine.StateNeedPassword -> Phase.NEED_PASSWORD
                Tgengine.StateAuthorized -> Phase.AUTHORIZED
                Tgengine.StateLoggingOut -> Phase.LOGGING_OUT
                Tgengine.StateLoggedOut -> Phase.LOGGED_OUT
                Tgengine.StateError -> Phase.ERROR
                else -> Phase.IDLE
            }
            val accepted = synchronized(lock) {
                if (!login.phase(generation, phase, message ?: "")) false
                else { publish(); true }
            }
            if (accepted && phase == Phase.AUTHORIZED) ensureProxy(e, generation)
            if (accepted && phase == Phase.NEED_LOGIN_METHOD) {
                val method = synchronized(lock) { pendingMethod.also { pendingMethod = null } }
                if (method != null) selectLoginMethod(method)
            }
        })
        e.setQRLoginListener(QRLoginListener { data, expiresAt ->
            if (data != null && data.size <= 64 * 1024) synchronized(lock) {
                if (login.qr(generation, QrCodeData(data.copyOf(), expiresAt))) publish()
            }
        })
        engine = e
        publish()
        true
    }

    /** Restores a session once; an explicit logout remains idle until user input. */
    fun start(ctx: Context) = start(ctx, userInitiated = false)

    private fun start(ctx: Context, userInitiated: Boolean) {
        synchronized(lock) {
            appContext = ctx.applicationContext
            if (login.current.phase in setOf(Phase.LOGGING_OUT, Phase.LOGOUT_CLEANUP)) return
            if (!userInitiated && login.current.phase == Phase.LOGGED_OUT) return
            try {
                if (!init(ctx) || startRequested) return
            } catch (_: Throwable) {
                login.phase(login.generation, Phase.ERROR, "Unable to initialize Telegram.")
                publish()
                return
            }
            val e = engine ?: return
            val generation = login.generation
            startRequested = true
            scope.launch {
                operations.withLock {
                    if (!synchronized(lock) { engine === e && login.accepts(generation) }) return@withLock
                    try { e.start() }
                    catch (_: Throwable) { synchronized(lock) {
                        if (login.phase(generation, Phase.ERROR, "Unable to connect to Telegram. Try again.")) {
                            startRequested = false
                            publish()
                        }
                    } }
                }
            }
        }
    }

    private fun ensureProxy(e: Engine, generation: Long) {
        scope.launch {
            operations.withLock {
                synchronized(lock) {
                    if (engine !== e || !login.accepts(generation)) return@synchronized
                    try {
                        if (proxyPort <= 0) proxyPort = e.startProxy().toInt()
                        if (proxyPort > 0 && login.phase(generation, Phase.READY)) publish()
                    } catch (_: Throwable) {
                        if (login.phase(generation, Phase.ERROR, "Unable to start the local player proxy.")) publish()
                    }
                }
            }
        }
    }

    fun selectLoginMethod(method: String) {
        synchronized(lock) {
            if (login.current.phase in setOf(Phase.LOGGING_OUT, Phase.LOGOUT_CLEANUP)) return
            automaticLoginAllowed = false
            if (engine == null || login.current.phase == Phase.LOGGED_OUT) {
                pendingMethod = method
                appContext?.let { start(it, userInitiated = true) }
                return
            }
        }
        synchronized(lock) {
            if (login.phase(login.generation, Phase.CONNECTING)) publish()
        }
        action { it.selectLoginMethod(method) }
    }

    fun cancelLogin() {
        synchronized(lock) { automaticLoginAllowed = false; pendingMethod = null }
        action { it.cancelLogin() }
    }

    private fun action(expectedPhase: Phase? = null, block: (Engine) -> Unit) {
        val target = synchronized(lock) {
            if (!login.accepts(login.generation) ||
                (expectedPhase != null && login.current.phase != expectedPhase)) return
            if (expectedPhase != null) {
                if (!login.beginSubmission(expectedPhase)) return
                publish()
            }
            Triple(engine ?: return, login.generation, login.current.revision)
        }
        scope.launch {
            operations.withLock {
                val current = synchronized(lock) {
                    engine === target.first && login.accepts(target.second) &&
                        (expectedPhase == null || login.current.revision == target.third)
                }
                if (!current) return@withLock
                try { block(target.first) }
                catch (t: Throwable) { synchronized(lock) {
                    if (login.phase(target.second, Phase.ERROR, t.message ?: "Telegram action failed. Try again.")) publish()
                } }
            }
        }
    }

    fun submitPhone(v: String) { action(Phase.NEED_PHONE) { it.submitPhone(v) } }
    fun submitCode(v: String) { action(Phase.NEED_CODE) { it.submitCode(v) } }
    fun submitPassword(v: String) { action(Phase.NEED_PASSWORD) { it.submitPassword(v) } }

    fun retry(ctx: Context) = scope.launch {
        operations.withLock {
            val active = synchronized(lock) {
                if (login.current.phase != Phase.ERROR) return@withLock
                engine
            }
            // Returning to the existing coordinator preserves Telegram's flood wait.
            if (active?.isLoginActive == true) { active.cancelLogin(); return@withLock }
            val old = synchronized(lock) {
                login.newEngine() // Fence old callbacks before waiting for shutdown.
                publish()
                engine.also { engine = null; startRequested = false; proxyPort = -1 }
            }
            old?.stop()
            start(ctx.applicationContext, userInitiated = true)
        }
    }

    fun logout() {
        val target = synchronized(lock) {
            if (login.current.phase !in setOf(Phase.AUTHORIZED, Phase.READY, Phase.LOGOUT_CLEANUP)) return
            val e = engine ?: return
            automaticLoginAllowed = false
            pendingMethod = null
            login.beginLogout()
            publish()
            e
        }
        scope.launch {
            operations.withLock {
                try {
                    target.logout()
                    synchronized(lock) {
                        engine = null
                        startRequested = false
                        proxyPort = -1
                        login.loggedOut()
                        publish()
                    }
                } catch (t: Throwable) {
                    synchronized(lock) {
                        val ready = target.isReady && proxyPort > 0
                        val cleanup = target.isLogoutPending
                        if (!ready) proxyPort = -1
                        login.logoutFailed(ready, cleanup, t.message ?: "Unable to log out. Try again.")
                        publish()
                    }
                }
            }
        }
    }

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
            if (LoginNavigation.needsLogin(state.value.phase) || state.value.phase in
                setOf(Phase.ERROR, Phase.LOGGED_OUT, Phase.LOGOUT_CLEANUP, Phase.LOGGING_OUT)) return@withContext false
            kotlinx.coroutines.delay(200)
        }
        isReady
    }
}
