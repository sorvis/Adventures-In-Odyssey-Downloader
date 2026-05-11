package com.odyssey.show

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over the Hilt-multibound `Set<ShowProvider>` that
 * exposes by-id lookups without forcing every consumer to depend on
 * the raw set type (and its `@JvmSuppressWildcards` ceremony).
 *
 * AIO + YSH share the same `id = "ysh"` between
 * YshFreeStreamProvider and YshOneplaceProvider; the first registered
 * provider for a given id wins in `byId()`. That's intentional —
 * displayName / artistName are identical across YSH providers by
 * construction, so the dedup is a no-op for user-visible strings.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards ShowProvider>,
) {
    /** All registered providers, deduplicated by id (first wins). */
    val all: List<ShowProvider> by lazy {
        providers.distinctBy { it.id }
    }

    private val byId: Map<String, ShowProvider> by lazy {
        all.associateBy { it.id }
    }

    fun byId(id: String): ShowProvider? = byId[id]

    /**
     * Display string for `MediaMetadata.artist` keyed by providerId.
     * Unknown ids fall back to the AIO string so a row with a
     * misspelled or migrated provider never shows up blank on the
     * lockscreen.
     */
    fun artistFor(providerId: String): String =
        byId[providerId]?.artistName ?: DEFAULT_ARTIST

    companion object {
        const val DEFAULT_ARTIST = "Adventures in Odyssey"
    }
}
