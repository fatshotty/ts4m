package org.tgstream.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferTuningTest {
    @Test fun keepsLowTierAvailableOnSmallHeap() {
        val heap384Mb = 384L * 1024 * 1024

        assertEquals(
            BufferTuning.exoBytes(BufferTuning.Tier.LOW),
            BufferTuning.exoBytesForHeap(BufferTuning.Tier.LOW, heap384Mb),
        )
    }

    @Test fun capsVeryHighTierOnSmallHeap() {
        val heap384Mb = 384L * 1024 * 1024
        val capped = BufferTuning.exoBytesForHeap(BufferTuning.Tier.VHIGH, heap384Mb)

        assertTrue(capped < BufferTuning.exoBytes(BufferTuning.Tier.VHIGH))
        assertTrue(capped <= 135L * 1024 * 1024)
    }
}
