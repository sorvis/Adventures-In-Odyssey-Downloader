package com.odyssey.show

import com.odyssey.data.local.LocalEpisodeEntity

/**
 * Per-track view shown on YshAlbumDetailScreen. Built by
 * [joinYshAlbumDetail] from the catalog (track universe) overlaid
 * with the local DB rows (what the user actually has).
 *
 * Mirrors AIO's [com.odyssey.catalog.CatalogEpisodeWithOwnership]
 * so the YSH album-detail UX matches:
 *   - faded row for UNAVAILABLE (catalog knows about it, but it's
 *     not in the free pool and we have no DB row → can't stream)
 *   - normal row for STREAMABLE (DB has downloadUrl; tap → stream)
 *   - "downloaded" badge for DOWNLOADED (file on disk; tap → play
 *     local)
 *
 * skuId is the catalog's identity for the track and round-trips into
 * the DB's externalId as `"ysh-sku-<skuId>"`. albumImageUrl flows from
 * the catalog (single source of truth) so a faded row still gets the
 * cover art for the album header to use.
 */
data class YshAlbumDetailRow(
    val skuId: Long,
    val title: String,
    val orderIndex: Int,
    val albumImageUrl: String?,
    val ownership: YshTrackOwnership,
    val localRow: LocalEpisodeEntity?,
)

enum class YshTrackOwnership {
    /** filePath != null in DB. Tap → playLocal. */
    DOWNLOADED,
    /** DB row exists, no file. Tap → playStream(downloadUrl). */
    STREAMABLE,
    /** Catalog-only — no DB row. Track is currently outside the free
     *  pool and we have no stream URL. Tap is disabled. */
    UNAVAILABLE,
}

/**
 * Build the per-track detail for [albumName] by joining the full
 * [catalog] with the DB rows we already have (passed in from
 * `EpisodeDao.observeYshAlbumTracks` — caller restricts to this album,
 * but we tolerate extras defensively).
 *
 * Pure — no Room/Android deps, JVM-testable. Sort: catalog orderIndex
 * ASC, then title for stable ordering on ties.
 */
fun joinYshAlbumDetail(
    catalog: YshCatalogIndex,
    albumName: String,
    dbRowsForAlbum: List<LocalEpisodeEntity>,
): List<YshAlbumDetailRow> {
    // DB rows index — keyed by skuId extracted from the externalId.
    // Defensive: skip rows whose externalId doesn't parse (any
    // non-YSH or malformed row in the input list).
    val bySkuId: Map<Long, LocalEpisodeEntity> = dbRowsForAlbum
        .mapNotNull { row ->
            val skuId = row.externalId.removePrefix("ysh-sku-").toLongOrNull() ?: return@mapNotNull null
            skuId to row
        }
        .toMap()

    return catalog.tracks
        .filter { it.albumTitle == albumName }
        .map { track ->
            val local = bySkuId[track.skuId]
            val ownership = when {
                local == null -> YshTrackOwnership.UNAVAILABLE
                local.filePath != null -> YshTrackOwnership.DOWNLOADED
                else -> YshTrackOwnership.STREAMABLE
            }
            YshAlbumDetailRow(
                skuId = track.skuId,
                title = track.title,
                orderIndex = track.orderIndex,
                albumImageUrl = track.albumImageUrl,
                ownership = ownership,
                localRow = local,
            )
        }
        .sortedWith(compareBy({ it.orderIndex }, { it.title }))
}
