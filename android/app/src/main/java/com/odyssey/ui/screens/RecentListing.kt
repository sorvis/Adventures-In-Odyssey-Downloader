package com.odyssey.ui.screens

import com.odyssey.data.local.LocalEpisodeEntity
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
 * Recent-screen visibility rule + chronological sort, as a pure function
 * so it can be tested without Compose / Room.
 *
 * Two layers of filtering:
 *
 *   1. Provider filter — only rows from the currently-active show
 *      (AIO/YSH) survive. The two providers' externalIds never overlap
 *      (different prefixes) so this is a clean cut.
 *
 *   2. Backup-mirror ghost filter — drop rows that BrowseNasScreen's
 *      `mirrorServerEpisodes()` inserted purely to power the Albums
 *      tab's "☁ on backup" badge. Those carry `sourceUrl="backup://<id>"`
 *      with `filePath=null`, no on-phone audio, and a year-only airDate
 *      ("2011") that fails to parse — without this filter they pile up
 *      under "Recent" looking like noise to the user (reported via
 *      screenshot 2026-05-13). Backup rows that have BEEN downloaded
 *      (filePath != null after a Restore) stay visible — they're real
 *      on-phone audio now.
 *
 * Sort: parsed air-date DESC, then externalId DESC as tiebreaker.
 */
internal fun recentItemsFor(
    eps: List<LocalEpisodeEntity>,
    activeShow: String,
): List<LocalEpisodeEntity> =
    eps.asSequence()
        .filter { it.providerId == activeShow }
        .filterNot { it.filePath == null && it.sourceUrl.startsWith("backup://") }
        .sortedWith(
            compareByDescending<LocalEpisodeEntity> { parseAirDateMillis(it.airDate) }
                .thenByDescending { it.externalId },
        )
        .toList()
