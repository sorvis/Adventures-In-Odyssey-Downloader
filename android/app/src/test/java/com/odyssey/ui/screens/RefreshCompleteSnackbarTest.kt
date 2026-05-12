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
        assertEquals("Refresh complete — no new episodes", refreshCompleteMessage(before = 7, after = 7))
        // Should also handle the degenerate "row count went DOWN" case
        // (could happen if a retention pass pruned between snapshots).
        // Treat as "no new" rather than negative.
        assertEquals("Refresh complete — no new episodes", refreshCompleteMessage(before = 10, after = 4))
    }

    @Test
    fun message_one_new_episode_uses_singular() {
        assertEquals("Refresh complete — 1 new episode", refreshCompleteMessage(before = 7, after = 8))
    }

    @Test
    fun message_multiple_new_episodes_uses_plural() {
        assertEquals("Refresh complete — 3 new episodes", refreshCompleteMessage(before = 7, after = 10))
    }

    // ---------- Compose effect ----------

    @Test
    fun snackbar_fires_on_true_to_false_transition() {
        val state = SnackbarHostState()
        var isRefreshing by mutableStateOf(false)
        var itemCount by mutableIntStateOf(7)

        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = isRefreshing,
                itemCount = itemCount,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()
        // Nothing has happened yet — no refresh in progress, no snackbar.
        assertNull(state.currentSnackbarData)

        // Simulate the refresh: flip to refreshing, increment items,
        // then flip back to not-refreshing.
        isRefreshing = true
        composeRule.waitForIdle()
        itemCount = 10
        composeRule.waitForIdle()
        isRefreshing = false
        composeRule.waitForIdle()

        val data = state.currentSnackbarData
        assertNotNull("snackbar should appear after refresh completes", data)
        assertEquals("Refresh complete — 3 new episodes", data!!.visuals.message)
    }

    @Test
    fun snackbar_reports_no_new_episodes_when_count_unchanged() {
        val state = SnackbarHostState()
        var isRefreshing by mutableStateOf(false)
        val itemCount = mutableIntStateOf(7)

        composeRule.setContent {
            RefreshCompleteSnackbarEffect(
                isRefreshing = isRefreshing,
                itemCount = itemCount.intValue,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()

        isRefreshing = true
        composeRule.waitForIdle()
        // No item-count change — simulates a daily check that found
        // nothing new (which is the common case post-cellular refresh).
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
                itemCount = 7,
                snackbarHostState = state,
            )
        }
        composeRule.waitForIdle()
        assertNull("no snackbar should appear without a refresh having fired",
                   state.currentSnackbarData)
    }
}
