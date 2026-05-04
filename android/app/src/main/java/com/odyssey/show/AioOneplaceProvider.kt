package com.odyssey.show

import com.odyssey.scrape.OneplaceClient
import com.odyssey.scrape.OneplaceEpisode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adventures in Odyssey provider — wraps the existing oneplace.com
 * scraper. AIO's CMS id is numeric, so externalId is just the
 * stringified Long. When the entity layer needs a Long again it
 * parses back via .toLong() (safe — we control both ends).
 */
@Singleton
class AioOneplaceProvider @Inject constructor(
    private val oneplace: OneplaceClient,
) : ShowProvider {
    override val id = ID
    override val displayName = "Adventures in Odyssey"
    override val artistName = "Adventures in Odyssey"

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> {
        val lastSeen = lastSeenExternalId?.toLongOrNull() ?: 0L
        return oneplace.newSince(lastSeen, maxFetch).map { it.toProvider() }
    }

    companion object {
        const val ID = "aio"
    }
}

private fun OneplaceEpisode.toProvider() = ProviderEpisode(
    externalId = episodeId.toString(),
    title = title,
    airDate = airDate,
    description = description ?: descriptionHtml,
    downloadUrl = downloadFileUrl,
    sourceUrl = url,
    durationSeconds = durationSeconds,
    imageUrl = imageUrl,
)
