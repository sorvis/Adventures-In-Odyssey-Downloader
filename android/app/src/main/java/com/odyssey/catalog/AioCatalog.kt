package com.odyssey.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public AIO album catalog scraped from
 * `fotf.my.site.com/aio/services/apexrest/v1/contentgrouping/search`
 * (an anonymous read-only endpoint behind the AIO Club Flutter SPA).
 *
 * Bundled as the asset `aio_catalog.json` and used to enrich
 * oneplace.com episodes with canonical episode numbers ("#657 Clutter")
 * and real per-episode thumbnails.
 *
 * Refresh by running `scripts/aio-scrape-catalog.py` whenever new
 * albums air; it overwrites the asset in place.
 */

@Serializable
data class AioCatalog(
    val scrapedAtMs: Long,
    val albumCount: Int,
    val albums: List<AioAlbum>,
)

@Serializable
data class AioAlbum(
    /**
     * Album number as the catalog returns it. Usually numeric ("81"),
     * sometimes fractional ("78.5"), sometimes a special-collection code
     * ("OHC", "FP", "PDR"). Treat as opaque string.
     */
    val albumNumber: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val totalRuntimeMs: Long? = null,
    val episodes: List<AioCatalogEpisode> = emptyList(),
)

@Serializable
data class AioCatalogEpisode(
    /** Display title — e.g. "Clutter" (no number prefix). */
    val name: String = "",
    /**
     * Canonical-numbered title — e.g. "#657: Clutter". Empty when this
     * episode doesn't have a canonical AIO number (specials, bonus
     * content, club-only one-offs).
     */
    @SerialName("shortName")
    val shortName: String = "",
    val thumbnailSmall: String? = null,
    val thumbnailMedium: String? = null,
    val mediaLengthMs: Long? = null,
    val subtype: String? = null,
    val description: String? = null,
)
