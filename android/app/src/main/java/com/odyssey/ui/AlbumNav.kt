package com.odyssey.ui

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.show.YshCatalog
import com.odyssey.show.yshAlbumNameForRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a "Go to album" tap should land. [providerId] picks the route
 * family (`album/{name}` for AIO, `ysh-album/{name}` for YSH) and
 * [albumName] is the un-encoded album title — the same value both album
 * lists pass to their detail screens. OdysseyNav percent-encodes it.
 */
data class AlbumNavTarget(
    val providerId: String,
    val albumName: String,
)

/**
 * Resolves the album an episode belongs to, so the now-playing screen
 * and episode rows can offer a jump-to-album affordance.
 *
 *   AIO — title-join against the bundled catalog; the matched album's
 *         name is exactly the `album/{albumKey}` route arg.
 *   YSH — the album name persisted on the row at ingest, falling back
 *         to a catalog lookup by skuId for rows that predate
 *         album-at-ingest. See [yshAlbumNameForRow].
 *
 * Returns null when no album can be resolved (e.g. an AIO oneplace
 * title with no catalog match, or a YSH row whose catalog entry hasn't
 * loaded yet) — callers hide the affordance in that case.
 */
@Singleton
class AlbumNavResolver @Inject constructor(
    private val aio: AioCatalogRepo,
    private val ysh: YshCatalog,
) {
    fun targetFor(ep: LocalEpisodeEntity): AlbumNavTarget? = when (ep.providerId) {
        "aio" -> aio.match(ep.title)?.album?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { AlbumNavTarget("aio", it) }
        "ysh" -> yshAlbumNameForRow(ep, ysh.state.value)
            ?.let { AlbumNavTarget("ysh", it) }
        else -> null
    }
}
