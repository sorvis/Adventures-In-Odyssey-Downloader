package com.odyssey.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.show.YshCatalog
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring coverage for [AlbumNavResolver] — that a row maps to the right
 * route family (YSH stored-album path, AIO title path, unknown → null).
 * The per-show resolution rules are covered in depth by
 * `YshAlbumDetailTest` (YSH) and `AioCatalogRepo`/match tests (AIO).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AlbumNavResolverTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val resolver = AlbumNavResolver(
        AioCatalogRepo(ctx),
        YshCatalog(ctx, OkHttpClient()),
    )

    @Test
    fun `YSH row resolves to its stored album even with the catalog unloaded`() {
        val target = resolver.targetFor(yshRow(albumName = "Great Stories - Volume 7"))
        assertEquals(AlbumNavTarget("ysh", "Great Stories - Volume 7"), target)
    }

    @Test
    fun `YSH row with no stored album and no catalog resolves to null`() {
        assertNull(resolver.targetFor(yshRow(albumName = null)))
    }

    @Test
    fun `AIO row with a title absent from the catalog resolves to null`() {
        val aio = yshRow(albumName = null).copy(
            providerId = "aio",
            externalId = "12345",
            title = "This Title Is Definitely Not In The Catalog 9f3a",
        )
        assertNull(resolver.targetFor(aio))
    }

    @Test
    fun `unknown provider resolves to null`() {
        assertNull(resolver.targetFor(yshRow(albumName = "x").copy(providerId = "rss-abc")))
    }

    private fun yshRow(albumName: String?) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = "ysh-sku-559",
        title = "Some Story",
        airDate = "2021-01-01",
        description = null,
        sourceUrl = "https://src/559",
        downloadUrl = "https://dl/559.mp3",
        filePath = null,
        fileSize = 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = null,
        archivedAt = null,
        imageUrl = null,
        albumName = albumName,
    )
}
