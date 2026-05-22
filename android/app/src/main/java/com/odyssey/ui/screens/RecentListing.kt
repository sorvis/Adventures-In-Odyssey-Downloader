package com.odyssey.ui.screens

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure list-shaping helpers for the Recent screen — kept Android-free so
 * they're testable in the fast JVM lane. Generic over T so tests can use
 * a plain data class without dragging in Room.
 */

/**
 * Drop the resume episode from a list so the Recent screen doesn't show
 * it twice (once in the "Continue listening" card, once in the main list).
 * Pass-through when [resumeId] is null.
 */
fun <T> dedupResume(items: List<T>, resumeId: Long?, idOf: (T) -> Long): List<T> =
    if (resumeId == null) items else items.filterNot { idOf(it) == resumeId }

/**
 * Parse oneplace's air-date strings ("May 8, 2026") to epoch millis for
 * stable chronological sort. Returns 0 for null/blank/unparseable input
 * — those sort to the bottom under DESC.
 *
 * The default DB sort is by airDate string DESC + episodeId DESC, which
 * works within a single year but breaks across year boundaries
 * ("December 31, 2025" sorts AFTER "January 1, 2026" alphabetically,
 * but should sort BEFORE chronologically). This helper fixes that.
 */
fun parseAirDateMillis(airDate: String?): Long {
    if (airDate.isNullOrBlank()) return 0L
    return runCatching {
        // Locale.US is non-negotiable here — oneplace ships English month
        // names ("May", "December") and a device locale of e.g. fr_FR
        // would otherwise refuse to parse them.
        SimpleDateFormat("MMMM d, yyyy", Locale.US).parse(airDate)?.time ?: 0L
    }.getOrDefault(0L)
}

/**
 * Recent-screen visibility rule + chronological sort, kept Android-free
 * (generic over T) so it can be tested in the JVM-only fast lane.
 *
 * Two layers of filtering:
 *
 *   1. Provider filter — only rows from the currently-active show
 *      (AIO/YSH) survive. The two providers' externalIds never overlap
 *      (different prefixes) so this is a clean cut.
 *
 *   2. Junk-ghost filter — drop ONLY backup-mirror ghost rows whose
 *      airDate doesn't parse to a real date. Older BrowseNasScreen
 *      `mirrorServerEpisodes()` ingests carry year-only strings ("2011")
 *      that fail to parse and pile up under "Recent" looking like noise
 *      (reported 2026-05-13).
 *
 *      KEEP ghost rows that have a parseable airDate — those are
 *      either (a) retention-pruned rows where RetentionWorker preserved
 *      the original airDate, or (b) newer NAS-mirror rows where the
 *      server carries a real date. The user wants to see "what aired
 *      last Thursday" even after retention deleted the local copy, so
 *      tap → stream-from-NAS still makes sense (reported 2026-05-22).
 *
 *      KEEP downloaded backup rows (filePath != null after a Restore)
 *      regardless of airDate shape — they're real on-phone audio now.
 *
 * Sort: parsed air-date DESC, then externalId DESC as tiebreaker.
 */
fun <T> recentItemsFor(
    eps: List<T>,
    activeShow: String,
    providerId: (T) -> String,
    filePath: (T) -> String?,
    sourceUrl: (T) -> String,
    airDate: (T) -> String?,
    externalId: (T) -> String,
): List<T> =
    eps.asSequence()
        .filter { providerId(it) == activeShow }
        .filterNot { ep ->
            val isGhost = filePath(ep) == null && sourceUrl(ep).startsWith("backup://")
            val airDateUnparseable = parseAirDateMillis(airDate(ep)) == 0L
            // Hide ONLY junk ghosts (no on-phone file AND backup-mirror
            // source AND no usable date). Ghost rows with real dates
            // stay so the user can see + stream their NAS catalog.
            isGhost && airDateUnparseable
        }
        .sortedWith(
            compareByDescending<T> { parseAirDateMillis(airDate(it)) }
                .thenByDescending(externalId),
        )
        .toList()
