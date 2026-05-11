package com.odyssey.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression test for the v0.1.37 crash: `LocalEpisodeEntity.episodeId`
 * (and the mirror on PlaybackPositionEntity) used to call
 * `externalId.toLong()` directly, which threw NumberFormatException on
 * YSH externalIds like "ysh-sku-1958". The Recent/Downloaded LazyColumn
 * key extractor + every per-row state lookup hit that getter, so the
 * app crashed on hitting Refresh once YSH was active and YSH rows had
 * been ingested.
 *
 * Pure JVM unit test — exercises the getter directly so a future
 * regression can never re-introduce the throw.
 */
class EntityEpisodeIdGetterTest {

    @Test
    fun `LocalEpisodeEntity episodeId returns the parsed Long for AIO numeric externalId`() {
        val row = aioRow("1278294")
        assertEquals(1278294L, row.episodeId)
    }

    @Test
    fun `LocalEpisodeEntity episodeId does NOT throw on YSH non-numeric externalId`() {
        val row = yshRow("ysh-sku-1958")
        // Must not throw. Value comes from hashCode().
        val id = row.episodeId
        assertEquals("ysh-sku-1958".hashCode().toLong(), id)
    }

    @Test
    fun `episodeId is stable across calls for the same externalId`() {
        val a = yshRow("ysh-sku-1958").episodeId
        val b = yshRow("ysh-sku-1958").episodeId
        assertEquals(a, b)
    }

    @Test
    fun `episodeId differs for different YSH externalIds`() {
        // Stable + distinct-per-row enough that LazyColumn keys don't
        // collide for a catalog of ~1000 tracks.
        val a = yshRow("ysh-sku-1958").episodeId
        val b = yshRow("ysh-sku-2740").episodeId
        assertNotEquals(a, b)
    }

    @Test
    fun `PlaybackPositionEntity episodeId mirrors the same fallback`() {
        val aio = PlaybackPositionEntity("aio", "1278294", 0L, 0L, 0L, null)
        val ysh = PlaybackPositionEntity("ysh", "ysh-sku-1958", 0L, 0L, 0L, null)
        assertEquals(1278294L, aio.episodeId)
        // Must not throw; identical formula to LocalEpisodeEntity.
        assertEquals("ysh-sku-1958".hashCode().toLong(), ysh.episodeId)
    }

    private fun aioRow(externalId: String) = LocalEpisodeEntity(
        providerId = "aio",
        externalId = externalId,
        title = "any",
        airDate = null,
        description = null,
        sourceUrl = "x",
        downloadUrl = "y",
        filePath = null,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
    )

    private fun yshRow(externalId: String) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = externalId,
        title = "any",
        airDate = null,
        description = null,
        sourceUrl = "x",
        downloadUrl = "y",
        filePath = null,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
    )
}
