package com.odyssey.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The autoplay toggle, added alongside the album playlist.
 *
 * The default matters: auto-advance shipped ON in v0.1.90, so an
 * install that upgrades into this build must keep advancing. A DataStore
 * key that is simply absent has to read as "on", not as "off".
 *
 * Note this covers the SETTING only. The behaviour it drives — pausing
 * on an automatic media-item transition — lives in PlayerController's
 * Media3 listener, which needs a live MediaController and is not
 * reachable from a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AutoplaySettingTest {

    private lateinit var settings: SettingsRepo

    @Before
    fun setUp() {
        settings = SettingsRepo(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clearAllForTest() }
    }

    @Test
    fun `defaults to on so upgrading installs keep advancing`() = runBlocking {
        assertTrue(settings.flow.first().autoplayNextEpisode)
    }

    @Test
    fun `turning it off persists`() = runBlocking {
        settings.setAutoplayNextEpisode(false)
        assertFalse(settings.flow.first().autoplayNextEpisode)
    }

    @Test
    fun `turning it back on persists`() = runBlocking {
        settings.setAutoplayNextEpisode(false)
        settings.setAutoplayNextEpisode(true)
        assertTrue(settings.flow.first().autoplayNextEpisode)
    }
}
