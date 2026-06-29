package net.fatshotty.tgstream.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {

    @Test fun directProviderIsFirstProvider() {
        assertTrue(ProviderRegistry.all.first() is DirectProvider)
    }

    @Test fun registryKeepsEmbyProvider() {
        assertEquals(listOf("direct", "emby"), ProviderRegistry.all.map { it.id })
    }
}
