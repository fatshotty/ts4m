package org.tgstream.app.update

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.tgstream.app.BuildConfig
import org.tgstream.app.ui.MainActivity
import java.io.File

/** Update identity and old explicit component names must survive source renames. */
@RunWith(AndroidJUnit4::class)
class UpdateIdentityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun existingApplicationAndLauncherIdentityRemainUsable() {
        assertEquals("net.fatshotty.tgstream", context.packageName)
        assertEquals("net.fatshotty.tgstream", BuildConfig.APPLICATION_ID)
        val pm = context.packageManager
        val legacy = ComponentName(context.packageName, "net.fatshotty.tgstream.ui.MainActivity")
        val info = pm.getActivityInfo(legacy, 0)
        assertTrue(info.exported)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
        val launcher = pm.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
        assertEquals(legacy, launcher!!.component)
    }

    @Test fun previousExternalPlayerComponentStillResolves() {
        val legacy = ComponentName(context.packageName, "net.fatshotty.tgstream.ui.PlayerEntryActivity")
        val info = context.packageManager.getActivityInfo(legacy, 0)
        assertTrue(info.exported)
        assertEquals("org.tgstream.app.ui.PlayerEntryActivity", info.targetActivity)
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tgstream://play?channel=1&messages=1"))
            .setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty())
    }

    @Test fun updateApkCanStillBeSharedUnderExistingAuthority() {
        val apk = File(context.cacheDir, "updates/identity-test.apk")
        apk.parentFile!!.mkdirs()
        apk.writeText("non-secret installer handoff fixture")
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            assertEquals("net.fatshotty.tgstream.fileprovider", uri.authority)
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                assertEquals("non-secret installer handoff fixture", it.readText())
            }
        } finally { apk.delete() }
    }
}
