package com.odyssey.ui.screens

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
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
 * Callers that want a per-show strip (Recent screen does this since
 * 2026-06-14) pre-filter the [episodes] list by providerId before
 * passing it in. The helper itself stays provider-agnostic so the same
 * code path can power a cross-show strip later without a behavior fork.
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

/**
 * Day buckets for the Recent History screen. Three coarse buckets
 * rather than one header per calendar date — at the distance where
 * per-date headers start to matter, the per-row "Sep 21" chip already
 * carries it, and a wall of one-row groups reads worse than a list.
 */
enum class PlayedBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER("Earlier"),
}

/**
 * Which bucket a play at [playedAtMs] falls into relative to [nowMs].
 *
 * Compares calendar dates in [zone], not elapsed milliseconds: a play
 * at 11pm is "yesterday" at 1am, not "2 hours ago → today". [zone] and
 * [nowMs] are injected so tests don't depend on the host clock or
 * timezone.
 *
 * Future timestamps (clock skew) bucket as TODAY rather than throwing.
 */
fun playedBucket(
    playedAtMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PlayedBucket {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val played = Instant.ofEpochMilli(playedAtMs).atZone(zone).toLocalDate()
    return when {
        !played.isBefore(today) -> PlayedBucket.TODAY
        played == today.minusDays(1) -> PlayedBucket.YESTERDAY
        else -> PlayedBucket.EARLIER
    }
}

/**
 * Group [items] into day buckets, preserving input order within each
 * bucket and emitting buckets in TODAY → YESTERDAY → EARLIER order.
 * Empty buckets are omitted, so the caller can render headers blindly.
 *
 * Callers pass a newest-first list (PlaybackDao orders by updatedAt
 * DESC), which this preserves — it does not re-sort.
 *
 * Generic over T so the pure-JVM test doesn't need Room.
 */
fun <T> groupPlaysByDay(
    items: List<T>,
    playedAt: (T) -> Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<PlayedBucket, List<T>>> {
    if (items.isEmpty()) return emptyList()
    val byBucket = items.groupBy { playedBucket(playedAt(it), nowMs, zone) }
    // No takeIf(isNotEmpty) guard: groupBy never yields an empty value
    // list, so the absent-key case (mapNotNull drops it) is the only way
    // a bucket can be empty. The guard was unreachable.
    return PlayedBucket.entries.mapNotNull { bucket ->
        byBucket[bucket]?.let { bucket to it }
    }
}

/**
 * Recency buckets for the Recent tab's "Aired" view.
 *
 * The complaint this answers (2026-11-14): the tab already listed every
 * episode of the active show in air-date order, but with this week's
 * ingests and the whole back catalogue in one undifferentiated scroll,
 * "what aired recently" could not be picked out. Headers do that work.
 */
enum class AiredBucket(val label: String) {
    THIS_WEEK("This week"),
    THIS_MONTH("Earlier this month"),
    EARLIER("Earlier"),
}

/**
 * Which bucket an episode airing at [airDateMs] falls into.
 *
 * Calendar-based in [zone], like [playedBucket] — "this week" means the
 * last seven calendar days, not 604800000 milliseconds, so an episode
 * that aired seven mornings ago does not slide into the next bucket
 * partway through today.
 *
 * A future air date (a provider publishing ahead of broadcast) counts
 * as this week rather than falling through to EARLIER.
 */
fun airedBucket(
    airDateMs: Long,
    nowMs: Long,
    // No default. A ZoneId.systemDefault() default is both untestable
    // (its value differs per machine, and this dev box runs UTC while
    // the user is Eastern) and invisible at the call site, which is a
    // bad combination for date bucketing.
    zone: ZoneId,
): AiredBucket {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val aired = Instant.ofEpochMilli(airDateMs).atZone(zone).toLocalDate()
    return when {
        !aired.isBefore(today.minusDays(6)) -> AiredBucket.THIS_WEEK
        aired.year == today.year && aired.month == today.month -> AiredBucket.THIS_MONTH
        else -> AiredBucket.EARLIER
    }
}

/**
 * Group an already-air-date-sorted list under its buckets, preserving
 * the incoming order and emitting only non-empty buckets.
 *
 * Episodes with an unparseable air date ([parseAirDateMillis] == 0)
 * land in EARLIER rather than being dropped: a row with a bad date is
 * still a real episode the user may own, and silently hiding it is how
 * the old backup-ghost pile-up became confusing.
 */
fun <T> groupByAiredBucket(
    items: List<T>,
    airDateMs: (T) -> Long,
    nowMs: Long,
    zone: ZoneId,
): List<Pair<AiredBucket, List<T>>> {
    val byBucket = items.groupBy { item ->
        val ms = airDateMs(item)
        if (ms == 0L) AiredBucket.EARLIER else airedBucket(ms, nowMs, zone)
    }
    return AiredBucket.entries.mapNotNull { bucket ->
        byBucket[bucket]?.takeIf { it.isNotEmpty() }?.let { bucket to it }
    }
}

/**
 * Whether an episode airing at [airDateMs] is new since the user last
 * opened the Aired view at [seenAtMs].
 *
 * Keyed on air date rather than an ingest timestamp because the episode
 * table has no "first seen" column — adding one would mean a schema
 * migration, and "aired since I last looked" is the question the chip
 * is actually answering anyway.
 *
 * A [seenAtMs] of 0 means "never opened it", which must NOT light up
 * the entire back catalogue, so nothing is new on a first visit.
 */
fun isNewlyAired(airDateMs: Long, seenAtMs: Long): Boolean =
    seenAtMs > 0L && airDateMs > seenAtMs
