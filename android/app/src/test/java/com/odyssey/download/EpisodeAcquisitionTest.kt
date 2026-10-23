package com.odyssey.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeAcquisitionTest {

    @Test
    fun `an archived episode with a NAS configured comes from backup`() {
        assertEquals(
            AcquireSource.BACKUP,
            acquisitionSourceFor(archivedAt = 1_700_000_000_000L, nasConfigured = true),
        )
    }

    @Test
    fun `a never-archived episode falls back to the CDN`() {
        // This is the case the AIO album screen got wrong: it enqueued
        // a backup restore unconditionally, so the button did nothing
        // visible for an episode the server has never seen.
        assertEquals(
            AcquireSource.CDN,
            acquisitionSourceFor(archivedAt = null, nasConfigured = true),
        )
    }

    @Test
    fun `no NAS configured falls back to the CDN even when archived`() {
        // archivedAt can be set from a previous install or a NAS that
        // has since been removed from settings.
        assertEquals(
            AcquireSource.CDN,
            acquisitionSourceFor(archivedAt = 1_700_000_000_000L, nasConfigured = false),
        )
    }

    @Test
    fun `startsImmediately is true off metered, or on metered when opted in`() {
        assertTrue(startsImmediately(onMeteredNetwork = false, allowMetered = false))
        assertTrue(startsImmediately(onMeteredNetwork = true, allowMetered = true))
        assertFalse(startsImmediately(onMeteredNetwork = true, allowMetered = false))
    }

    @Test
    fun `messages name the episode when work starts now`() {
        assertEquals(
            "Pulling from backup: A Name, Not a Number",
            acquisitionMessage("A Name, Not a Number", AcquireSource.BACKUP, startsNow = true),
        )
        assertEquals(
            "Download started: A Name, Not a Number",
            acquisitionMessage("A Name, Not a Number", AcquireSource.CDN, startsNow = true),
        )
    }

    @Test
    fun `every source yields a distinct, non-blank message in both timing states`() {
        // Exhaustive over the enum on purpose: a third source (a peer
        // phone, an SD card import) must not be addable without
        // deciding what the user gets told about it. A duplicate
        // message would mean two different things look identical in
        // the snackbar.
        val messages = AcquireSource.entries.flatMap { source ->
            listOf(true, false).map { now -> acquisitionMessage("Ep", source, now) }
        }
        assertEquals(AcquireSource.entries.size * 2, messages.toSet().size)
        assertTrue("no message may be blank", messages.none { it.isBlank() })
    }

    @Test
    fun `messages drop the title when the work is waiting for WiFi`() {
        // The message is about the wait, not the episode; a long title
        // buries the part that matters.
        assertEquals(
            "Pull queued — will start on WiFi",
            acquisitionMessage("A Name, Not a Number", AcquireSource.BACKUP, startsNow = false),
        )
        assertEquals(
            "Download queued — will start on WiFi",
            acquisitionMessage("A Name, Not a Number", AcquireSource.CDN, startsNow = false),
        )
    }
}
