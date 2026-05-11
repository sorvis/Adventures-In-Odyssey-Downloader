package com.odyssey.show

import com.odyssey.data.local.YshUnmatchedDao
import com.odyssey.data.local.YshUnmatchedTitleEntity
import com.odyssey.scrape.OneplaceClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your Story Hour — second YSH provider, ingesting broadcast episodes
 * from oneplace.com's YSH stream (a daily-aired feed, ~9 episodes
 * deep at any time). Differs from YshFreeStreamProvider in that
 * oneplace gives us NO album metadata at all (showTitle, series,
 * etc. are all null on YSH broadcasts), so this provider joins each
 * episode against `YshCatalog` by normalized title to attach the
 * proper album info.
 *
 * Provider id is intentionally "ysh" (same as YshFreeStreamProvider)
 * and externalId is `ysh-sku-<sku_id>` — both keyed off the catalog
 * sku_id — so the same story coming from both surfaces collapses on
 * the composite-PK dedup at upsert time.
 *
 * Catalog-miss policy: titles that don't normalize-match the catalog
 * land in `ysh_unmatched_titles` and never produce a ProviderEpisode.
 * Surfaced in-app via the badge + review screen (lands with the UI
 * work in step 10); for now, observability is via the DAO.
 *
 * lastSeen behavior: takes the oneplace CMS id stringified as
 * lastSeenExternalId (mirroring AioOneplaceProvider). This is
 * deliberately distinct from the `ysh-sku-` externalId we emit;
 * the worker passes the oneplace-shaped cursor in via the
 * `ysh__oneplace` key once step 3 lands. Pre-step-3 the worker just
 * passes null and the provider walks from latest.
 */
@Singleton
class YshOneplaceProvider @Inject constructor(
    private val oneplace: OneplaceClient,
    private val catalog: YshCatalog,
    private val unmatched: YshUnmatchedDao,
) : ShowProvider {
    override val id = "ysh"
    override val displayName = "Your Story Hour"
    override val artistName = "Your Story Hour"

    /** Overridable for tests; production targets oneplace.com. */
    var listenUrl: String = LISTEN_URL

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> {
        val lastSeen = lastSeenExternalId?.toLongOrNull() ?: 0L
        val episodes = oneplace.newSince(listenUrl, lastSeen, maxFetch)
        val out = mutableListOf<ProviderEpisode>()
        for (ep in episodes) {
            val match = catalog.lookup(ep.title)
            if (match == null) {
                // Log the miss for the in-app review screen. insert
                // is IGNORE-on-conflict so re-encounters don't error;
                // bumpAttempt then either increments an existing row's
                // count or (for a brand-new insert with count=0)
                // moves it to 1.
                unmatched.insert(
                    YshUnmatchedTitleEntity(
                        oneplaceEpisodeId = ep.episodeId,
                        title = ep.title,
                        sourceUrl = ep.url,
                        downloadUrl = ep.downloadFileUrl,
                        firstSeenAt = System.currentTimeMillis(),
                        attemptCount = 0,
                    ),
                )
                unmatched.bumpAttempt(ep.episodeId)
                continue
            }
            out += ProviderEpisode(
                externalId = "${YshFreeStreamProvider.EXTERNAL_ID_PREFIX}${match.skuId}",
                title = ep.title,
                airDate = ep.airDate,
                description = ep.description ?: ep.descriptionHtml,
                downloadUrl = ep.downloadFileUrl,
                sourceUrl = ep.url,
                durationSeconds = ep.durationSeconds,
                imageUrl = match.albumImageUrl,
            )
        }
        return out
    }

    companion object {
        const val LISTEN_URL = "https://www.oneplace.com/ministries/your-story-hour/listen/"
    }
}
