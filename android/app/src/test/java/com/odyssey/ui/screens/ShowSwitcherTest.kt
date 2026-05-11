package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.odyssey.show.ProviderEpisode
import com.odyssey.show.ShowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-nav ShowSwitcher dropdown (step 10d).
 *
 * Locks down:
 *   - shows the active show's label
 *   - tapping the button opens a menu listing every enabled provider
 *     plus a "Manage shows…" footer that fires onOpenSettings
 *   - picking a different show fires onPickActive with the right id
 *   - selecting the current show shows a leading "✓"
 *
 * Drives `ShowSwitcherUi` directly so we don't need Hilt — the
 * production `ShowSwitcher` wrapper just feeds it from a VM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShowSwitcherTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun button_shows_active_show_label_and_dropdown_lists_every_provider() {
        composeRule.setContent {
            ShowSwitcherUi(
                providers = listOf(
                    stub("aio", "Adventures in Odyssey"),
                    stub("ysh", "Your Story Hour"),
                ),
                activeId = "aio",
                onPickActive = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("Adventures in Odyssey").assertIsDisplayed()
        // Open the menu.
        composeRule.onNodeWithTag("show-switcher-button").performClick()
        composeRule.onNodeWithTag("show-switcher-item-aio").assertIsDisplayed()
        composeRule.onNodeWithTag("show-switcher-item-ysh").assertIsDisplayed()
        composeRule.onNodeWithTag("show-switcher-manage").assertIsDisplayed()
    }

    @Test
    fun picking_a_different_show_fires_onPickActive_with_the_id() {
        val picks = mutableListOf<String>()
        composeRule.setContent {
            ShowSwitcherUi(
                providers = listOf(
                    stub("aio", "Adventures in Odyssey"),
                    stub("ysh", "Your Story Hour"),
                ),
                activeId = "aio",
                onPickActive = { picks += it },
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithTag("show-switcher-button").performClick()
        composeRule.onNodeWithTag("show-switcher-item-ysh").performClick()
        assertEquals(listOf("ysh"), picks)
    }

    @Test
    fun manage_shows_footer_fires_onOpenSettings() {
        var settingsOpens = 0
        composeRule.setContent {
            ShowSwitcherUi(
                providers = listOf(
                    stub("aio", "Adventures in Odyssey"),
                    stub("ysh", "Your Story Hour"),
                ),
                activeId = "aio",
                onPickActive = {},
                onOpenSettings = { settingsOpens++ },
            )
        }
        composeRule.onNodeWithTag("show-switcher-button").performClick()
        composeRule.onNodeWithTag("show-switcher-manage").performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun active_show_carries_a_leading_checkmark_in_the_menu() {
        composeRule.setContent {
            ShowSwitcherUi(
                providers = listOf(
                    stub("aio", "Adventures in Odyssey"),
                    stub("ysh", "Your Story Hour"),
                ),
                activeId = "ysh",
                onPickActive = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithTag("show-switcher-button").performClick()
        // "✓ Your Story Hour" rendered inside the dropdown item.
        // Compose's onNodeWithText defaults to substring=false / exact
        // match — split into two Text() siblings to test each piece.
        composeRule.onNodeWithText("✓ ").assertIsDisplayed()
    }

    private fun stub(id: String, name: String): ShowProvider =
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
