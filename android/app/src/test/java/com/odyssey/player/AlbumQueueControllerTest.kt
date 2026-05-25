package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumQueueControllerTest {

    private val aQ = AlbumQueueController()

    private fun aio(id: Long) = AlbumQueueEntry(
        episodeId = id, providerId = "aio", externalId = id.toString(),
    )

    private fun ysh(externalId: String): AlbumQueueEntry {
        val hashed = externalId.hashCode().toLong()
        return AlbumQueueEntry(episodeId = hashed, providerId = "ysh", externalId = externalId)
    }

    @Test
    fun `empty queue returns null`() {
        assertNull(aQ.nextAfter(42L))
    }

    @Test
    fun `nextAfter returns the entry after current`() {
        aQ.setQueue(listOf(aio(100), aio(101), aio(102)))
        assertEquals(aio(101), aQ.nextAfter(100))
        // nextAfter is pure — repeated calls from the same current id
        // return the same successor. Queue advances based on the
        // currently-playing track id, not an internal cursor.
        assertEquals(aio(101), aQ.nextAfter(100))
    }

    @Test
    fun `nextAfter returns null at end of album AND clears the queue`() {
        aQ.setQueue(listOf(aio(100), aio(101)))
        assertEquals(aio(101), aQ.nextAfter(100))
        assertNull("end of album → null", aQ.nextAfter(101))
        // Side effect: queue is cleared so a subsequent STATE_ENDED on
        // an unrelated (non-album) track doesn't accidentally
        // re-enter this stale queue.
        assertTrue("queue cleared after end-of-album", aQ.queue.value.isEmpty())
    }

    @Test
    fun `nextAfter returns null when current track isn't in the queue`() {
        // Real-world: user navigated away from the album, tapped a
        // standalone Recent-tab track. STATE_ENDED for that track must
        // NOT auto-advance into the album queue.
        aQ.setQueue(listOf(aio(100), aio(101)))
        assertNull(aQ.nextAfter(999))
        // Queue stays intact — the next play from album detail would
        // still resume the queue correctly.
        assertEquals(2, aQ.queue.value.size)
    }

    @Test
    fun `setQueue replaces, doesn't append`() {
        aQ.setQueue(listOf(aio(100), aio(101)))
        aQ.setQueue(listOf(aio(200), aio(201), aio(202)))
        assertEquals(3, aQ.queue.value.size)
        assertEquals(aio(201), aQ.nextAfter(200))
        assertNull("100 belonged to the OLD queue, should not resolve", aQ.nextAfter(100))
    }

    @Test
    fun `clear empties the queue`() {
        aQ.setQueue(listOf(aio(100), aio(101)))
        aQ.clear()
        assertTrue(aQ.queue.value.isEmpty())
        assertNull(aQ.nextAfter(100))
    }

    @Test
    fun `YSH entries work the same -- hash-fallback episodeIds compare correctly`() {
        // YSH externalIds are non-numeric strings like "ysh-sku-1958".
        // The queue stores LocalEpisodeEntity.episodeId (the computed
        // hash fallback) so the player's mediaId-derived currentEpisodeId
        // matches what the queue holds.
        val a = ysh("ysh-sku-1000")
        val b = ysh("ysh-sku-1001")
        val c = ysh("ysh-sku-1002")
        aQ.setQueue(listOf(a, b, c))
        assertEquals(b, aQ.nextAfter(a.episodeId))
        assertEquals(c, aQ.nextAfter(b.episodeId))
        assertNull(aQ.nextAfter(c.episodeId))
    }

    @Test
    fun `mixed-provider queue does not happen in practice but is structurally fine`() {
        // Defense: AlbumDetailVm primes AIO-only, YshAlbumDetailVm
        // primes YSH-only. The queue itself doesn't validate
        // homogeneity. If a caller mixes providers, lookups still
        // resolve by episodeId — fine.
        val a = aio(500)
        val b = ysh("ysh-sku-9999")
        aQ.setQueue(listOf(a, b))
        assertEquals(b, aQ.nextAfter(a.episodeId))
    }
}
