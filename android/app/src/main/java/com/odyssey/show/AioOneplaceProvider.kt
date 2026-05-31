package com.odyssey.show

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.debug.DebugLogger
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

    /** Overridable for tests; production targets oneplace.com. */
    var listenUrl: String = LISTEN_URL

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> {
        val lastSeen = lastSeenExternalId?.toLongOrNull() ?: 0L
        // CRITICAL FILTER: oneplace's related-episodes API doesn't
        // restrict to the requested show. Walking back from the AIO
        // latest cursor returns episodes from any oneplace ministry
        // (Jay Sekulow Live, Focus on the Family, etc.). Without this
        // filter, Sekulow rows were getting ingested with
        // providerId="aio" and showing up in the AIO Library — see
        // user report 2026-05-17. Drop anything whose showId isn't
        // AIO; tolerate null (older clients hadn't requested the
        // field) by accepting downloadUrls that contain the AIO show
        // slug as a fallback identity check.
        // Pass showId into newSince so the probe-forward loop skips
        // PAST other shows' pages until it finds an AIO-context seed.
        // The post-filter stays as a defense-in-depth — newSince's
        // showId param is optional and a future regression could lose
        // the wiring; isAio() catches any stray that slips through.
        val result = oneplace.newSince(listenUrl, lastSeen, maxFetch, showId = AIO_SHOW_ID)
            .filter { isAio(it) }
            .map { it.toProvider(catalog) }
        if (result.isEmpty()) logEmptyResultDiagnostic(lastSeen)
        return result
    }

    /**
     * When `newSince` returns empty, log WHY — bootstrap regex miss
     * vs probe exhaustion vs genuine "no new episodes". OneplaceClient
     * itself can't use [DebugLogger] (it ships in the pure-JVM compile
     * set; DebugLogger pulls in `android.util.Log`), so the diagnostic
     * lives here in the Android-side provider. One extra HTTP hit per
     * empty result is cheap insurance against the next time oneplace
     * changes its HTML and the Recent screen silently empties.
     */
    private suspend fun logEmptyResultDiagnostic(lastSeen: Long) {
        val latest = runCatching { oneplace.latestEpisodeId(listenUrl) }.getOrNull()
        when {
            latest == null -> DebugLogger.w(
                TAG,
                "newSince(lastSeen=$lastSeen) returned 0 — latestEpisodeId($listenUrl) " +
                    "is null. Either the network is down or the bootstrap regex no " +
                    "longer matches oneplace.com's HTML (it inlines `episodeId=NNNNNNN` " +
                    "in JS; a markup or var-name change breaks discovery).",
            )
            latest == lastSeen -> DebugLogger.i(
                TAG,
                "newSince(lastSeen=$lastSeen) returned 0 — latestEpisodeId=$latest " +
                    "matches lastSeen exactly, so no new AIO broadcast since the last check.",
            )
            else -> DebugLogger.w(
                TAG,
                "newSince(lastSeen=$lastSeen) returned 0 — latestEpisodeId=$latest. " +
                    "Bootstrap succeeded, so the forward-probe (cap=50) exhausted " +
                    "without finding an AIO seed (showId=$AIO_SHOW_ID). Either AIO " +
                    "hasn't published recently and the cursor sits in a deep " +
                    "Sekulow/FOTF stretch, or related-episodes is mis-tagging show ids.",
            )
        }
    }

    companion object {
        const val ID = "aio"
        private const val TAG = "AioOneplaceProvider"
        const val LISTEN_URL = "https://www.oneplace.com/ministries/adventures-in-odyssey/listen/"
        // oneplace's numeric identity for Adventures in Odyssey.
        // Confirmed live 2026-05-17 by GETting /api/related-episodes
        // with an AIO seed: every returned episode had showId=777.
        // (Jay Sekulow Live is 663, Your Story Hour is 583, etc.)
        const val AIO_SHOW_ID = 777L
        internal const val AIO_SHOW_SLUG = "adventures-in-odyssey"
    }
}

/**
 * True iff the episode belongs to AIO on oneplace. Primary check is
 * `showId == AIO_SHOW_ID`; the URL-slug fallback exists in case a
 * future API change drops showId from the response. Top-level
 * internal so the pure unit test [AioOneplaceFilterTest] can hit it
 * without standing up OneplaceClient + MockWebServer.
 */
internal fun isAio(ep: OneplaceEpisode): Boolean {
    val byShowId = ep.showId == AioOneplaceProvider.AIO_SHOW_ID
    val byUrl = ep.downloadFileUrl.contains("/${AioOneplaceProvider.AIO_SHOW_SLUG}/")
    return byShowId || (ep.showId == null && byUrl)
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
