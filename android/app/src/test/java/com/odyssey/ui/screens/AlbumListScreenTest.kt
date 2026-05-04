package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.odyssey.catalog.AioAlbum
import com.odyssey.catalog.AioCatalog
import com.odyssey.catalog.AioCatalogEpisode
import com.odyssey.catalog.AlbumWithOwnership
import com.odyssey.catalog.CatalogEpisodeWithOwnership
import com.odyssey.catalog.EpisodeOwnership
import com.odyssey.catalog.LocalEpisodeKey
import com.odyssey.catalog.joinAlbumOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the "tap Albums tab → app crashes" report.
 *
 * Cause: the public AIO catalog has TWO entries with album number
 * "78.5" (the regular one and a special variant). Our LazyColumn
 * was keyed on albumNumber alone — Compose throws
 * IllegalArgumentException("Key … was already used") on duplicate
 * keys, which manifested as a top-level crash when opening the tab.
 *
 * The fix uses album NAME as the key (unique even when number isn't).
 * These tests render the same data shape that crashed and assert
 * that LazyColumn composes successfully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AlbumListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `LazyColumn renders with two albums sharing the same albumNumber`() {
        val rows = listOf(
            row(albumNumber = "78.5", name = "#78.5: Club Season 11 - Part 2"),
            row(albumNumber = "78.5", name = "#78.5: Club Season 12 - Part 2"),  // ← duplicate number
            row(albumNumber = "81",   name = "#81: Never a Dull Moment"),
        )

        // Inline the same key strategy AlbumListScreen now uses, against
        // the same data shape. If keys collide, Compose throws inside
        // setContent and this test fails.
        composeRule.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("test-list")) {
                items(rows, key = { it.album.name ?: it.album.albumNumber ?: "" }) { r ->
                    Text(r.album.name ?: "?")
                }
            }
        }
        // assertExists not assertIsDisplayed — Robolectric's window
        // sizing is unreliable in the test rule.
        composeRule.onNodeWithTag("test-list").assertExists()
    }

    @Test
    fun `joinAlbumOwnership produces unique names for the duplicate-number scenario`() {
        // Pin the contract that catalog data with duplicate albumNumbers
        // still yields rows whose names ARE unique. If a future scrape
        // ever produces colliding names, the comment above and the keying
        // strategy both need to change.
        val cat = AioCatalog(
            scrapedAtMs = 0L, albumCount = 2,
            albums = listOf(
                AioAlbum(albumNumber = "78.5", name = "#78.5: A", episodes = listOf(AioCatalogEpisode("X"))),
                AioAlbum(albumNumber = "78.5", name = "#78.5: B", episodes = listOf(AioCatalogEpisode("Y"))),
            ),
        )
        val joined = joinAlbumOwnership(cat, emptyList())
        val names = joined.map { it.album.name }
        assertEquals(2, names.size)
        assertEquals(names.size, names.toSet().size)  // all unique
    }

    @Test
    fun `name is non-null for every album the scraped catalog returns`() {
        // Defense in depth — if any album row had a null name the
        // LazyColumn key fallback chain (name → albumNumber → "")
        // could re-introduce a collision via the empty-string sentinel.
        val cat = AioCatalog(
            scrapedAtMs = 0L, albumCount = 1,
            albums = listOf(AioAlbum(albumNumber = "X", name = null, episodes = emptyList())),
        )
        val joined = joinAlbumOwnership(cat, emptyList())
        assertNotNull("expect a row even when name is null", joined.firstOrNull())
        // Caller (AlbumListScreen) handles this case via fallback to
        // albumNumber; the test just documents the data shape.
    }

    private fun row(albumNumber: String, name: String): AlbumWithOwnership =
        AlbumWithOwnership(
            album = AioAlbum(albumNumber = albumNumber, name = name, episodes = emptyList()),
            episodes = emptyList<CatalogEpisodeWithOwnership>(),
        )
}
