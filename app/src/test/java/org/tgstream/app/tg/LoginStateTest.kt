package org.tgstream.app.tg

import org.junit.Assert.*
import org.junit.Test

class LoginStateTest {
    @Test fun pendingSubmissionSurvivesReplayUntilFreshPrompt() {
        val state = LoginState()
        val generation = state.newEngine()
        state.phase(generation, Tg.Phase.NEED_PASSWORD)
        assertTrue(state.beginSubmission(Tg.Phase.NEED_PASSWORD))
        assertTrue(state.current.inputPending)
        assertFalse(state.beginSubmission(Tg.Phase.NEED_PASSWORD))
        // Activity recreation reads the process state, including this pending bit.
        val replay = state.current
        assertTrue(replay.inputPending)
        assertEquals(Tg.Phase.NEED_PASSWORD, replay.phase)
        state.phase(generation, Tg.Phase.NEED_PASSWORD, "Incorrect password. Try again.")
        assertFalse(state.current.inputPending)
        assertTrue(state.beginSubmission(Tg.Phase.NEED_PASSWORD))
        state.beginLogout()
        assertFalse(state.current.inputPending)
        assertFalse(state.beginSubmission(Tg.Phase.NEED_PASSWORD))
    }

    @Test fun exitingWhileAMethodIsConnectingStillCancelsLogin() {
        assertTrue(LoginNavigation.shouldCancelOnExit(Tg.Phase.CONNECTING))
        assertTrue(LoginNavigation.shouldCancelOnExit(Tg.Phase.WAIT_QR))
        assertTrue(LoginNavigation.shouldCancelOnExit(Tg.Phase.NEED_PASSWORD))
        assertFalse(LoginNavigation.shouldCancelOnExit(Tg.Phase.READY))
        assertFalse(LoginNavigation.shouldCancelOnExit(Tg.Phase.LOGGING_OUT))
        assertFalse(LoginNavigation.shouldCancelOnExit(Tg.Phase.LOGGED_OUT))
    }

    @Test fun repeatedPasswordFailureStillPublishesANewPrompt() {
        val state = LoginState()
        val generation = state.newEngine()
        state.phase(generation, Tg.Phase.NEED_PASSWORD, "Incorrect password. Try again.")
        val previous = state.current
        state.phase(generation, Tg.Phase.NEED_PASSWORD, "Incorrect password. Try again.")
        assertNotEquals(previous, state.current)
        assertTrue(state.current.revision > previous.revision)
    }

    @Test fun qrIsClearedWhenPasswordOrErrorReplacesIt() {
        val state = LoginState()
        val generation = state.newEngine()
        assertTrue(state.phase(generation, Tg.Phase.WAIT_QR))
        assertTrue(state.qr(generation, QrCodeData(byteArrayOf(1), 100)))
        assertNotNull(state.current.qrCode)
        state.phase(generation, Tg.Phase.NEED_PASSWORD)
        assertNull(state.current.qrCode)
        assertFalse(state.qr(generation, QrCodeData(byteArrayOf(2), 200)))
        state.phase(generation, Tg.Phase.ERROR, "Retry")
        assertNull(state.current.qrCode)
    }

    @Test fun logoutBlocksOldProxyAndListenerCompletions() {
        val state = LoginState()
        val old = state.newEngine()
        state.phase(old, Tg.Phase.READY)
        state.beginLogout()
        assertEquals(Tg.Phase.LOGGING_OUT, state.current.phase)
        assertFalse(state.phase(old, Tg.Phase.AUTHORIZED))
        assertFalse(state.phase(old, Tg.Phase.READY))
        assertFalse(state.phase(old, Tg.Phase.ERROR, "Late error"))
        state.loggedOut()
        assertEquals(Tg.Phase.LOGGED_OUT, state.current.phase)
        assertFalse(state.phase(old, Tg.Phase.READY))
        val fresh = state.newEngine()
        assertTrue(fresh > old)
        assertFalse(state.phase(old, Tg.Phase.WAIT_QR))
        assertTrue(state.phase(fresh, Tg.Phase.NEED_LOGIN_METHOD))
    }

    @Test fun failedLogoutRestoresConnectedStateOrRequiresCleanup() {
        val state = LoginState()
        val generation = state.newEngine()
        state.phase(generation, Tg.Phase.READY)
        state.beginLogout()
        state.logoutFailed(authorized = true, cleanupPending = false, message = "Retry logout")
        assertEquals(Tg.Phase.READY, state.current.phase)
        assertEquals("Retry logout", state.current.message)
        state.beginLogout()
        state.logoutFailed(authorized = false, cleanupPending = true, message = "Retry cleanup")
        assertEquals(Tg.Phase.LOGOUT_CLEANUP, state.current.phase)
        assertFalse(state.phase(generation, Tg.Phase.READY))
        assertNull(state.current.qrCode)
    }

    @Test fun renewedQrReplacesImageAndExpiry() {
        val state = LoginState()
        val generation = state.newEngine()
        state.phase(generation, Tg.Phase.WAIT_QR)
        state.qr(generation, QrCodeData(byteArrayOf(1), 100))
        val old = state.current.qrCode
        state.qr(generation, QrCodeData(byteArrayOf(2), 200))
        assertNotSame(old, state.current.qrCode)
        assertArrayEquals(byteArrayOf(2), state.current.qrCode!!.pngData)
        assertEquals(200L, state.current.qrCode!!.expiresAtUnix)
    }

    @Test fun managementNavigationStaysOpenWhileRequiredLoginCompletes() {
        assertFalse(LoginNavigation.shouldFinish(true, Tg.Phase.READY))
        assertFalse(LoginNavigation.shouldFinish(true, Tg.Phase.AUTHORIZED))
        assertTrue(LoginNavigation.shouldFinish(false, Tg.Phase.READY))
        assertFalse(LoginNavigation.shouldFinish(false, Tg.Phase.AUTHORIZED))
        assertFalse(LoginNavigation.shouldFinish(false, Tg.Phase.LOGGED_OUT))
        assertTrue(LoginNavigation.needsLogin(Tg.Phase.WAIT_QR))
        assertTrue(LoginNavigation.needsLogin(Tg.Phase.NEED_LOGIN_METHOD))
        assertFalse(LoginNavigation.needsLogin(Tg.Phase.LOGGED_OUT))
    }
}
