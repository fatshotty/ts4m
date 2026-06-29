package net.fatshotty.tgstream.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPositionResolverTest {
    @Test fun providerReportingUsesProviderStartEvenWhenInboundResumeExists() {
        val startMs = StartPositionResolver.choose(
            reportingKind = "emby",
            inboundResumeMs = 90_000,
            providerStartMs = 0,
        )

        assertEquals(0L, startMs)
    }

    @Test fun directPlaybackCanUseInboundResumePosition() {
        val startMs = StartPositionResolver.choose(
            reportingKind = "none",
            inboundResumeMs = 90_000,
            providerStartMs = 0,
        )

        assertEquals(90_000L, startMs)
    }
}
