package com.odyssey.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the media-id encoding that carries provider identity through
 * Media3.
 *
 * The bug this prevents: every playback position written by the player
 * was keyed `("aio", <long>)` regardless of which show it came from,
 * because a bare-Long media id carries no provider and the write path
 * defaulted. Your Story Hour plays landed in the Adventures in Odyssey
 * history and YSH's own history read empty.
 */
class EpisodeKeyTest {

    @Test
    fun `round-trips an AIO key`() {
        val id = encodeMediaId("aio", "1278377")
        assertEquals(EpisodeKey("aio", "1278377"), decodeMediaId(id))
    }

    @Test
    fun `round-trips a YSH key whose external id is not numeric`() {
        val id = encodeMediaId("ysh", "ysh-sku-1958")
        assertEquals(EpisodeKey("ysh", "ysh-sku-1958"), decodeMediaId(id))
    }

    @Test
    fun `only the first separator splits, so an external id may contain one`() {
        // Nothing guarantees a future provider's ids are colon-free.
        // Splitting on the last (or on every) separator would silently
        // corrupt the key instead of failing loudly.
        val id = encodeMediaId("rss", "https://example.com/ep/7")
        assertEquals(EpisodeKey("rss", "https://example.com/ep/7"), decodeMediaId(id))
    }

    @Test
    fun `a legacy bare-Long media id decodes to null rather than guessing a provider`() {
        // Items already loaded in a player that outlived the upgrade.
        // Returning null forces the caller to deal with it; inventing
        // "aio" here is exactly the original defect.
        assertNull(decodeMediaId("1278377"))
    }

    @Test
    fun `malformed media ids decode to null`() {
        assertNull(decodeMediaId(null))
        assertNull(decodeMediaId(""))
        assertNull(decodeMediaId(":1278377"))   // no provider
        assertNull(decodeMediaId("aio:"))       // no external id
    }

    @Test
    fun `episodeIdFromMediaId accepts both the encoded and the legacy form`() {
        assertEquals(1278377L, episodeIdFromMediaId(encodeMediaId("aio", "1278377")))
        assertEquals(1278377L, episodeIdFromMediaId("1278377"))
        assertNull(episodeIdFromMediaId(null))
        assertNull(episodeIdFromMediaId("not-a-number"))
    }

    @Test
    fun `episodeIdFromMediaId hashes a non-numeric external id the same way the entity does`() {
        // Must agree with LocalEpisodeEntity.episodeId or row-level UI
        // state (expanded, progress, is-this-row-playing) attaches to
        // the wrong row.
        val external = "ysh-sku-1958"
        assertEquals(
            derivedEpisodeId(external),
            episodeIdFromMediaId(encodeMediaId("ysh", external)),
        )
    }

    @Test
    fun `storageKeyFor uses the encoded key when there is one`() {
        assertEquals(
            EpisodeKey("ysh", "ysh-sku-1958"),
            storageKeyFor(encodeMediaId("ysh", "ysh-sku-1958"), derivedEpisodeId("ysh-sku-1958")),
        )
    }

    @Test
    fun `storageKeyFor falls back to AIO only for a legacy bare-Long media id`() {
        // Reproduces the pre-v0.1.91 row for an item still loaded in a
        // player that outlived the upgrade, rather than creating a new
        // one under a provider we cannot actually determine.
        assertEquals(EpisodeKey("aio", "1278377"), storageKeyFor("1278377", 1278377L))
        assertEquals(EpisodeKey("aio", "1278377"), storageKeyFor(null, 1278377L))
    }

    @Test
    fun `derivedEpisodeId is numeric for AIO and a stable hash otherwise`() {
        assertEquals(1278377L, derivedEpisodeId("1278377"))
        assertEquals(derivedEpisodeId("ysh-sku-1958"), derivedEpisodeId("ysh-sku-1958"))
    }

    @Test
    fun `two shows can derive the same Long, which is why positions are keyed on the pair`() {
        // A numeric YSH sku id collides head-on with an AIO CMS id of
        // the same number. The Long is therefore unusable as a storage
        // key, and this test exists to stop anyone "simplifying" back
        // to it.
        assertEquals(derivedEpisodeId("1958"), derivedEpisodeId("1958"))
        assertEquals(
            episodeIdFromMediaId(encodeMediaId("ysh", "1958")),
            episodeIdFromMediaId(encodeMediaId("aio", "1958")),
        )
        // ...while the keys stay distinct.
        assertEquals(EpisodeKey("ysh", "1958"), decodeMediaId(encodeMediaId("ysh", "1958")))
        assertEquals(EpisodeKey("aio", "1958"), decodeMediaId(encodeMediaId("aio", "1958")))
    }
}
