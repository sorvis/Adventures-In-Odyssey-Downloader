package com.odyssey.catalog

/**
 * Per-episode ownership state on the user's phone, as reflected in
 * the local Room DB. The Album browse view uses this to render
 * playability badges and enable/disable the row's tap target.
 */
enum class EpisodeOwnership {
    /** filePath != null in DB; tap → playLocal. */
    DOWNLOADED,
    /** row exists in DB without filePath; tap → playStream. */
    STREAMABLE,
    /** no DB row matches this catalog episode; tap is disabled. */
    UNAVAILABLE,
}

/** A single catalog episode paired with what we know locally. */
data class CatalogEpisodeWithOwnership(
    val catalogEp: AioCatalogEpisode,
    val ownership: EpisodeOwnership,
    /**
     * The matching LocalEpisodeEntity *as a small projection*.
     * Stored as `Any?` here so this file can stay JVM-pure (no Room
     * deps for tests). Callers cast to LocalEpisodeEntity.
     */
    val localEpisode: Any? = null,
)

/** An album with per-episode ownership and aggregate counts. */
data class AlbumWithOwnership(
    val album: AioAlbum,
    val episodes: List<CatalogEpisodeWithOwnership>,
) {
    val totalCount: Int get() = album.episodes.size
    val downloadedCount: Int get() = episodes.count { it.ownership == EpisodeOwnership.DOWNLOADED }
    val streamableCount: Int get() = episodes.count { it.ownership == EpisodeOwnership.STREAMABLE }
}

/**
 * One-line summary for the album list/detail headers. Downloaded count
 * is always shown ("5 of 31 downloaded") so even an album with zero
 * downloads tells the user how big it is and that nothing's local yet.
 * Streamable suffix only appears when > 0 to avoid clutter.
 */
fun ownershipSummary(row: AlbumWithOwnership): String = buildString {
    append("${row.downloadedCount} of ${row.totalCount} downloaded")
    if (row.streamableCount > 0) append(" • ${row.streamableCount} streamable")
}

/**
 * A minimal view of a local episode that the joiner needs. Keeps the
 * pure helper free of Room dependencies — callers map their
 * LocalEpisodeEntity to this shape.
 */
data class LocalEpisodeKey(
    val title: String,
    val hasFile: Boolean,
    val raw: Any? = null,
)

/**
 * Join the AIO catalog with local episodes by normalized title.
 * Pure — no Android deps, JVM-testable. Result is sorted with [compareAlbumNumber].
 */
fun joinAlbumOwnership(
    catalog: AioCatalog,
    localEpisodes: List<LocalEpisodeKey>,
): List<AlbumWithOwnership> {
    // Pre-bucket locals by normalized title for O(1) lookup per catalog episode.
    val localByTitle: Map<String, LocalEpisodeKey> = buildMap {
        for (le in localEpisodes) {
            val key = normalizeTitle(le.title)
            if (key.isNotEmpty()) putIfAbsent(key, le)
        }
    }

    val results = catalog.albums.map { album ->
        val episodes = album.episodes.map { catEp ->
            val byName = normalizeTitle(catEp.name).takeIf { it.isNotEmpty() }
            val byShort = normalizeTitle(stripNumberPrefix(catEp.shortName)).takeIf { it.isNotEmpty() }
            val local = (byName?.let { localByTitle[it] }) ?: (byShort?.let { localByTitle[it] })
            val state = when {
                local == null -> EpisodeOwnership.UNAVAILABLE
                local.hasFile -> EpisodeOwnership.DOWNLOADED
                else -> EpisodeOwnership.STREAMABLE
            }
            CatalogEpisodeWithOwnership(catEp, state, local?.raw)
        }
        AlbumWithOwnership(album, episodes)
    }
    return results.sortedWith(albumOrder)
}

/**
 * Comparator: numeric album numbers descending, non-numeric (special
 * collections — "OHC", "FP", "PDR" etc.) sort to the bottom in
 * alphabetical order. Null → very bottom.
 */
val albumOrder: Comparator<AlbumWithOwnership> = Comparator { a, b ->
    compareAlbumNumber(a.album.albumNumber, b.album.albumNumber)
}

fun compareAlbumNumber(a: String?, b: String?): Int {
    val aNum = a?.toDoubleOrNull()
    val bNum = b?.toDoubleOrNull()
    return when {
        aNum != null && bNum != null -> bNum.compareTo(aNum)        // numeric desc
        aNum != null -> -1                                          // numeric before non-numeric
        bNum != null -> 1
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b)                                      // both non-numeric: alphabetical asc
    }
}
