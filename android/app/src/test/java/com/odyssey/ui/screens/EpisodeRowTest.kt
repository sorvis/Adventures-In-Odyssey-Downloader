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
    fun `tapping the delete icon opens a confirm dialog instead of deleting immediately`() {
        // The trash icon is intentionally gated by an "are you sure?"
        // dialog because accidental presses would destroy the local mp3.
        // Tapping the trash itself MUST NOT fire onDelete — the user has
        // to confirm.
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
        composeRule.onNodeWithTag("episode-row-delete-dialog").assertExists()
        assertTrue("onDelete must NOT fire on the trash tap alone", deleted == 0)
        assertTrue("onPlay must not fire from a delete tap", played == 0)
    }

    @Test
    fun `confirming the delete dialog fires onDelete`() {
        var deleted = 0
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDelete = { deleted++ },
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").performClick()
        composeRule.onNodeWithTag("episode-row-delete-confirm").performClick()
        assertTrue("onDelete should fire after confirm", deleted == 1)
    }

    @Test
    fun `cancelling the delete dialog does not fire onDelete`() {
        var deleted = 0
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3"),
                played = false,
                expanded = true,
                onToggleExpand = {},
                onPlay = {},
                onDelete = { deleted++ },
            )
        }
        composeRule.onNodeWithTag("episode-row-delete-button").performClick()
        composeRule.onNodeWithTag("episode-row-delete-cancel").performClick()
        assertTrue("onDelete must not fire on cancel", deleted == 0)
        composeRule.onNodeWithTag("episode-row-delete-dialog").assertDoesNotExist()
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
    fun `row does NOT render the oneplace CMS id as an episode number`() {
        // Regression lock: oneplace's episodeId is a CMS-internal id
        // (e.g. 1278383) NOT the canonical AIO episode number (e.g. #657
        // for "Clutter"). Showing the CMS id is misleading. The real
        // number must come from app.adventuresinodyssey.com — see
        // BACKLOG.md. Until that's wired, headline is title-only.
        composeRule.setContent {
            EpisodeRow(
                ep = episode().copy(externalId = "1278383"),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-number", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("#1278383", useUnmergedTree = true).assertDoesNotExist()
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
    fun `progress percent chip shows while downloading and overrides the duration chip`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null).copy(durationMs = 25 * 60_000L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
                downloadProgress = DownloadProgressEntry(bytesRead = 200, totalBytes = 1000),
            )
        }
        composeRule.onNodeWithText("20%", useUnmergedTree = true).assertExists()
        // The percent chip outranks the duration chip while a download is
        // in flight; the row's "25 min" length must NOT also show.
        composeRule.onNodeWithText("25 min", useUnmergedTree = true).assertDoesNotExist()
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

    // ---- Trailing-chip states (duration / played / progress) ----
    //
    // Downloaded vs not-downloaded is now communicated by the row's
    // alpha (lighter gray for streamable, normal for downloaded), not
    // by a chip. The chip slot is the duration cue (or progress while
    // a transfer is in flight, or the played mark when finished).

    @Test
    fun `streamable row shows duration chip with no downloaded or stream label`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null).copy(durationMs = 25 * 60_000L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        // assertExists, not assertIsDisplayed — Robolectric's window
        // sizing isn't reliable for visibility checks (matches the
        // pattern used in the rest of this file).
        composeRule.onNodeWithTag("episode-row-duration", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("25 min", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("▶ stream", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("✓ downloaded", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `downloaded row shows duration chip with no downloaded label`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3").copy(durationMs = 25 * 60_000L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-duration", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("25 min", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("✓ downloaded", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("▶ stream", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `played row shows played chip and overrides duration chip`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/123.mp3").copy(durationMs = 25 * 60_000L),
                played = true,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithText("✓ played", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("25 min", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("✓ downloaded", useUnmergedTree = true).assertDoesNotExist()
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

    // ---- ownership badges (v0.1.70 — match Album view's two-chip pattern) ----

    @Test
    fun `on-phone badge appears when filePath is set`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/aio/1.mp3"),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        // assertExists rather than assertIsDisplayed: M3 ListItem's
        // trailingContent slot has tight measurement constraints that
        // can leave stacked Text nodes technically out-of-viewport in
        // the test harness. The contract we care about is "the badge
        // composable rendered" — assertExists captures that.
        composeRule.onNodeWithTag("episode-row-on-phone", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `on-phone badge hidden when filePath is null`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-on-phone").assertDoesNotExist()
    }

    @Test
    fun `on-backup badge appears when archivedAt is set`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null, archivedAt = 12345L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-on-backup", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `on-backup badge hidden when archivedAt is null`() {
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null, archivedAt = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-on-backup").assertDoesNotExist()
    }

    @Test
    fun `both badges show when row is on-phone AND on-backup`() {
        // The "I have this everywhere" state — local file present AND
        // archived to the NAS. Both chips stack so the user can see at
        // a glance what they have where. Same UX as the Album view.
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = "/data/odyssey/aio/1.mp3", archivedAt = 12345L),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-on-phone", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("episode-row-on-backup", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `neither badge shows when row is streamable-only (CDN, no backup, no local)`() {
        // Fresh DailyCheck ingest before download — row exists, points
        // at oneplace CDN, but nothing's been downloaded or archived
        // yet. Both badges hidden.
        composeRule.setContent {
            EpisodeRow(
                ep = episode(filePath = null, archivedAt = null),
                played = false,
                expanded = false,
                onToggleExpand = {},
                onPlay = {},
            )
        }
        composeRule.onNodeWithTag("episode-row-on-phone").assertDoesNotExist()
        composeRule.onNodeWithTag("episode-row-on-backup").assertDoesNotExist()
    }

    private fun episode(
        filePath: String? = null,
        description: String? = "Some description.",
        archivedAt: Long? = null,
    ): LocalEpisodeEntity = LocalEpisodeEntity(
        providerId = "aio",
        externalId = "1",
        title = "Some Episode",
        airDate = "2026-05-03",
        description = description,
        sourceUrl = "https://oneplace.com/episodes/1",
        downloadUrl = "https://example.com/1.mp3",
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = archivedAt,
    )
}
