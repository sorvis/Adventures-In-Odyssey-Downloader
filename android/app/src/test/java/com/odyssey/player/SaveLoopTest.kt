package com.odyssey.player

import android.app.Application
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Simulate playing an episode for 15 seconds" — virtual-time test for
 * [savePeriodicallyOnMain], the loop that drives PlayerController's
 * position-persisting save job.
 *
 * Background: v0.1.12 crashed about 5 seconds into playback because
 * the loop ran on Dispatchers.Default and read MediaController state
 * (which has thread affinity) → IllegalStateException → uncaught →
 * process death. The fix moved the loop to Main and added per-iteration
 * try/catch.
 *
 * These tests pin the contract:
 *   - Loop fires every 5s of (virtual) playback.
 *   - 15s of playback produces exactly 3 ticks (5s, 10s, 15s).
 *   - An exception thrown inside one iteration does NOT terminate the
 *     loop — subsequent iterations continue to fire. (This is what
 *     keeps the app alive even if MediaController happens to throw.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SaveLoopTest {

    @Before
    fun setUp() {
        // viewModelScope-equivalent: Dispatchers.Main backed by a
        // virtual-time test dispatcher we can advance manually.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `15 seconds of playback fires the save loop exactly 3 times`() = runTest {
        var calls = 0
        val handler = CoroutineExceptionHandler { _, _ -> /* shouldn't fire */ }
        val job = savePeriodicallyOnMain(
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + handler),
            intervalMs = 5_000L,
        ) { calls++ }

        // Simulate playback. Need to advance the test scheduler enough to
        // pump the launched coroutine onto the dispatcher first, then
        // advance virtual time across three save-interval boundaries.
        runCurrent()
        advanceTimeBy(15_500)
        runCurrent()

        assertEquals("save loop should fire at 5s, 10s, 15s", 3, calls)
        job.cancel()
    }

    @Test
    fun `loop survives an exception in one iteration and continues`() = runTest {
        var calls = 0
        var loggedThrowable: Throwable? = null
        val handler = CoroutineExceptionHandler { _, t ->
            // Should NOT fire — per-iteration try/catch in
            // savePeriodicallyOnMain catches throws before they bubble.
            loggedThrowable = t
        }
        val job = savePeriodicallyOnMain(
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + handler),
            intervalMs = 5_000L,
        ) {
            calls++
            // Tick 2 simulates MediaController throwing
            // IllegalStateException("Player accessed on wrong thread") —
            // exactly the v0.1.12 crash signature.
            if (calls == 2) error("simulated controller-affinity throw")
        }

        runCurrent()
        advanceTimeBy(15_500)
        runCurrent()

        assertEquals(
            "loop must keep firing after a throw — expect 3 calls in 15s even with iteration 2 failing",
            3, calls,
        )
        assertNull("CoroutineExceptionHandler should NOT see anything — try/catch caught it", loggedThrowable)
        job.cancel()
    }

    @Test
    fun `cancelling the job stops the loop immediately`() = runTest {
        var calls = 0
        val handler = CoroutineExceptionHandler { _, _ -> }
        val job = savePeriodicallyOnMain(
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + handler),
            intervalMs = 5_000L,
        ) { calls++ }

        runCurrent()
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(1, calls)

        job.cancel()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals("no further calls after cancel", 1, calls)
    }
}
