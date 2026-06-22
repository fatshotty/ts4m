package net.fatshotty.tgstream.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import net.fatshotty.tgstream.BuildConfig
import net.fatshotty.tgstream.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updater backed by GitHub Releases (public repo). Reads the stable
 * `releases/latest/download/version.json`, compares versionCode with the running
 * build, downloads the device-ABI APK and hands it to the system installer.
 * All calls are blocking — invoke off the main thread.
 */
object Updater {
    private const val REPO = "fatshotty/ts4m"
    private const val BASE = "https://github.com/$REPO/releases/latest/download"
    private const val TAG = "Updater"

    data class Release(val versionCode: Int, val versionName: String, val notes: String)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Latest release if newer than the installed build, else null. */
    fun checkForUpdate(): Release? = try {
        http.newCall(Request.Builder().url("$BASE/version.json").build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "version.json HTTP ${resp.code}"); null
            } else {
                val o = JSONObject(resp.body?.string().orEmpty())
                val rel = Release(
                    o.optInt("versionCode"), o.optString("versionName"), o.optString("notes"),
                )
                AppLog.i(TAG, "latest vc=${rel.versionCode}, installed=${BuildConfig.VERSION_CODE}")
                if (rel.versionCode > BuildConfig.VERSION_CODE) rel else null
            }
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "update check failed", t); null
    }

    private fun apkUrl(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val asset = if (abi.startsWith("arm64")) "tgstream-arm64-v8a.apk" else "tgstream-armeabi-v7a.apk"
        return "$BASE/$asset"
    }

    /** Downloads the device-ABI APK to cacheDir/updates; progress is 0..100. */
    fun download(ctx: Context, progress: (Int) -> Unit): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        http.newCall(Request.Builder().url(apkUrl()).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "apk HTTP ${resp.code}"); null
            } else {
                val body = resp.body!!
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(256 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            os.write(buf, 0, n); read += n
                            if (total > 0) progress((read * 100 / total).toInt())
                        }
                    }
                }
                out
            }
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "download failed", t); null
    }

    /**
     * Launches the system installer. Returns false (and opens the relevant
     * Settings screen) if the user must first allow "install unknown apps".
     */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                activity.startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return true
    }
}
