package com.odyssey.ui.screens

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

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
/**
 * Project rows the user has actually played, newest-first, capped at
 * [maxItems] and EXCLUDING the [excludeEpisodeId] (which is the row
 * already surfaced in the "Continue listening" card above the list —
 * showing it again here would duplicate).
 *
 * [positions] is expected to be ordered by `updatedAt` DESC (PlaybackDao's
 * query takes care of that); the helper re-sorts defensively in case a
 * caller passes an unordered list, since the visual order is what users
 * see.
 *
 * Cross-show on purpose: this list reflects the user's listening history,
 * which spans whatever they've actually tapped — the main Recent list's
 * provider filter (AIO vs YSH) deliberately does NOT apply here. The
 * user explicitly asked for this in the 2026-05-31 design conversation
 * ("maybe there's a couple different ones") — they want to bounce
 * between shows from this strip.
 *
 * Generic over T + the position type so the pure-JVM unit test doesn't
 * need to drag in Room.
 */
fun <T, P> recentlyPlayedFor(
    episodes: List<T>,
    positions: List<P>,
    excludeEpisodeId: Long?,
    maxItems: Int,
    episodeId: (T) -> Long,
    positionEpisodeId: (P) -> Long,
    updatedAt: (P) -> Long,
): List<T> {
    if (maxItems <= 0 || positions.isEmpty() || episodes.isEmpty()) return emptyList()
    val episodeIndex = episodes.associateBy(episodeId)
    return positions.asSequence()
        .sortedByDescending(updatedAt)
        .mapNotNull { pos ->
            val id = positionEpisodeId(pos)
            if (id == excludeEpisodeId) null else episodeIndex[id]
        }
        .distinctBy(episodeId)
        .take(maxItems)
        .toList()
}

/**
 * Render an "X ago" style relative timestamp for the recently-played
 * row trailing slot. [updatedAtMs] is the position's last-touched
 * epoch ms; [nowMs] is injected so tests don't depend on the system
 * clock.
 *
 * Bucketing:
 *   <60s   → "just now"
 *   <60m   → "Nm ago"
 *   <24h   → "Nh ago"
 *   <7d    → "Nd ago"
 *   else   → "MMM d" (e.g. "May 21") — month/day is enough at that
 *            distance, year omitted to keep the chip narrow
 *
 * Returns "" for negative deltas (clock skew or test injection).
 */
fun formatRelativePlayedAt(updatedAtMs: Long, nowMs: Long): String {
    val delta = nowMs - updatedAtMs
    if (delta < 0L) return ""
    val seconds = TimeUnit.MILLISECONDS.toSeconds(delta)
    if (seconds < 60L) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    if (minutes < 60L) return "${minutes}m ago"
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    if (hours < 24L) return "${hours}h ago"
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    if (days < 7L) return "${days}d ago"
    return SimpleDateFormat("MMM d", Locale.US).format(java.util.Date(updatedAtMs))
}

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
