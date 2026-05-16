package com.odyssey.show

import com.odyssey.catalog.AlbumFilter
import com.odyssey.catalog.AlbumSort
import com.odyssey.data.local.LocalEpisodeEntity

/**
 * Pure helper that turns the full YshCatalog into one row per album,
 * overlaying download counts from the local DB. Mirrors AIO's
 * `joinAlbumOwnership` so the YSH Albums tab can show every catalog
 * album (faded for albums with zero downloads, opaque once at least
 * one track lands on the phone) instead of only listing albums the
 * user has already ingested.
 *
 * Catalog is the source of truth for the album list and total track
 * count; the DB only contributes `downloadedTracks` by counting how
 * many local rows' skuIds appear in the album's catalog tracks.
 *
 * **Important:** join key is `skuId` (parsed from the row's
 * `externalId` of shape `ysh-sku-<n>`), NOT `albumName`. Pre-v0.1.58
 * builds had no `albumName` on YSH rows at all (DailyCheckWorker
 * never populated it), so any join through that field missed every
 * downloaded YSH episode — every album showed "0 downloaded" and
 * every track in album-detail showed UNAVAILABLE even right after
 * the file landed on disk. skuId is stable and present on every row
 * by construction.
 */
data class YshAlbumCatalogRow(
    val albumId: Long,
    val albumName: String,
    val coverUrl: String?,
    val totalTracks: Int,
    val downloadedTracks: Int,
)

fun joinYshAlbumOwnership(
    catalog: YshCatalogIndex,
    dbRows: List<LocalEpisodeEntity>,
): List<YshAlbumCatalogRow> {
    val downloadedSkuIds: Set<Long> = dbRows
        .asSequence()
        .filter { it.providerId == "ysh" && it.filePath != null }
        .mapNotNull { it.externalId.removePrefix("ysh-sku-").toLongOrNull() }
        .toSet()

    return catalog.tracks
        .groupBy { it.albumId }
        .map { (albumId, tracks) ->
            val first = tracks.first()
            YshAlbumCatalogRow(
                albumId = albumId,
                albumName = first.albumTitle,
                coverUrl = first.albumImageUrl,
                totalTracks = tracks.size,
                downloadedTracks = tracks.count { it.skuId in downloadedSkuIds },
            )
        }
        .sortedBy { it.albumName.lowercase() }
}

/**
 * Filter YSH rows by [AlbumFilter]. Mirrors AIO's [com.odyssey.catalog.filterAlbums]
 * but operates on [YshAlbumCatalogRow]. The shared enum is reused so a
 * single Sort+Filter Composable in the TopAppBar drives both shows;
 * the show-specific logic just plugs in here.
 *
 * `HasOnBackup` always returns the empty list because YSH has no
 * backup-upload path yet (the archive-service is AIO-only today). The
 * YSH screen hides that option via `availableFilters` so the user
 * never picks it, but the function honors it defensively if some
 * future code path passes it.
 */
fun filterYshAlbums(
    rows: List<YshAlbumCatalogRow>,
    filter: AlbumFilter,
): List<YshAlbumCatalogRow> = when (filter) {
    AlbumFilter.All -> rows
    AlbumFilter.HasOnPhone -> rows.filter { it.downloadedTracks > 0 }
    AlbumFilter.HasOnBackup -> emptyList()
}

/**
 * Sort YSH rows by [AlbumSort]. The shared enum is reused; the
 * mode→ordinality mapping is show-specific:
 *
 *   Default        – case-insensitive alphabetical (what
 *                    `joinYshAlbumOwnership` already returns).
 *   Chronological  – by [YshAlbumCatalogRow.albumId] ascending. YSH
 *                    doesn't have a canonical "album number" like AIO,
 *                    but albumId roughly correlates with the order the
 *                    catalog grew over time.
 *   MostDownloaded – highest downloaded-ratio first, alphabetical
 *                    secondary for stable order on ties.
 */
fun sortYshAlbums(
    rows: List<YshAlbumCatalogRow>,
    mode: AlbumSort,
): List<YshAlbumCatalogRow> {
    val byName: Comparator<YshAlbumCatalogRow> = compareBy { it.albumName.lowercase() }
    return when (mode) {
        AlbumSort.Default -> rows.sortedWith(byName)
        AlbumSort.Chronological -> rows.sortedWith(compareBy<YshAlbumCatalogRow> { it.albumId }.then(byName))
        AlbumSort.MostDownloaded -> rows.sortedWith(
            compareByDescending<YshAlbumCatalogRow> { downloadedRatio(it) }.then(byName),
        )
    }
}

private fun downloadedRatio(row: YshAlbumCatalogRow): Double =
    if (row.totalTracks == 0) 0.0 else row.downloadedTracks.toDouble() / row.totalTracks
