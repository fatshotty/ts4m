package net.fatshotty.tgstream.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-global, in-memory log buffer. Mirrors everything to android.util.Log
 * (so `adb logcat -s TgStream` still works) and keeps the last [MAX] lines so
 * [net.fatshotty.tgstream.ui.LogActivity] can show them on-device.
 *
 * It is a process singleton, so logs written by short-lived activities like
 * PlayerEntryActivity (noHistory + finish) survive until the process is killed.
 */
object AppLog {
    private const val MAX = 2000
    private val secretQueryParam = Regex(
        "([?&](?:api_key|apikey|api-token|apitoken|x-emby-token|token)=)[^&]+",
        RegexOption.IGNORE_CASE,
    )
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t)
        add("W", tag, msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("E", tag, msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    fun snapshot(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    fun redactUrl(url: String): String =
        secretQueryParam.replace(url) { it.groupValues[1] + "***" }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }
}
