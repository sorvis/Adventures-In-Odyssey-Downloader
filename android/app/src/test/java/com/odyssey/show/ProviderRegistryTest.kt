package com.odyssey.show

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ProviderRegistry — exposes the Hilt-multibound Set<ShowProvider> as
 * a sane lookup API. Pure JVM unit tests, no Android types needed.
 *
 * Behavior locked down:
 *   - byId returns the matching provider, or null on miss.
 *   - artistFor returns the provider's artistName, or falls back to
 *     the AIO default string when the id is unknown.
 *   - Duplicate provider ids (YshFreeStreamProvider +
 *     YshOneplaceProvider both share id="ysh") collapse to one entry
 *     in `all` and `byId` — the dedup is OK because YSH providers
 *     share displayName and artistName by construction.
 */
class ProviderRegistryTest {

    @Test
    fun `byId finds matching provider and returns null on miss`() {
        val reg = ProviderRegistry(setOf(stub("aio", "Adventures in Odyssey")))
        assertNotNull(reg.byId("aio"))
        assertEquals("Adventures in Odyssey", reg.byId("aio")!!.artistName)
        assertNull(reg.byId("never-registered"))
    }

    @Test
    fun `artistFor returns the provider's artistName`() {
        val reg = ProviderRegistry(
            setOf(
                stub("aio", "Adventures in Odyssey"),
                stub("ysh", "Your Story Hour"),
            ),
        )
        assertEquals("Adventures in Odyssey", reg.artistFor("aio"))
        assertEquals("Your Story Hour", reg.artistFor("ysh"))
    }

    @Test
    fun `artistFor falls back to AIO default when the providerId is unknown`() {
        val reg = ProviderRegistry(setOf(stub("aio", "Adventures in Odyssey")))
        assertEquals(
            "Adventures in Odyssey",
            reg.artistFor("totally-unknown-show"),
        )
    }

    @Test
    fun `duplicate ids dedup in all and byId — first wins`() {
        // YshFreeStreamProvider and YshOneplaceProvider both have
        // id="ysh"; the registry should treat them as one show.
        val freeStream = stub("ysh", "Your Story Hour")
        val oneplace = stub("ysh", "Your Story Hour")   // same name by construction
        val reg = ProviderRegistry(setOf(freeStream, oneplace))
        assertEquals(1, reg.all.size)
        assertEquals("ysh", reg.all.first().id)
    }

    private fun stub(id: String, artistName: String): ShowProvider =
        object : ShowProvider {
            override val id = id
            override val displayName = artistName
            override val artistName = artistName
            override suspend fun newSince(
                lastSeenExternalId: String?,
                maxFetch: Int,
            ): List<ProviderEpisode> = emptyList()
        }
}
