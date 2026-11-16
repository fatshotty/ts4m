package org.tgstream.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectProviderTest {

    @Test fun parsesCanonicalDirectLink() {
        val stream = DirectLinkCodec.parse("tgstream://play?channel=-1001234567890&messages=15-16-17")

        assertEquals(-1001234567890L, stream.channelId)
        assertEquals("15-16-17", stream.messagesDash)
        assertEquals("-1001234567890:15-16-17", stream.streamId)
        assertEquals(0L, stream.startPositionMs)
        assertEquals("", stream.title)
    }

    @Test fun directProviderReturnsNoReportingContext() {
        val result = DirectProvider().resolve("tgstream://play?channel=123&messages=10")

        assertEquals(123L, result.stream.channelId)
        assertEquals("10", result.stream.messagesDash)
        assertEquals("none", result.reporting.kind)
    }

    @Test fun acceptsAliasesAndNormalizesCommaSeparatedMessages() {
        val stream = DirectLinkCodec.parse("tgstream://play?channel_id=123&message_ids=10,11,12")

        assertEquals(123L, stream.channelId)
        assertEquals("10-11-12", stream.messagesDash)
    }

    @Test fun parsesTitleAndFractionalPositionSeconds() {
        val stream = DirectLinkCodec.parse("tgstream://play?channel=123&messages=10&title=Test%20Movie&pos=90.5")

        assertEquals("Test Movie", stream.title)
        assertEquals(90_500L, stream.startPositionMs)
    }

    @Test fun buildsCanonicalDirectLink() {
        val uri = DirectLinkCodec.build(
            StreamSpec(
                channelId = 123,
                messagesDash = "10,11",
                title = "Test Movie",
                startPositionMs = 90_500,
            ),
        )

        assertEquals("tgstream://play?channel=123&messages=10-11&title=Test+Movie&pos=90.5", uri)
    }

    @Test fun canHandleOnlyCanonicalDirectLinks() {
        assertTrue(DirectLinkCodec.canParse("tgstream://play?channel=1&messages=2"))
        assertFalse(DirectLinkCodec.canParse("tgstream://other?channel=1&messages=2"))
        assertFalse(DirectLinkCodec.canParse("https://server/emby/videos/1/stream.mkv?api_key=x"))
        assertFalse(DirectLinkCodec.canParse("not a uri with spaces"))
    }

    @Test fun rejectsMissingChannel() {
        assertProviderError("Direct link: missing channel") {
            DirectLinkCodec.parse("tgstream://play?messages=10")
        }
    }

    @Test fun rejectsMalformedMessages() {
        assertProviderError("Direct link: invalid message id") {
            DirectLinkCodec.parse("tgstream://play?channel=123&messages=10--11")
        }
    }

    @Test fun rejectsInvalidPosition() {
        assertProviderError("Direct link: invalid pos") {
            DirectLinkCodec.parse("tgstream://play?channel=123&messages=10&pos=abc")
        }
    }

    private fun assertProviderError(expectedPrefix: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue("message=${e.message}", e.message?.startsWith(expectedPrefix) == true)
        }
    }
}
