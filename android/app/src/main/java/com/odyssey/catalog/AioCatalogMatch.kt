package com.odyssey.catalog

/**
 * Match a oneplace.com episode title against the AIO catalog. Returns
 * the matched (album, episode) pair when one is found, or null.
 *
 * Matching is case-insensitive, whitespace-normalized, and strips a
 * leading "#NNN: " prefix from either side. We also strip surrounding
 * quotes and curly-quote variants because oneplace's titles
 * occasionally include them while the catalog never does.
 *
 * Pure — JVM-testable, no Android deps.
 */
data class AioMatch(
    val album: AioAlbum,
    val episode: AioCatalogEpisode,
) {
    /**
     * Canonical "#NNN: Title" string for display. Falls back to the
     * episode `name` if no number prefix exists.
     */
    val displayName: String
        get() = episode.shortName.takeIf { it.isNotBlank() } ?: episode.name

    /** Best available thumbnail URL — medium preferred over small. */
    val thumbnailUrl: String?
        get() = episode.thumbnailMedium?.takeIf { it.isNotBlank() }
            ?: episode.thumbnailSmall?.takeIf { it.isNotBlank() }
            ?: album.imageUrl
}

/**
 * Match by title against [catalog]. Returns null when no match is
 * found. Use [findMatchByTitle] from the app's enrichment path.
 */
fun findMatchByTitle(catalog: AioCatalog, oneplaceTitle: String): AioMatch? {
    val needle = normalizeTitle(oneplaceTitle)
    if (needle.isEmpty()) return null
    for (album in catalog.albums) {
        for (ep in album.episodes) {
            val candidate = listOf(ep.name, stripNumberPrefix(ep.shortName))
                .firstOrNull { normalizeTitle(it) == needle }
            if (candidate != null) return AioMatch(album, ep)
        }
    }
    return null
}

fun normalizeTitle(raw: String): String {
    if (raw.isBlank()) return ""
    val withoutQuotes = raw
        .replace('“', '"').replace('”', '"')
        .replace('‘', '\'').replace('’', '\'')
        .trim('"', '\'', ' ', '\t')
    return stripNumberPrefix(withoutQuotes)
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun stripNumberPrefix(s: String): String {
    // "#657: Clutter" → "Clutter"; also handles "#657 Clutter" (no colon).
    val match = Regex("""^\s*#\s*\d+(?:[\.½⅓⅔]\d*)?\s*:?\s*""").find(s) ?: return s
    return s.substring(match.range.last + 1)
}
