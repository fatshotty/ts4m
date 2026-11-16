package org.tgstream.app.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAccountTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else emptyList()

    @Test fun unsavedSettingsSurviveRecreation() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            var bufferLabel = ""
            scenario.onActivity { activity ->
                val all = views(activity.window.decorView)
                val fields = all.filterIsInstance<EditText>()
                fields[0].setText("9")
                fields[1].setText("123456")
                fields[2].setText("unsaved-test-api-hash")
                val buffer = all.filterIsInstance<Button>().first { it.text.startsWith("Buffer:") }
                buffer.performClick()
                bufferLabel = buffer.text.toString()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val all = views(activity.window.decorView)
                val fields = all.filterIsInstance<EditText>()
                assertEquals("9", fields[0].text.toString())
                assertEquals("123456", fields[1].text.toString())
                assertEquals("unsaved-test-api-hash", fields[2].text.toString())
                assertEquals(bufferLabel, all.filterIsInstance<Button>().first { it.text.startsWith("Buffer:") }.text.toString())
            }
        }
    }

    @Test fun settingsOpensAccountManagementAndBackReturnsToSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(LoginActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    views(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text == "Telegram account" }.performClick()
                }
                val account = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? LoginActivity
                assertNotNull("Settings did not open the account screen", account)
                instrumentation.runOnMainSync {
                    assertTrue(account!!.intent.getBooleanExtra(LoginActivity.EXTRA_ACCOUNT_MANAGEMENT, false))
                    account.onBackPressedDispatcher.onBackPressed()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Telegram account" })
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
