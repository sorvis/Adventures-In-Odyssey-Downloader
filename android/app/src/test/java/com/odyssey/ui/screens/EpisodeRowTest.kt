package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.odyssey.data.local.LocalEpisodeEntity
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric/Compose UI test for EpisodeRow — pins the bug fix where
 * undownloaded rows were silently un-tappable. The corresponding
 * regression at the dispatch layer is covered by PlaySourceTest; this
 * test covers the UI layer (clickable wired up, no `enabled = …` gate).
 *
 * Uses plain Application (not OdysseyApp) so Robolectric doesn't try to
 * boot the Hilt graph — this composable doesn't need it.
 *
 * TODO: currently @Ignore'd — initial CI run failed (commit 5ecbc22),
 * exact stack trace not yet retrieved (gha logs need auth). Likely
 * causes: the Hilt-rewritten merged manifest still drives Robolectric's
 * Application init even with @Config override, OR ui-test-manifest's
 * test activity isn't being picked up from testImplementation. Re-enable
 * once we can run `./gradlew test` locally to iterate on the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpisodeRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping a streamable row invokes onPlay`() {
        val ep = episode(filePath = null)
        var clicked = false

        composeRule.setContent {
            EpisodeRow(ep = ep, played = false, onPlay = { clicked = true })
        }

        composeRule.onNodeWithTag("episode-row-streamable").performClick()
        assertTrue("onPlay was not invoked for an undownloaded (streamable) row", clicked)
    }

    @Test
    fun `tapping a downloaded row invokes onPlay`() {
        val ep = episode(filePath = "/data/odyssey/123.mp3")
        var clicked = false

        composeRule.setContent {
            EpisodeRow(ep = ep, played = false, onPlay = { clicked = true })
        }

        composeRule.onNodeWithTag("episode-row-playable").performClick()
        assertTrue("onPlay was not invoked for a downloaded (playable) row", clicked)
    }

    private fun episode(filePath: String?): LocalEpisodeEntity = LocalEpisodeEntity(
        episodeId = 1L,
        title = "Some Episode",
        airDate = "2026-05-03",
        description = null,
        sourceUrl = "https://oneplace.com/episodes/1",
        downloadUrl = "https://example.com/1.mp3",
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
    )
}
