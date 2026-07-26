package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.odyssey.debug.DebugLogEntry
import com.odyssey.debug.LogLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render tests for LogRow — one line of the Debug screen's log dump.
 * Covers the per-level color switch (DEBUG/INFO/WARN/ERROR) and the
 * optional stack-trace line. Renders the real production composable so
 * DebugScreen's lines count toward the ui/screens coverage floor (the
 * file was 0%-covered before).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DebugLogRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(level: LogLevel, message: String, throwable: String? = null) =
        DebugLogEntry(
            timestampMs = 0L,
            level = level,
            tag = "TestTag",
            message = message,
            throwable = throwable,
        )

    @Test
    fun `renders one row per log level`() {
        composeRule.setContent {
            Column(modifier = androidx.compose.ui.Modifier.testTag("log-col")) {
                LogRow(entry(LogLevel.DEBUG, "debug line"))
                LogRow(entry(LogLevel.INFO, "info line"))
                LogRow(entry(LogLevel.WARN, "warn line"))
                LogRow(entry(LogLevel.ERROR, "error line"))
            }
        }
        composeRule.onNodeWithTag("log-col").assertIsDisplayed()
        composeRule.onNodeWithText("debug line").assertIsDisplayed()
        composeRule.onNodeWithText("error line").assertIsDisplayed()
    }

    @Test
    fun `error entry with a throwable renders the stack-trace line`() {
        composeRule.setContent {
            LogRow(entry(LogLevel.ERROR, "boom", throwable = "java.lang.IllegalStateException: nope"))
        }
        composeRule.onNodeWithText("boom").assertIsDisplayed()
        composeRule.onNodeWithText("java.lang.IllegalStateException: nope").assertIsDisplayed()
    }

    @Test
    fun `the header line embeds the level initial and tag`() {
        composeRule.setContent {
            LogRow(entry(LogLevel.WARN, "watch out"))
        }
        // Header is "<ts>  W/TestTag" — assert the tag half is present.
        composeRule.onNodeWithText("W/TestTag", substring = true).assertIsDisplayed()
    }
}
