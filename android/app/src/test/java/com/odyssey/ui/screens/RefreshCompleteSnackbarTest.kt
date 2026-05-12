package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the user-facing snackbar emitted by
 * `RefreshCompleteSnackbarEffect` after a daily-check completes —
 * the v0.1.41 fix for the "I hit Refresh and nothing visibly
 * happens" report.
 *
 * Tests the pure helper for the copy and drives the Composable in
 * Robolectric to verify the LaunchedEffect actually shows a snackbar
 * on the true→false transition (and only then).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RefreshCompleteSnackbarTest {

    @get:Rule val composeRule = createComposeRule()

    // ---------- pure helper ----------

    @Test
    fun message_no_new_episodes() {
        assertEquals("Refresh complete — no new episodes", refreshCompleteMessage(0))
        // Should also handle a negative input defensively (worker
        // should never produce one, but coerceAtLeast(0) is cheap).
        assertEquals("Refresh complete — no new episodes", refreshCompleteMessage(-3))
    }

    @Test
    fun message_one_new_episode_uses_singular() {
        assertEquals("Refresh complete — 1 new episode", refreshCompleteMessage(1))
    }

    @Test
    fun message_multiple_new_episodes_uses_plural() {
        assertEquals("Refresh complete — 3 new episodes", refreshCompleteMessage(3))
    }

    // ---------- Compose effect ----------

    @Test
    fun snackbar_fires_on_true_to_false_transition_using_worker_published_count() {
        // The fix for the "no new episodes" bug: the effect reads the
        // count directly from SettingsRepo.lastCheckNewCount (passed
        // in as `newCount`), not from items.size delta. The worker
        // publishes the count BEFORE its Result.success(), which
        // happens BEFORE WorkInfo flips to SUCCEEDED, so there's no
        // race with Room's Flow.
        val state = SnackbarHostState()
        var isRefreshing by mutableStateOf(false)
        var newCount by mutableIntStateOf(0)

        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = isRefreshing,
                newCount = newCount,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()
        assertNull(state.currentSnackbarData)

        // Worker starts: isRefreshing true. Worker publishes 3 new
        // (it inserted 3 rows). Worker completes: isRefreshing false.
        isRefreshing = true
        composeRule.waitForIdle()
        newCount = 3   // worker calls settings.setLastCheckNewCount(3)
        composeRule.waitForIdle()
        isRefreshing = false
        composeRule.waitForIdle()

        val data = state.currentSnackbarData
        assertNotNull("snackbar should appear after refresh completes", data)
        assertEquals("Refresh complete — 3 new episodes", data!!.visuals.message)
    }

    @Test
    fun snackbar_reports_no_new_episodes_when_worker_published_zero() {
        val state = SnackbarHostState()
        var isRefreshing by mutableStateOf(false)

        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = isRefreshing,
                newCount = 0,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()

        isRefreshing = true
        composeRule.waitForIdle()
        isRefreshing = false
        composeRule.waitForIdle()

        val data = state.currentSnackbarData
        assertNotNull("must still emit the 'done' snackbar even when no new rows", data)
        assertEquals("Refresh complete — no new episodes", data!!.visuals.message)
    }

    @Test
    fun snackbar_does_NOT_fire_on_initial_composition_when_isRefreshing_is_already_false() {
        // Regression guard: the LaunchedEffect runs on first composition.
        // If the keyed value is already `false`, no transition has
        // happened — we must NOT emit a stale "Refresh complete" message.
        val state = SnackbarHostState()
        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = false,
                newCount = 0,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()
        assertNull("no snackbar should appear without a refresh having fired",
                   state.currentSnackbarData)
    }

    @Test
    fun snackbar_does_NOT_lose_the_count_if_newCount_arrives_BEFORE_isRefreshing_flips_false() {
        // Reproduces the v0.1.42 race symptom: the worker publishes
        // its count just before completing. There's no ordering
        // guarantee that `newCount` propagates to Compose AFTER
        // `isRefreshing` flips false. But since the snackbar effect
        // reads `newCount` directly from SettingsRepo (not via items
        // delta), it picks up whichever value is current — and the
        // worker writes the count BEFORE returning, so by the time
        // isRefreshing observes the completion the count is correct.
        val state = SnackbarHostState()
        var isRefreshing by mutableStateOf(false)
        var newCount by mutableIntStateOf(0)

        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = isRefreshing,
                newCount = newCount,
                snackbarHostState = state,
            )
        }
        isRefreshing = true
        composeRule.waitForIdle()
        // Worker writes newCount BEFORE its Result.success() — by
        // the time isRefreshing observes the completion, newCount
        // is already 3.
        newCount = 3
        isRefreshing = false
        composeRule.waitForIdle()

        val data = state.currentSnackbarData
        assertNotNull(data)
        assertEquals("Refresh complete — 3 new episodes", data!!.visuals.message)
    }
}
