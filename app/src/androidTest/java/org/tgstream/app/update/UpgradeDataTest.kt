package org.tgstream.app.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run seed on the published APK, then verify after an in-place candidate install. */
@RunWith(AndroidJUnit4::class)
class UpgradeDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun seedDataBeforeUpgrade() {
        assertTrue(context.getSharedPreferences("tgstream", 0).edit()
            .putInt("connections", 9)
            .putString("upgrade_test_marker", "installed-before-package-rename")
            .commit())
        File(context.filesDir, "upgrade-test-marker.txt").writeText("existing private app data")
    }

    @Test fun verifyDataAfterUpgrade() {
        val prefs = context.getSharedPreferences("tgstream", 0)
        assertEquals(9, prefs.getInt("connections", -1))
        assertEquals("installed-before-package-rename", prefs.getString("upgrade_test_marker", null))
        assertEquals("existing private app data", File(context.filesDir, "upgrade-test-marker.txt").readText())
    }
}
