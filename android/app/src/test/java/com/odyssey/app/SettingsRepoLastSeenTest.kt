package com.odyssey.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the per-provider lastSeen API added in step 2 of the YSH
 * plan. Two behaviors must hold during the transition:
 *
 *   1. AIO has a legacy-key fallback. Pre-upgrade installs stored the
 *      cursor as a Long under `last_seen_episode_id`. After the new
 *      version reads `lastSeenFor("aio")` for the first time, the
 *      legacy value must come through so the daily-check worker
 *      doesn't re-pull 50 episodes.
 *
 *   2. Non-AIO providers (YSH, future shows) start with null
 *      lastSeen on a fresh install — they don't share the AIO legacy
 *      cursor.
 *
 * Robolectric reuses the Application across tests in a class, so the
 * DataStore file persists between tests. Each @Test resets state
 * explicitly via the legacy `setLastSeen(Long)` shim, which clears the
 * legacy key by writing 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SettingsRepoLastSeenTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun wipeStore() = runBlocking {
        // Robolectric reuses the Application across tests in this class
        // so the DataStore file would otherwise carry values written by
        // a previous @Test. Start each test from a completely empty
        // store via the test-only `clearAllForTest` helper.
        SettingsRepo(ctx).clearAllForTest()
    }

    @Test
    fun `lastSeenFor returns null on a fresh install for any provider`() = runBlocking {
        val repo = SettingsRepo(ctx)
        assertNull("ysh starts null on fresh install", repo.lastSeenFor("ysh").first())
        // AIO with legacy unset also returns null — the shim treats 0
        // (or absent) as "unset" rather than a legitimate cursor.
        assertNull("aio with no legacy reads as null", repo.lastSeenFor("aio").first())
    }

    @Test
    fun `lastSeenFor falls back to the legacy long key for AIO`() = runBlocking {
        val repo = SettingsRepo(ctx)
        // Existing-install simulation: legacy long key is populated,
        // new per-provider key is unset.
        repo.setLastSeen(1278294L)
        assertEquals("1278294", repo.lastSeenFor("aio").first())
        // Other providers never read the legacy key.
        assertNull(repo.lastSeenFor("ysh").first())
    }

    @Test
    fun `setLastSeen for AIO writes through the new per-provider key and shadows the legacy fallback`() =
        runBlocking {
            val repo = SettingsRepo(ctx)
            repo.setLastSeen(100L)                           // legacy seed
            repo.setLastSeen("aio", "265")                   // new write
            // The per-provider key wins.
            assertEquals("265", repo.lastSeenFor("aio").first())
        }

    @Test
    fun `setLastSeen for YSH is independent of AIO`() = runBlocking {
        val repo = SettingsRepo(ctx)
        repo.setLastSeen("aio", "657")
        repo.setLastSeen("ysh", "1958")
        assertEquals("657", repo.lastSeenFor("aio").first())
        assertEquals("1958", repo.lastSeenFor("ysh").first())
    }

    @Test
    fun `lastSeenFor handles string externalIds (sku_ids non-numeric in theory)`() = runBlocking {
        // YSH stores sku_id stringified; future providers might use
        // GUIDs. Verify the API doesn't accidentally Long-parse.
        val repo = SettingsRepo(ctx)
        repo.setLastSeen("ysh", "ysh-sku-1958")
        assertEquals("ysh-sku-1958", repo.lastSeenFor("ysh").first())
    }
}
