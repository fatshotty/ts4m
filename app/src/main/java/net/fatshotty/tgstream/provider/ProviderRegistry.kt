package net.fatshotty.tgstream.provider

import android.net.Uri

/**
 * Preconfigured source adapters. The incoming URI selects the provider; no
 * picker is needed while schemes/hosts are unambiguous.
 */
object ProviderRegistry {
    val all: List<Provider> = listOf(DirectProvider(), EmbyProvider())

    /** Providers that can handle the given URI. */
    fun candidates(uri: Uri): List<Provider> = all.filter { it.canHandle(uri) }

    /** The single default provider when only one is configured. */
    val single: Provider? get() = all.singleOrNull()
}
