package org.tgstream.app.provider

import android.net.Uri

/** Provider for canonical tgstream://play direct links. */
class DirectProvider : Provider {
    override val id = "direct"
    override val label = "Direct"

    override fun canHandle(uri: Uri): Boolean = DirectLinkCodec.canParse(uri.toString())

    override fun resolve(uri: Uri): ResolveResult = resolve(uri.toString())

    fun resolve(raw: String): ResolveResult =
        ResolveResult(
            stream = DirectLinkCodec.parse(raw),
            reporting = ReportingContext(kind = "none"),
        )
}
