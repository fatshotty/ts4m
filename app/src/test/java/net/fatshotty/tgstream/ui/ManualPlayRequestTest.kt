package net.fatshotty.tgstream.ui

import net.fatshotty.tgstream.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPlayRequestTest {
    @Test fun buildsDirectLinkFromManualInput() {
        val uri = ManualPlayRequest.toDirectLink(
            channelText = " -1001234567890 ",
            messagesText = " 10, 11-12 ",
            titleText = " Test Movie ",
            positionSecondsText = "90.5",
        )

        assertEquals(
            "tgstream://play?channel=-1001234567890&messages=10-11-12&title=Test+Movie&pos=90.5",
            uri,
        )
    }

    @Test fun omitsOptionalFieldsWhenBlank() {
        val uri = ManualPlayRequest.toDirectLink(
            channelText = "123",
            messagesText = "10",
            titleText = "",
            positionSecondsText = "",
        )

        assertEquals("tgstream://play?channel=123&messages=10", uri)
    }

    @Test fun rejectsBlankChannel() {
        assertManualInputError("Manual play: missing channel_id") {
            ManualPlayRequest.toDirectLink("", "10", "", "")
        }
    }

    @Test fun rejectsBlankMessages() {
        assertManualInputError("Manual play: missing message_ids") {
            ManualPlayRequest.toDirectLink("123", "", "", "")
        }
    }

    @Test fun rejectsInvalidPosition() {
        assertManualInputError("Manual play: invalid position") {
            ManualPlayRequest.toDirectLink("123", "10", "", "abc")
        }
    }

    private fun assertManualInputError(expectedPrefix: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue("message=${e.message}", e.message?.startsWith(expectedPrefix) == true)
        }
    }
}
