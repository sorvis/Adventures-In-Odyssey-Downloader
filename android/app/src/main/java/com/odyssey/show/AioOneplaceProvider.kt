package com.odyssey.show

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.scrape.OneplaceClient
import com.odyssey.scrape.OneplaceEpisode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adventures in Odyssey provider — wraps the existing oneplace.com
 * scraper. AIO publishes episodes under TWO independent number
 * spaces: the oneplace CMS id (~7-digit, e.g. 1278294) and the
 * canonical broadcast number listeners actually use (e.g. 657 for
 * "Clutter"). Using broadcast numbers as the local episode_id
 * means oneplace downloads, drop-folder imports, and pin-from-
 * backup all converge on the SAME row for the same logical
 * episode — no more duplicates by source.
 *
 * Lookup priority for a oneplace episode:
 *   1. Catalog match by title → use the broadcast number from
 *      `shortName` ("#657: Clutter" → 657).
 *   2. Catalog match without a broadcast number → CMS id (rare —
 *      means the catalog row exists but has no `#NNN:` prefix).
 *   3. No catalog match → CMS id (the unmatched-fallback case;
 *      collisions with broadcast numbers are impossible because
 *      CMS ids are 1.27M+ while broadcast numbers stay under ~2k).
 */
@Singleton
class AioOneplaceProvider @Inject constructor(
    private val oneplace: OneplaceClient,
    private val catalog: AioCatalogRepo,
) : ShowProvider {
    override val id = ID
    override val displayName = "Adventures in Odyssey"
    override val artistName = "Adventures in Odyssey"

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> {
        val lastSeen = lastSeenExternalId?.toLongOrNull() ?: 0L
        return oneplace.newSince(lastSeen, maxFetch).map { it.toProvider(catalog) }
    }

    companion object {
        const val ID = "aio"
    }
}

private fun OneplaceEpisode.toProvider(catalog: AioCatalogRepo): ProviderEpisode {
    val resolvedId = preferredEpisodeId(this, catalog)
    return ProviderEpisode(
        externalId = resolvedId.toString(),
        title = title,
        airDate = airDate,
        description = description ?: descriptionHtml,
        downloadUrl = downloadFileUrl,
        sourceUrl = url,
        durationSeconds = durationSeconds,
        imageUrl = imageUrl,
    )
}

/**
 * Pure helper: pick the canonical broadcast number for this oneplace
 * episode if the catalog has one, else fall back to the CMS id.
 * Extracted so tests can hit the decision without standing up a
 * real catalog repo.
 */
internal fun preferredEpisodeId(ep: OneplaceEpisode, catalog: AioCatalogRepo): Long {
    val match = catalog.match(ep.title) ?: return ep.episodeId
    val short = match.episode.shortName
    val parsed = Regex("""#\s*(\d+)""").find(short)?.groupValues?.get(1)?.toLongOrNull()
    return parsed ?: ep.episodeId
}
