package com.odyssey.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the hand-off window between two episodes of an album.
 *
 * The contract that matters is "proceed exactly once, or not at all":
 * a double-proceed restarts audio the user just cancelled, and a
 * never-proceed silently ends the album.
 */
class UpNextAnnouncerTest {

    private class FakeChime : Chime {
        var plays = 0
        override fun play() { plays++ }
    }

    @Test
    fun `announce chimes once and opens the window at the full count`() = runTest {
        val chime = FakeChime()
        val announcer = UpNextAnnouncer(chime)

        announcer.announce(this, episodeId = 1278377L, title = "A Name, Not a Number") {}
        runCurrent()

        assertEquals(1, chime.plays)
        assertEquals(
            UpNextPrompt(1278377L, "A Name, Not a Number", UP_NEXT_WINDOW_SECONDS),
            announcer.prompt.value,
        )
        announcer.cancel()
    }

    @Test
    fun `the prompt counts down one second at a time`() = runTest {
        val announcer = UpNextAnnouncer(FakeChime())
        announcer.announce(this, episodeId = 1L, title = "Next", windowSeconds = 3) {}
        runCurrent()

        assertEquals(3, announcer.prompt.value?.secondsRemaining)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(2, announcer.prompt.value?.secondsRemaining)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(1, announcer.prompt.value?.secondsRemaining)

        announcer.cancel()
    }

    @Test
    fun `the window elapsing proceeds once and clears the prompt`() = runTest {
        val announcer = UpNextAnnouncer(FakeChime())
        var proceeds = 0

        announcer.announce(this, episodeId = 1L, title = "Next", windowSeconds = 2) { proceeds++ }
        runCurrent()
        assertEquals("must not proceed while the window is open", 0, proceeds)

        advanceTimeBy(2_000L); runCurrent()
        assertEquals(1, proceeds)
        assertNull("banner must be gone before audio resumes", announcer.prompt.value)
    }

    @Test
    fun `cancel closes the window and never proceeds`() = runTest {
        val announcer = UpNextAnnouncer(FakeChime())
        var proceeds = 0

        announcer.announce(this, episodeId = 1L, title = "Next", windowSeconds = 5) { proceeds++ }
        runCurrent()
        advanceTimeBy(2_000L); runCurrent()

        announcer.cancel()
        assertNull(announcer.prompt.value)

        // Well past the original window: a cancelled countdown must stay
        // dead rather than fire late.
        advanceTimeBy(60_000L); runCurrent()
        assertEquals(0, proceeds)
    }

    @Test
    fun `a second announce supersedes the first, which never proceeds`() = runTest {
        // Happens when the user skips twice in quick succession: two
        // transitions fire, and the stale countdown must not drag
        // playback back to the episode it was counting down to.
        val chime = FakeChime()
        val announcer = UpNextAnnouncer(chime)
        val proceeded = mutableListOf<Long>()

        announcer.announce(this, episodeId = 11L, title = "First", windowSeconds = 5) {
            proceeded += 11L
        }
        runCurrent()
        advanceTimeBy(1_000L); runCurrent()

        announcer.announce(this, episodeId = 22L, title = "Second", windowSeconds = 5) {
            proceeded += 22L
        }
        runCurrent()
        assertEquals(2, chime.plays)
        assertEquals(22L, announcer.prompt.value?.episodeId)

        advanceTimeBy(5_000L); runCurrent()
        assertEquals(listOf(22L), proceeded)
    }

    @Test
    fun `a zero-length window proceeds without ever showing a prompt`() = runTest {
        val announcer = UpNextAnnouncer(FakeChime())
        var proceeds = 0

        announcer.announce(this, episodeId = 1L, title = "Next", windowSeconds = 0) { proceeds++ }
        runCurrent()

        assertEquals(1, proceeds)
        assertNull(announcer.prompt.value)
    }

    // ----- upNextTitle ------------------------------------------------

    @Test
    fun `upNextTitle passes a real title through`() {
        assertEquals("A Name, Not a Number", upNextTitle("A Name, Not a Number"))
    }

    @Test
    fun `upNextTitle falls back for null, blank and whitespace metadata`() {
        // Media3 returns null when metadata never resolved; NAS rows
        // mirrored without a title have come through blank.
        assertEquals(UP_NEXT_FALLBACK_TITLE, upNextTitle(null))
        assertEquals(UP_NEXT_FALLBACK_TITLE, upNextTitle(""))
        assertEquals(UP_NEXT_FALLBACK_TITLE, upNextTitle("   "))
    }

    @Test
    fun `upNextTitle trims surrounding whitespace`() {
        assertEquals("Mystery!", upNextTitle("  Mystery!  "))
    }
}
