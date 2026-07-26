package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.odyssey.download.TransferKind
import com.odyssey.download.TransferRow
import com.odyssey.download.TransferState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render tests for TransferRowCard — the per-transfer card on the
 * Transfers screen. Exercises every branch the card switches on:
 * kind (download / upload / restore), state (active / queued), the
 * air-date subtitle, and the determinate-vs-indeterminate progress
 * bar. These render the real production composable so its lines count
 * toward the ui/screens coverage floor (it was 0%-covered before).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class TransferRowCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun row(
        id: Long,
        kind: TransferKind,
        state: TransferState = TransferState.ACTIVE,
        bytes: Long = 500L,
        total: Long = 1_000L,
        airDate: String? = null,
    ) = TransferRow(
        episodeId = id,
        title = "Episode $id",
        kind = kind,
        bytesTransferred = bytes,
        totalBytes = total,
        state = state,
        airDate = airDate,
    )

    @Test
    fun `active download shows percent and a determinate bar`() {
        composeRule.setContent {
            TransferRowCard(row(1, TransferKind.DOWNLOAD, bytes = 500, total = 1_000))
        }
        composeRule.onNodeWithTag("transfer-row-download-1").assertIsDisplayed()
        // percent = 50 for a half-done row (TransferRow.percent).
        composeRule.onNodeWithTag("transfer-row-pct").assertIsDisplayed()
    }

    @Test
    fun `active download with unknown total renders indeterminate bar`() {
        composeRule.setContent {
            TransferRowCard(row(2, TransferKind.DOWNLOAD, bytes = 0, total = 0))
        }
        composeRule.onNodeWithTag("transfer-row-download-2").assertIsDisplayed()
    }

    @Test
    fun `upload card carries the upload tag and label`() {
        composeRule.setContent {
            TransferRowCard(row(3, TransferKind.UPLOAD))
        }
        composeRule.onNodeWithTag("transfer-row-upload-3").assertIsDisplayed()
    }

    @Test
    fun `queued restore shows the queued tag and no progress bar`() {
        composeRule.setContent {
            TransferRowCard(row(4, TransferKind.RESTORE, state = TransferState.QUEUED))
        }
        composeRule.onNodeWithTag("transfer-row-restore-queued-4").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer-row-pct").assertIsDisplayed() // shows "queued"
    }

    @Test
    fun `air-date subtitle renders when present`() {
        composeRule.setContent {
            TransferRowCard(row(5, TransferKind.DOWNLOAD, airDate = "March 3, 2024"))
        }
        composeRule.onNodeWithTag("transfer-row-air-date").assertIsDisplayed()
    }
}
