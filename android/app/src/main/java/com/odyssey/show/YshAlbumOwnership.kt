package com.odyssey.show

import com.odyssey.data.local.YshAlbumSummary

/**
 * Pure helper that turns the full YshCatalog into one row per album,
 * overlaying download counts from the local DB. Mirrors AIO's
 * `joinAlbumOwnership` so the YSH Albums tab can show every catalog
 * album (faded for albums with zero downloads, opaque once at least
 * one track lands on the phone) instead of only listing albums the
 * user has already ingested.
 *
 * Catalog is the source of truth for the album list and total track
 * count; the DB only contributes the per-album `downloadedTracks`
 * overlay. Albums present in [dbSummaries] but absent from [catalog]
 * are dropped — that situation only arises if the catalog refresh
 * fell behind a brand-new album going live, and the row will
 * reappear on the next catalog refresh.
 *
 * Joins by exact [YshCatalogTrack.albumTitle] == [YshAlbumSummary.albumName].
 * Both sides populate that field from the same API response field
 * (`product`/`album_title`), so case/whitespace match by construction —
 * no normalization needed.
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
    dbSummaries: List<YshAlbumSummary>,
): List<YshAlbumCatalogRow> {
    val downloadedByAlbum: Map<String, Int> =
        dbSummaries.associate { it.albumName to it.downloadedCount }

    return catalog.tracks
        .groupBy { it.albumId }
        .map { (albumId, tracks) ->
            val first = tracks.first()
            YshAlbumCatalogRow(
                albumId = albumId,
                albumName = first.albumTitle,
                coverUrl = first.albumImageUrl,
                totalTracks = tracks.size,
                downloadedTracks = downloadedByAlbum[first.albumTitle] ?: 0,
            )
        }
        .sortedBy { it.albumName.lowercase() }
}
