package net.fatshotty.tgstream.provider

import android.net.Uri

/**
 * Preconfigured providers. With a single entry we never prompt the user; with
 * more than one we'd show a picker (future).
 */
object ProviderRegistry {
    val all: List<Provider> = listOf(EmbyProvider())

    /** Providers that can handle the given URI. */
    fun candidates(uri: Uri): List<Provider> = all.filter { it.canHandle(uri) }

    /** The single default provider when only one is configured. */
    val single: Provider? get() = all.singleOrNull()
}
