package com.odyssey.data.local

/**
 * One-shot repair for playback positions written before v0.1.91.
 *
 * Until then, PlayerController keyed every position it wrote
 * `("aio", derivedEpisodeId)` no matter which show was playing, so the
 * whole of a user's Your Story Hour listening history sits in
 * Adventures in Odyssey's history and YSH's own reads empty. Fixing the
 * write path stops the bleeding but leaves the existing rows wrong, and
 * they would only correct themselves as each episode happened to be
 * played again.
 *
 * The repair is a pure plan over keys so it can be reasoned about and
 * tested without a database.
 */

/** A corrective action on one `playback_positions` row. */
sealed interface RekeyAction {
    val from: EpisodeKey

    /** Move the row to the show it actually belongs to. */
    data class Rekey(override val from: EpisodeKey, val to: EpisodeKey) : RekeyAction

    /**
     * Delete the row: the correctly-keyed position already exists, so
     * this one is a strictly-older duplicate of it. Happens when the
     * same episode was played both before and after the upgrade.
     */
    data class DropRedundant(override val from: EpisodeKey) : RekeyAction
}

/**
 * Which `playback_positions` rows are mis-keyed YSH plays, and what to
 * do about each.
 *
 * @param positions every key currently in the table.
 * @param aioExternalIds external ids of the AIO rows in `local_episodes`.
 * @param yshExternalIdsByDerivedId unambiguous derived-id → YSH external
 *        id map; see [unambiguousDerivedIds].
 *
 * Deliberately conservative — a row is left alone unless it can be
 * shown to belong elsewhere:
 *
 *  - Rows that already name a non-AIO provider were written by the
 *    fixed code and are correct.
 *  - A row whose external id matches a real AIO episode is treated as a
 *    genuine AIO play, **even if a YSH episode derives the same Long**.
 *    That collision is indistinguishable after the fact, and keeping
 *    the AIO reading preserves the status quo rather than gambling with
 *    real listening history.
 *  - A row that maps to no episode at all (an orphan, e.g. the episode
 *    was hard-deleted by the no-NAS retention path) is left in place.
 *    The Recent screens already tolerate orphans.
 */
fun planPlaybackKeyRepair(
    positions: List<EpisodeKey>,
    aioExternalIds: Set<String>,
    yshExternalIdsByDerivedId: Map<Long, String>,
): List<RekeyAction> {
    val existing = positions.toSet()
    return positions.mapNotNull { key ->
        if (key.providerId != "aio") return@mapNotNull null
        if (key.externalId in aioExternalIds) return@mapNotNull null
        val derived = key.externalId.toLongOrNull() ?: return@mapNotNull null
        val yshExternalId = yshExternalIdsByDerivedId[derived] ?: return@mapNotNull null
        val target = EpisodeKey("ysh", yshExternalId)
        if (target in existing) RekeyAction.DropRedundant(key) else RekeyAction.Rekey(key, target)
    }
}

/**
 * derived-id → external-id, keeping only ids that are the sole owner of
 * their derived Long.
 *
 * A hash collision would make the mapping a coin flip, and this repair
 * moves real listening history — so an ambiguous id is dropped and its
 * row left untouched.
 */
fun unambiguousDerivedIds(externalIds: List<String>): Map<Long, String> =
    externalIds.groupBy { derivedEpisodeId(it) }
        .filterValues { it.size == 1 }
        .mapValues { (_, ids) -> ids.first() }
