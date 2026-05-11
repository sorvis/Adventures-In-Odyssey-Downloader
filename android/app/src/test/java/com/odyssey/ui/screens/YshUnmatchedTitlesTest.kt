package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.odyssey.data.local.YshUnmatchedTitleEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YSH unmatched-titles review screen (step 10c).
 *
 * Locks down:
 *   - subtitle formatting (date + attempt-count plural)
 *   - Compose row contract: title rendered, Dismiss button fires
 *     onDismiss with the right id
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshUnmatchedTitlesTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun yshUnmatchedSubtitle_pluralizes_attempts() {
        // Timestamp doesn't matter for the pluralization check (date
        // formatting is timezone-dependent and not load-bearing).
        val ts = 1746835200000L
        val singular = yshUnmatchedSubtitle(stub(attempts = 1, ts = ts))
        val plural = yshUnmatchedSubtitle(stub(attempts = 4, ts = ts))
        assertEquals(true, singular.startsWith("1 attempt · first seen "))
        assertEquals(true, plural.startsWith("4 attempts · first seen "))
    }

    private fun stub(attempts: Int, ts: Long) = YshUnmatchedTitleEntity(
        oneplaceEpisodeId = attempts.toLong(),
        title = "x",
        sourceUrl = "x",
        downloadUrl = "x",
        firstSeenAt = ts,
        attemptCount = attempts,
    )

    @Test
    fun row_renders_title_and_dismiss_button_fires_callback() {
        var dismissed = 0L
        composeRule.setContent {
            YshUnmatchedRow(
                row = YshUnmatchedTitleEntity(
                    oneplaceEpisodeId = 1277617,
                    title = "Child of Privilege (Lottie Moon Part 1)",
                    sourceUrl = "https://oneplace.com/.../1277617",
                    downloadUrl = "https://zcast.swncdn.com/.../1277617.mp3",
                    firstSeenAt = System.currentTimeMillis(),
                    attemptCount = 2,
                ),
                onDismiss = { dismissed = 1277617L },
            )
        }
        composeRule.onNodeWithText("Child of Privilege (Lottie Moon Part 1)").assertIsDisplayed()
        composeRule.onNodeWithTag("ysh-unmatched-dismiss-1277617").performClick()
        assertEquals(1277617L, dismissed)
    }
}
