package org.tgstream.app.emby

/**
 * Builds the `X-Emby-Authorization` header the Emby/Jellyfin API expects to
 * identify the client device and session. Reusing the DeviceId from the launch
 * URL ties our requests to the session Emby already opened for this playback —
 * which is what makes progress reporting behave like the native player.
 */
object EmbyAuth {
    private const val CLIENT = "tgstream"
    private const val VERSION = "1.0"

    fun header(token: String, deviceId: String): String {
        val device = (android.os.Build.MODEL ?: "Android").replace('"', '\'')
        val id = deviceId.ifBlank { "tgstream-${android.os.Build.MODEL ?: "device"}" }
        return "MediaBrowser Client=\"$CLIENT\", Device=\"$device\", " +
            "DeviceId=\"$id\", Version=\"$VERSION\"" +
            (if (token.isNotBlank()) ", Token=\"$token\"" else "")
    }
}
