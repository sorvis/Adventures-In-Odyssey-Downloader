package com.odyssey.player

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.show.YshCatalog
import com.odyssey.show.yshAlbumNameForRow
import com.odyssey.ui.AlbumNavResolver
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the auto-advance queue for whatever album an episode belongs
 * to, so playback rolls into the next track no matter where the play
 * started.
 *
 * [AlbumQueueController] and the STATE_ENDED hook in PlayerController
 * already did the advancing; the gap was that only the two album-detail
 * screens ever called `setQueue`. Every other entry point — Recent tab,
 * Recent History, Downloaded — played a standalone track, so
 * `nextAfter()` found no queue and silently stopped. Observed
 * 2026-09-28: episode 354 resumed at 25.5 min, ENDED 8 ms later, and
 * the user had to find 355 by hand in the album.
 *
 * Priming lives here (called from the VMs' play paths) rather than
 * inside PlayerController, so the player layer stays ignorant of albums
 * and catalogs. Steven's call, 2026-09-30.
 *
 * Ordering mirrors the album screens:
 *   AIO — catalog order, via each row's position in its matched
 *         [com.odyssey.catalog.AioAlbum.episodes].
 *   YSH — `albumTrackOrder` persisted on the row at ingest.
 *
 * Every DB row for the album is included. A row with no local file is
 * still playable (ghosts carry `backup://` and stream from the NAS), so
 * filtering on filePath would truncate the queue after retention prunes.
 */
@Singleton
class AlbumQueuePrimer @Inject constructor(
    private val episodes: EpisodeDao,
    private val resolver: AlbumNavResolver,
    private val aio: AioCatalogRepo,
    private val ysh: YshCatalog,
    private val albumQueue: AlbumQueueController,
) {

    /**
     * Build + install the queue for [ep]'s album. Returns how many
     * entries were installed (0 when the album can't be resolved).
     *
     * Clears the queue on an unresolvable album rather than leaving the
     * previous one in place: [AlbumQueueController.nextAfter] keys off
     * the ended episode being present, so a stale queue would be
     * harmless — but an explicit clear keeps "what's queued" honest for
     * anything that observes it.
     */
    suspend fun primeFor(ep: LocalEpisodeEntity): Int {
        val target = resolver.targetFor(ep)
        if (target == null) {
            DebugLogger.d(
                "AlbumQueuePrimer",
                "no album for ${ep.providerId}:${ep.externalId} \"${ep.title}\" — queue cleared",
            )
            albumQueue.setQueue(emptyList())
            return 0
        }
        val siblings = episodes.observeAll().first().filter { it.providerId == ep.providerId }
        val ordered = when (ep.providerId) {
            "aio" -> orderAio(siblings, target.albumName)
            "ysh" -> orderYsh(siblings, target.albumName)
            else -> emptyList()
        }
        albumQueue.setQueue(
            ordered.map { AlbumQueueEntry(it.episodeId, it.providerId, it.externalId) },
        )
        DebugLogger.d(
            "AlbumQueuePrimer",
            "primed queue size=${ordered.size} start=${ep.episodeId} album=\"${target.albumName}\"",
        )
        return ordered.size
    }

    /**
     * Catalog order. Rows are matched by title (the same join the album
     * screen and "Go to album" use); unmatched rows and rows from other
     * albums drop out. A matched row whose episode can't be located
     * inside its own album's list sorts last rather than being dropped —
     * better to play it late than to lose it from the queue.
     */
    private fun orderAio(rows: List<LocalEpisodeEntity>, albumName: String) =
        rows.mapNotNull { row ->
            val m = aio.match(row.title) ?: return@mapNotNull null
            if (m.album.name != albumName) null else row to m
        }.sortedBy { (_, m) ->
            m.album.episodes
                .indexOfFirst { it.shortName == m.episode.shortName && it.name == m.episode.name }
                .let { if (it < 0) Int.MAX_VALUE else it }
        }.map { it.first }

    /**
     * Track order as persisted at ingest. externalId is the tiebreaker
     * so rows that predate album-at-ingest (null order) still land in a
     * stable, repeatable position instead of shuffling per query.
     */
    private fun orderYsh(rows: List<LocalEpisodeEntity>, albumName: String) =
        rows.filter { yshAlbumNameForRow(it, ysh.state.value) == albumName }
            .sortedWith(
                compareBy<LocalEpisodeEntity> { it.albumTrackOrder ?: Int.MAX_VALUE }
                    .thenBy { it.externalId },
            )
}
