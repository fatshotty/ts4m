package org.tgstream.app.tg

data class QrCodeData(val pngData: ByteArray, val expiresAtUnix: Long)

/** Mutated under Tg's lock, including callbacks from Go goroutine threads. */
internal class LoginState {
    var current = Tg.State()
        private set
    var generation = 0L
        private set
    private var loggingOut = false

    fun newEngine(): Long {
        generation++
        loggingOut = false
        current = Tg.State(Tg.Phase.CONNECTING)
        return generation
    }

    fun accepts(source: Long): Boolean = source == generation && !loggingOut &&
        current.phase != Tg.Phase.LOGGED_OUT && current.phase != Tg.Phase.LOGOUT_CLEANUP

    fun phase(source: Long, phase: Tg.Phase, message: String = ""): Boolean {
        if (!accepts(source)) return false
        current = Tg.State(phase, message, revision = current.revision + 1)
        return true
    }

    fun qr(source: Long, data: QrCodeData): Boolean {
        if (!accepts(source) || current.phase != Tg.Phase.WAIT_QR) return false
        current = current.copy(qrCode = data)
        return true
    }

    fun beginSubmission(phase: Tg.Phase): Boolean {
        if (!accepts(generation) || current.phase != phase || current.inputPending ||
            phase !in setOf(Tg.Phase.NEED_PHONE, Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD)) return false
        current = current.copy(inputPending = true)
        return true
    }

    fun beginLogout() {
        loggingOut = true
        current = Tg.State(Tg.Phase.LOGGING_OUT)
    }

    fun loggedOut() {
        generation++
        loggingOut = false
        current = Tg.State(Tg.Phase.LOGGED_OUT)
    }

    fun logoutFailed(authorized: Boolean, cleanupPending: Boolean, message: String) {
        loggingOut = false
        current = Tg.State(when {
            cleanupPending -> Tg.Phase.LOGOUT_CLEANUP
            authorized -> Tg.Phase.READY
            else -> Tg.Phase.ERROR
        }, message)
    }
}

object LoginNavigation {
    fun shouldCancelOnExit(phase: Tg.Phase): Boolean = needsLogin(phase) ||
        phase == Tg.Phase.CONNECTING || phase == Tg.Phase.ERROR

    fun shouldFinish(management: Boolean, phase: Tg.Phase): Boolean =
        !management && phase == Tg.Phase.READY

    fun needsLogin(phase: Tg.Phase): Boolean = phase in setOf(
        Tg.Phase.NEED_LOGIN_METHOD, Tg.Phase.WAIT_QR, Tg.Phase.NEED_PHONE,
        Tg.Phase.NEED_CODE, Tg.Phase.NEED_PASSWORD,
    )
}
