package com.odyssey.catalog

import android.content.Context
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process AIO catalog cache. Loads `aio_catalog.json` from the APK
 * assets exactly once and pre-builds a normalized title → AioMatch
 * map so per-episode lookups are O(1).
 *
 * Construction is lazy — the load doesn't happen until the first
 * `match()` call, so the app starts up without paying the deserialize
 * cost up front (498 KB JSON, 1182 episodes).
 */
@Singleton
class AioCatalogRepo @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Empty catalog as fallback if the asset can't be read. */
    private val emptyCatalog = AioCatalog(scrapedAtMs = 0L, albumCount = 0, albums = emptyList())

    val catalog: AioCatalog by lazy {
        runCatching {
            val text = ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            json.decodeFromString<AioCatalog>(text)
        }.onFailure {
            DebugLogger.e("AioCatalogRepo", "Failed to load $ASSET_NAME — enrichment disabled", it)
        }.getOrDefault(emptyCatalog)
    }

    /**
     * Normalized-title → first matching (album, episode). Built once
     * from `catalog.albums`. Both the plain `name` and the
     * number-stripped `shortName` are indexed so either form matches.
     */
    private val byTitle: Map<String, AioMatch> by lazy {
        buildMap {
            for (album in catalog.albums) {
                for (ep in album.episodes) {
                    val nameKey = normalizeTitle(ep.name)
                    if (nameKey.isNotEmpty()) putIfAbsent(nameKey, AioMatch(album, ep))
                    val shortKey = normalizeTitle(stripNumberPrefix(ep.shortName))
                    if (shortKey.isNotEmpty()) putIfAbsent(shortKey, AioMatch(album, ep))
                }
            }
        }
    }

    fun match(oneplaceTitle: String): AioMatch? {
        val key = normalizeTitle(oneplaceTitle)
        if (key.isEmpty()) return null
        return byTitle[key]
    }

    private companion object { const val ASSET_NAME = "aio_catalog.json" }
}
