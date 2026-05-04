package com.odyssey.show

/**
 * One source of episodes — Adventures in Odyssey today, Your Story
 * Hour next, possibly generic RSS later. The product niche is daily-
 * aired radio shows people want to collect over time, so each
 * provider is a one-off scrape against a specific publisher's
 * surface (oneplace.com Apex REST for AIO, etc.).
 *
 * Kept deliberately small for now: just enough surface for the
 * daily-check worker to pull new episodes from each registered
 * provider. Enrichment, per-episode artwork, and per-provider
 * retention are deferred until a second provider actually exists
 * and forces those questions.
 */
interface ShowProvider {
    /** Stable provider id — "aio", later "ysh", "rss-<feedHash>". */
    val id: String

    /** Human label — "Adventures in Odyssey". */
    val displayName: String

    /** Goes into MediaMetadata.artist for lockscreen display. */
    val artistName: String

    /**
     * Pull episodes newer than `lastSeenExternalId`, newest-first,
     * capped at `maxFetch`. Pass null for a fresh install.
     *
     * `externalId` is whatever stable id the provider uses — for AIO
     * the oneplace CMS id stringified, for future RSS providers the
     * `<guid>` element.
     */
    suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode>
}

data class ProviderEpisode(
    val externalId: String,
    val title: String,
    val airDate: String?,
    val description: String?,
    val downloadUrl: String,
    val sourceUrl: String,
    val durationSeconds: Long,
    val imageUrl: String?,
)
