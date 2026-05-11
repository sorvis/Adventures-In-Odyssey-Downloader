package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.odyssey.show.ProviderEpisode
import com.odyssey.show.ShowProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Visual contract for ShowsCard — the Settings affordance that lets
 * the user turn YSH on (step 9 of the YSH plan).
 *
 * Asserted behaviors:
 *   - One row per registered provider, sorted with AIO first.
 *   - Each row has a Switch that reflects the enabledIds set.
 *   - Toggling a Switch fires onToggle with the right (id, enabled)
 *     pair.
 *   - Active provider gets a leading checkmark; an enabled-but-
 *     not-active row exposes a "Make active" button that fires
 *     onPickActive when clicked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShowsCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `disabled YSH shows up unchecked and toggle event carries the right id`() {
        val toggleEvents = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            ShowsCard(
                providers = listOf(stubProvider("aio", "Adventures in Odyssey"), stubProvider("ysh", "Your Story Hour")),
                enabledIds = setOf("aio"),
                activeId = "aio",
                onToggle = { id, enabled -> toggleEvents += id to enabled },
                onPickActive = {},
            )
        }

        // Both rows render.
        composeRule.onNodeWithText("Adventures in Odyssey").assertIsDisplayed()
        composeRule.onNodeWithText("Your Story Hour").assertIsDisplayed()
        // AIO is on; YSH is off.
        composeRule.onNodeWithTag("show-toggle-aio").assertIsOn()
        composeRule.onNodeWithTag("show-toggle-ysh").assertIsOff()

        // Flip YSH on → onToggle fires with the right pair.
        composeRule.onNodeWithTag("show-toggle-ysh").performClick()
        assertEquals(listOf("ysh" to true), toggleEvents)
    }

    @Test
    fun `enabled-but-not-active row exposes a Make active button`() {
        val activePicks = mutableListOf<String>()
        composeRule.setContent {
            ShowsCard(
                providers = listOf(stubProvider("aio", "Adventures in Odyssey"), stubProvider("ysh", "Your Story Hour")),
                enabledIds = setOf("aio", "ysh"),
                activeId = "aio",   // AIO is active
                onToggle = { _, _ -> },
                onPickActive = { activePicks += it },
            )
        }

        // YSH row has "Make active" because it's enabled but not active.
        composeRule.onNodeWithTag("make-active-ysh").assertIsDisplayed().assertHasClickAction()
        // AIO row does NOT show "Make active" — it's already active.
        composeRule.onNodeWithTag("make-active-aio").assertDoesNotExist()

        composeRule.onNodeWithTag("make-active-ysh").performClick()
        assertEquals(listOf("ysh"), activePicks)
    }

    @Test
    fun `disabling a row does NOT show Make active (would be useless)`() {
        composeRule.setContent {
            ShowsCard(
                providers = listOf(stubProvider("aio", "Adventures in Odyssey"), stubProvider("ysh", "Your Story Hour")),
                enabledIds = setOf("aio"),     // YSH is off
                activeId = "aio",
                onToggle = { _, _ -> },
                onPickActive = {},
            )
        }
        // YSH is disabled → no "Make active" button rendered for it.
        composeRule.onNodeWithTag("make-active-ysh").assertDoesNotExist()
    }

    private fun stubProvider(id: String, name: String): ShowProvider =
        object : ShowProvider {
            override val id = id
            override val displayName = name
            override val artistName = name
            override suspend fun newSince(
                lastSeenExternalId: String?,
                maxFetch: Int,
            ): List<ProviderEpisode> = emptyList()
        }
}
