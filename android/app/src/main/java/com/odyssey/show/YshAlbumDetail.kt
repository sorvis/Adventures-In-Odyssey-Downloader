package com.odyssey.show

import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger

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

    val catalogRows = catalog.tracks
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

    // Robustness: a downloaded row whose stored albumName matches this
    // album but whose skuId isn't among the catalog's tracks for it
    // (catalog drift, or a free-stream sku the deep catalog dropped)
    // would otherwise vanish from its own album. Surface those DB rows
    // too so "Go to album" never lands on a screen missing the very
    // episode the user came from.
    val catalogSkuIds = catalogRows.mapTo(HashSet()) { it.skuId }
    val orphanRows = dbRowsForAlbum
        .filter { it.providerId == "ysh" && it.albumName == albumName }
        .mapNotNull { row ->
            val skuId = row.externalId.removePrefix("ysh-sku-").toLongOrNull() ?: return@mapNotNull null
            if (skuId in catalogSkuIds) return@mapNotNull null
            YshAlbumDetailRow(
                skuId = skuId,
                title = row.title,
                orderIndex = row.albumTrackOrder ?: Int.MAX_VALUE,
                albumImageUrl = row.albumImageUrl ?: row.imageUrl,
                ownership = if (row.filePath != null) YshTrackOwnership.DOWNLOADED
                            else YshTrackOwnership.STREAMABLE,
                localRow = row,
            )
        }

    return (catalogRows + orphanRows)
        .sortedWith(compareBy({ it.orderIndex }, { it.title }))
}

/**
 * Resolve the album NAME for a YSH row. Prefers the album name
 * persisted on the row at ingest (v0.1.84+); falls back to a catalog
 * lookup by skuId for rows ingested before persistence landed. Returns
 * null for non-YSH rows, malformed externalIds, or when neither the
 * row nor the (possibly-unloaded) catalog can supply a name. This is
 * the join key for navigating to `ysh-album/{albumName}`.
 */
fun yshAlbumNameForRow(
    row: LocalEpisodeEntity,
    catalog: YshCatalogIndex?,
): String? {
    if (row.providerId != "ysh") return null
    row.albumName?.takeIf { it.isNotBlank() }?.let { return it }
    if (catalog == null) return null
    val skuId = row.externalId.removePrefix("ysh-sku-").toLongOrNull() ?: return null
    return catalog.tracks.firstOrNull { it.skuId == skuId }?.albumTitle
}

/**
 * Backfill album metadata onto YSH rows that predate album-at-ingest.
 * For each row with a null albumName whose skuId is in [catalog], writes
 * the album name / cover / track order. Idempotent — rows already
 * carrying an albumName are skipped by the DAO query. Returns the number
 * of rows updated. Run after catalog load and after each refresh.
 */
suspend fun backfillYshAlbums(dao: EpisodeDao, catalog: YshCatalogIndex): Int {
    val bySkuId = catalog.tracks.associateBy { it.skuId }
    var updated = 0
    for (row in dao.yshRowsMissingAlbum()) {
        val skuId = row.externalId.removePrefix("ysh-sku-").toLongOrNull() ?: continue
        val track = bySkuId[skuId] ?: continue
        dao.setAlbumInfo(
            providerId = "ysh",
            externalId = row.externalId,
            albumName = track.albumTitle,
            albumImageUrl = track.albumImageUrl,
            albumTrackOrder = track.orderIndex,
        )
        updated++
    }
    if (updated > 0) DebugLogger.i("YshAlbumBackfill", "backfilled album on $updated YSH row(s)")
    return updated
}

/**
 * Resolve an artwork URL for a YSH row by looking up its skuId in the
 * catalog. Used as a fallback when [LocalEpisodeEntity.imageUrl] is
 * null — which happens for free-streaming-pool rows whose
 * `/crud/free-streaming` response came back with a null
 * `primary_image`, even though the same album in `/crud/product/skus`
 * (the catalog) reliably has a cover.
 *
 * Returns null for non-YSH rows, for YSH rows with a malformed
 * externalId, or when the catalog hasn't loaded yet. Callers chain
 * `row.imageUrl ?: yshAlbumImageUrlForRow(row, catalog)` to keep the
 * preferred per-episode imageUrl when it exists.
 */
fun yshAlbumImageUrlForRow(
    row: LocalEpisodeEntity,
    catalog: YshCatalogIndex?,
): String? {
    if (row.providerId != "ysh") return null
    if (catalog == null) return null
    val skuId = row.externalId.removePrefix("ysh-sku-").toLongOrNull() ?: return null
    return catalog.tracks.firstOrNull { it.skuId == skuId }?.albumImageUrl
}
