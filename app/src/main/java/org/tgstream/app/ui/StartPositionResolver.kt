package org.tgstream.app.ui

object StartPositionResolver {
    fun choose(reportingKind: String, inboundResumeMs: Long, providerStartMs: Long): Long {
        val providerMs = providerStartMs.coerceAtLeast(0)
        val inboundMs = inboundResumeMs.takeIf { it >= 0 }
        return when (reportingKind) {
            "none" -> inboundMs ?: providerMs
            else -> providerMs
        }
    }
}
