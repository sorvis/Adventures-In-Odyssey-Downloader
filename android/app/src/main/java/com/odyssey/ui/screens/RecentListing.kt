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
