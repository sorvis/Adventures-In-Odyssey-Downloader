package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.odyssey.data.local.LocalEpisodeEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric/Compose UI tests for EpisodeRow.
 *
 * Two interaction surfaces:
 *   - tap on the row's main ListItem  → toggles description expansion (NOT play)
 *   - tap on the Play button (only visible when expanded) → invokes onPlay
 *
 * Uses plain Application (not OdysseyApp) so Robolectric doesn't try to
 * boot the Hilt graph — this composable doesn't need it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpisodeRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `collapsed row hides description and play button`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }

        composeRule.onNodeWithTag("episode-row-description").assertDoesNotExist()
        composeRule.onNodeWithTag("episode-row-play-button").assertDoesNotExist()
    }

    @Test
    fun `expanded row shows description and play button`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(description = "A reckless word causes chaos in Odyssey."),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
            )
        }

        composeRule.onNodeWithTag("episode-row-description").assertIsDisplayed()
        composeRule.onNodeWithText("A reckless word causes chaos in Odyssey.").assertIsDisplayed()
        composeRule.onNodeWithTag("episode-row-play-button").assertIsDisplayed()
    }

    @Test
    fun `expanded row with null description shows fallback text`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(description = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
            )
        }

        composeRule.onNodeWithText("No description available.").assertIsDisplayed()
    }

    @Test
    fun `tapping a streamable row toggles expand and does not play`() {
        var toggled = 0
        var played = 0

        // Use real Compose state so the row actually re-renders expanded
        // after the tap — confirms the toggle path lights up the description.
        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            EpisodeRow(
                ep = episode(filePath = null, description = "Stream me."),
                played = false,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded; toggled++ },
                onPlay = { played++ },
            )
        }

        composeRule.onNodeWithTag("episode-row-streamable").performClick()
        composeRule.onNodeWithTag("episode-row-description").assertIsDisplayed()
        assertTrue("onToggleExpand should fire on row tap", toggled == 1)
        assertTrue("onPlay should NOT fire on row tap", played == 0)
    }

    @Test
    fun `tapping a downloaded row toggles expand and does not play`() {
        var toggled = 0
        var played = 0

        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3", description = "Already saved."),
                played = false,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded; toggled++ },
                onPlay = { played++ },
            )
        }

        composeRule.onNodeWithTag("episode-row-playable").performClick()
        composeRule.onNodeWithTag("episode-row-description").assertIsDisplayed()
        assertTrue(toggled == 1)
        assertTrue(played == 0)
    }

    @Test
    fun `tapping the play button invokes onPlay`() {
        var played = 0

        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = { played++ },
            )
        }

        composeRule.onNodeWithTag("episode-row-play-button").performClick()
        assertTrue("Play button should invoke onPlay", played == 1)
    }

    // ---- Trailing-chip states (downloaded / streamable / archived / played) ----

    @Test
    fun `streamable row shows stream chip`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithText("▶ stream").assertIsDisplayed()
    }

    @Test
    fun `downloaded row shows downloaded chip - visible signal that play wont re-stream`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithText("✓ downloaded").assertIsDisplayed()
        // ...and the streaming chip must NOT be present.
        composeRule.onNodeWithText("▶ stream").assertDoesNotExist()
    }

    @Test
    fun `played row shows played chip and overrides downloaded chip`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = true,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithText("✓ played").assertIsDisplayed()
        composeRule.onNodeWithText("✓ downloaded").assertDoesNotExist()
    }

    @Test
    fun `archived row shows archived chip and overrides played and downloaded`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3").copy(archivedAt = 1_700_000_000L),
                played = true,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithText("✓ archived").assertIsDisplayed()
        composeRule.onNodeWithText("✓ played").assertDoesNotExist()
        composeRule.onNodeWithText("✓ downloaded").assertDoesNotExist()
    }

    @Test
    fun `second tap on row collapses the row`() {
        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            EpisodeRow(
                ep = episode(filePath = null, description = "toggle me"),
                played = false,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                onPlay = {},
            )
        }

        composeRule.onNodeWithTag("episode-row-streamable").performClick()
        composeRule.onNodeWithTag("episode-row-description").assertIsDisplayed()

        composeRule.onNodeWithTag("episode-row-streamable").performClick()
        composeRule.onNodeWithTag("episode-row-description").assertDoesNotExist()
    }

    private fun episode(
        filePath: String? = null,
        description: String? = "Some description.",
    ): LocalEpisodeEntity = LocalEpisodeEntity(
        episodeId = 1L,
        title = "Some Episode",
        airDate = "2026-05-03",
        description = description,
        sourceUrl = "https://oneplace.com/episodes/1",
        downloadUrl = "https://example.com/1.mp3",
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
    )
}
