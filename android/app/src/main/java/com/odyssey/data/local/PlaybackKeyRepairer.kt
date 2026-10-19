package com.odyssey.data.local

import com.odyssey.app.SettingsRepo
import com.odyssey.debug.DebugLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [planPlaybackKeyRepair] once, at launch.
 *
 * Thin on purpose — the decisions all live in the pure planner, which
 * is unit-tested. This class only reads the two tables, hands them
 * over, and executes the resulting actions.
 */
@Singleton
class PlaybackKeyRepairer @Inject constructor(
    private val episodes: EpisodeDao,
    private val playback: PlaybackDao,
    private val settings: SettingsRepo,
) {

    suspend fun repairIfNeeded() {
        if (settings.flow.first().playbackKeysRepaired) return
        val succeeded = runCatching { repair() }
            .onFailure { DebugLogger.e(TAG, "repair failed; will retry next launch", it) }
            .isSuccess
        // Only burn the sentinel on success. A transient DB error must
        // not skip the repair permanently.
        if (succeeded) settings.setPlaybackKeysRepaired(true)
    }

    private suspend fun repair() {
        val rows = episodes.observeAll().first()
        val positions = playback.allPositions()
        val plan = planPlaybackKeyRepair(
            positions = positions.map { EpisodeKey(it.providerId, it.externalId) },
            aioExternalIds = rows.filter { it.providerId == "aio" }
                .mapTo(mutableSetOf()) { it.externalId },
            yshExternalIdsByDerivedId = unambiguousDerivedIds(
                rows.filter { it.providerId == "ysh" }.map { it.externalId },
            ),
        )
        if (plan.isEmpty()) {
            DebugLogger.d(TAG, "no mis-keyed playback rows")
            return
        }
        val byKey = positions.associateBy { EpisodeKey(it.providerId, it.externalId) }
        var moved = 0
        var dropped = 0
        for (action in plan) {
            val row = byKey[action.from] ?: continue
            when (action) {
                is RekeyAction.Rekey -> {
                    // Write the new row BEFORE deleting the old one. If
                    // the process dies in between we are left with a
                    // duplicate, and the next launch sees the correct
                    // row already present and drops the legacy one —
                    // whereas deleting first would lose the position.
                    playback.upsert(
                        row.copy(
                            providerId = action.to.providerId,
                            externalId = action.to.externalId,
                        ),
                    )
                    playback.deleteByKey(action.from.providerId, action.from.externalId)
                    moved++
                }
                is RekeyAction.DropRedundant -> {
                    playback.deleteByKey(action.from.providerId, action.from.externalId)
                    dropped++
                }
            }
        }
        DebugLogger.i(TAG, "playback keys repaired: $moved moved to ysh, $dropped stale duplicates dropped")
    }

    private companion object {
        const val TAG = "PlaybackKeyRepairer"
    }
}
