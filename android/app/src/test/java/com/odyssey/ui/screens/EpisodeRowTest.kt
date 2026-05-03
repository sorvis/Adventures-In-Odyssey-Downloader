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
import com.odyssey.download.DownloadProgressEntry
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
    fun `collapsed row hides the expanded full-description block and play button`() {
        // Note: the collapsed *truncated* description (testTag
        // "episode-row-description-collapsed") IS visible per the
        // visibility tests below. This test pins the EXPANDED slot —
        // full description + Play button — to expanded == true only.
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
        // The collapsed truncated description is hidden when expanded so
        // the same text doesn't appear twice — assertExists on the
        // expanded testTag is the cleanest way to verify the expanded
        // copy specifically.
        composeRule.onNodeWithTag("episode-row-description-collapsed").assertDoesNotExist()
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

    // ---- Download button (manual re-download feature) -----------------

    @Test
    fun `download button is visible on streamable rows when handler is wired`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDownload = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-download-button").assertIsDisplayed()
    }

    @Test
    fun `download button is hidden when episode is already downloaded`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDownload = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-download-button").assertDoesNotExist()
    }

    @Test
    fun `download button is hidden when no onDownload handler is provided`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                // onDownload intentionally omitted
            )
        }
        composeRule.onNodeWithTag("episode-row-download-button").assertDoesNotExist()
    }

    @Test
    fun `download button is hidden while a download is already in flight`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDownload = {},
                downloadProgress = DownloadProgressEntry(bytesRead = 100, totalBytes = 1000),
            )
        }
        // Don't offer a duplicate download trigger — progress bar already
        // signals that a worker is active.
        composeRule.onNodeWithTag("episode-row-download-button").assertDoesNotExist()
    }

    @Test
    fun `tapping the download button invokes onDownload and not onPlay`() {
        var played = 0
        var downloaded = 0
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = { played++ },
                onDownload = { downloaded++ },
            )
        }
        composeRule.onNodeWithTag("episode-row-download-button").performClick()
        assertTrue("onDownload should fire", downloaded == 1)
        assertTrue("onPlay should not fire from a download tap", played == 0)
    }

    // ---- Delete button (manual delete-download feature) ---------------

    @Test
    fun `delete button is visible when row is downloaded and expanded with onDelete wired`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDelete = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").assertIsDisplayed()
    }

    @Test
    fun `delete button is hidden for streamable rows even when expanded`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDelete = {},
            )
        }
        // No file on disk → nothing to delete → button is hidden.
        composeRule.onNodeWithTag("episode-row-delete-button").assertDoesNotExist()
    }

    @Test
    fun `delete button is hidden when no onDelete handler is provided`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                // onDelete intentionally omitted → screen doesn't offer delete.
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").assertDoesNotExist()
    }

    @Test
    fun `delete button is hidden when row is collapsed`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                onDelete = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").assertDoesNotExist()
    }

    @Test
    fun `tapping the delete button invokes onDelete and not onPlay`() {
        var played = 0
        var deleted = 0
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = { played++ },
                onDelete = { deleted++ },
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").performClick()
        assertTrue("onDelete should fire", deleted == 1)
        assertTrue("onPlay should not fire from a delete tap", played == 0)
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

    // ---- Per-row visibility: thumbnail + description ----

    @Test
    fun `every row renders the thumbnail in the leading slot`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        // useUnmergedTree because ListItem's leading/supporting slots fold
        // their children into the merged semantics tree for accessibility,
        // hiding inner testTags. assertExists is enough — assertIsDisplayed
        // doesn't measure ListItem slots reliably without a real window.
        composeRule.onNodeWithTag("episode-row-thumbnail", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `collapsed row shows truncated description in supporting slot`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(description = "Eugene's careless word becomes the new insult."),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        // Description is visible WITHOUT expanding — the new contract for
        // the Recent list. useUnmergedTree because the description Text
        // lives inside ListItem's supportingContent slot.
        composeRule.onNodeWithTag(
            "episode-row-description-collapsed",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "Eugene's careless word becomes the new insult.",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `every row renders the episode number next to the title`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode().copy(episodeId = 1278383L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-number", useUnmergedTree = true).assertExists()
        // Format is "#<id>" — locks the user-visible string contract.
        composeRule.onNodeWithText("#1278383", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `collapsed row hides description when episode has none`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(description = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag(
            "episode-row-description-collapsed",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun `collapsed row hides description when episode description is blank`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(description = "   "),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag(
            "episode-row-description-collapsed",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    // ---- Download progress UI ----

    @Test
    fun `progress bar and percent chip are visible when downloadProgress is set`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                downloadProgress = DownloadProgressEntry(bytesRead = 5_000_000, totalBytes = 10_000_000),
            )
        }
        composeRule.onNodeWithTag("episode-row-progress-bar", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("episode-row-progress-pct", useUnmergedTree = true).assertExists()
        // 5/10 = 50% — text contract.
        composeRule.onNodeWithText("50%", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `progress percent chip overrides the trailing stream chip`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                downloadProgress = DownloadProgressEntry(bytesRead = 200, totalBytes = 1000),
            )
        }
        composeRule.onNodeWithText("20%", useUnmergedTree = true).assertExists()
        // While downloading, "▶ stream" should NOT show — the progress
        // chip takes priority since the row is actively transitioning.
        composeRule.onNodeWithText("▶ stream", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `progress bar is absent when downloadProgress is null (no in-flight download)`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                downloadProgress = null,
            )
        }
        composeRule.onNodeWithTag("episode-row-progress-bar", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("episode-row-progress-pct", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `unknown total bytes still shows progress bar (indeterminate variant)`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                // totalBytes=-1 means server didn't send Content-Length.
                downloadProgress = DownloadProgressEntry(bytesRead = 100, totalBytes = -1),
            )
        }
        composeRule.onNodeWithTag("episode-row-progress-bar", useUnmergedTree = true).assertExists()
        // Percent reads 0 in this case (per DownloadProgressTest).
        composeRule.onNodeWithText("0%", useUnmergedTree = true).assertExists()
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
