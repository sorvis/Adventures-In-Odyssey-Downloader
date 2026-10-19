package com.odyssey.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repair moves real listening history, so these tests are mostly
 * about what it must NOT touch.
 */
class PlaybackKeyRepairTest {

    private val yshIds = listOf("ysh-sku-1958", "ysh-sku-1959")
    private val yshMap = unambiguousDerivedIds(yshIds)

    @Test
    fun `a mis-keyed YSH play is moved to the YSH key`() {
        val misKeyed = EpisodeKey("aio", derivedEpisodeId("ysh-sku-1958").toString())
        val plan = planPlaybackKeyRepair(
            positions = listOf(misKeyed),
            aioExternalIds = setOf("1278377"),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertEquals(
            listOf(RekeyAction.Rekey(misKeyed, EpisodeKey("ysh", "ysh-sku-1958"))),
            plan,
        )
    }

    @Test
    fun `a genuine AIO play is left alone`() {
        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("aio", "1278377")),
            aioExternalIds = setOf("1278377"),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `rows already naming a provider are left alone`() {
        // Written by the fixed code; re-examining them would be a way
        // to break something that is already right.
        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("ysh", "ysh-sku-1958")),
            aioExternalIds = emptySet(),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a collision with a real AIO episode keeps the AIO reading`() {
        // A numeric YSH sku id collides head-on with an AIO CMS id of
        // the same number. After the fact the two are indistinguishable,
        // so the status quo wins rather than gambling with history.
        val collidingMap = unambiguousDerivedIds(listOf("1958"))
        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("aio", "1958")),
            aioExternalIds = setOf("1958"),
            yshExternalIdsByDerivedId = collidingMap,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `an orphan row that maps to no episode is left in place`() {
        // The episode row was hard-deleted by the no-NAS retention path
        // while its position survived. The Recent screens already
        // tolerate orphans; deleting history here would be worse.
        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("aio", "999999999")),
            aioExternalIds = setOf("1278377"),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a non-numeric legacy external id is left in place`() {
        // Cannot have been produced by derivedEpisodeId, so there is
        // nothing to map it through.
        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("aio", "not-a-number")),
            aioExternalIds = emptySet(),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `when the correct row already exists the stale one is dropped, not merged`() {
        // Same episode played before and after the upgrade. The
        // correctly-keyed row is strictly newer, so the legacy row is
        // redundant — and leaving it would keep the episode showing in
        // AIO's history forever.
        val misKeyed = EpisodeKey("aio", derivedEpisodeId("ysh-sku-1958").toString())
        val correct = EpisodeKey("ysh", "ysh-sku-1958")
        val plan = planPlaybackKeyRepair(
            positions = listOf(misKeyed, correct),
            aioExternalIds = emptySet(),
            yshExternalIdsByDerivedId = yshMap,
        )
        assertEquals(listOf(RekeyAction.DropRedundant(misKeyed)), plan)
    }

    @Test
    fun `an ambiguous derived id is dropped from the map and its row untouched`() {
        // Two external ids hashing to the same Long makes the mapping a
        // coin flip. unambiguousDerivedIds excludes them.
        val a = "ysh-sku-1958"
        val collidingPair = listOf(a, a + "-dup")
        val map = unambiguousDerivedIds(collidingPair)
        // Sanity: both are present because they do NOT actually collide.
        assertEquals(2, map.size)

        // Force the ambiguous case explicitly rather than hunting for a
        // real hash collision.
        val forced = unambiguousDerivedIds(listOf("1958", "1958"))
        assertTrue("duplicate ids must not be mappable", forced.isEmpty())

        val plan = planPlaybackKeyRepair(
            positions = listOf(EpisodeKey("aio", "1958")),
            aioExternalIds = emptySet(),
            yshExternalIdsByDerivedId = forced,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `an empty table plans nothing`() {
        assertTrue(
            planPlaybackKeyRepair(emptyList(), emptySet(), yshMap).isEmpty(),
        )
    }
}
