package com.odyssey.nas

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bad-data coverage for [NasMirror] — the v0.1.67 launch-time mirror
 * that backfills the local DB from the NAS catalog. Originally shipped
 * with zero tests (a habit that bit users with five releases of "still
 * broken" — see the 2026-05-22 conversation). Every scenario the
 * mirror can land in on a real device is covered: NAS reachable /
 * unreachable / paginated; catalog match miss; existing local row in
 * various states (filePath set, archivedAt missing, ghost from prior
 * mirror); idempotent re-run.
 *
 * Uses the real AioCatalogRepo loaded from `assets/aio_catalog.json`
 * — Robolectric serves assets, so the matcher exercises the same code
 * path the production app uses, not a synthetic fake that would let
 * matcher regressions slip past.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NasMirrorTest {

    private lateinit var ctx: Application
    private lateinit var server: MockWebServer
    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var catalog: AioCatalogRepo
    private lateinit var mirror: NasMirror

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries().build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        runBlocking {
            settings.clearAllForTest()
            settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        }
        catalog = AioCatalogRepo(ctx)
        val nas = NasClient(settings, OkHttpClient())
        mirror = NasMirror(nas, episodes, catalog)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    // ---- happy + boundary paths ------------------------------------------

    @Test
    fun `empty NAS catalog returns success with no inserts`() = runBlocking {
        server.enqueue(json("[]"))
        val outcome = mirror.run().getOrThrow()
        assertEquals(0, outcome.fetched)
        assertEquals(0, outcome.inserted)
        assertEquals(0, episodes.observeAll().first().size)
    }

    @Test
    fun `mirror inserts AIO-catalog-matching rows as backup ghosts`() = runBlocking {
        // "Clutter" is a real AIO episode in the bundled catalog
        // (broadcast #657, album #51). Pick titles the matcher
        // recognizes so the mirror's catalog filter lets them through.
        server.enqueue(json("""[
            {"episode_id":657,"title":"Clutter","file_size":18000000,"air_date":"2026-01-01"}
        ]"""))
        server.enqueue(json("[]"))

        val outcome = mirror.run().getOrThrow()
        assertEquals(1, outcome.inserted)
        assertEquals(0, outcome.skipped)

        val rows = episodes.observeAll().first()
        assertEquals(1, rows.size)
        val r = rows.single()
        assertEquals("aio", r.providerId)
        assertEquals("657", r.externalId)
        assertEquals("Clutter", r.title)
        assertNull("filePath stays null for mirror-inserted rows", r.filePath)
        assertEquals("sourceUrl is backup-mirror shape", "backup://657", r.sourceUrl)
        assertEquals("downloadUrl is backup-mirror shape", "backup://657", r.downloadUrl)
        assertNotNull("archivedAt set so Albums tab shows on-backup", r.archivedAt)
    }

    @Test
    fun `non-AIO server rows are skipped (no Sekulow leakage)`() = runBlocking {
        // The user's NAS may still hold pre-v0.1.59 cross-show leaks
        // (Sekulow, FOTF). Mirror must NOT re-insert them as aio rows —
        // they'd just be re-cleaned next launch by DownloadReconciler,
        // and meanwhile pollute the Albums tab. Title catalog-match is
        // the source-of-truth filter.
        server.enqueue(json("""[
            {"episode_id":9999,"title":"Jay Sekulow Live","file_size":1},
            {"episode_id":657,"title":"Clutter","file_size":1}
        ]"""))
        server.enqueue(json("[]"))

        val outcome = mirror.run().getOrThrow()
        assertEquals("Sekulow row should be skipped, Clutter kept", 1, outcome.inserted)
        assertEquals(1, outcome.skipped)
        assertEquals("only the AIO row makes it into the DB",
            setOf("657"), episodes.observeAll().first().map { it.externalId }.toSet())
    }

    @Test
    fun `existing downloaded row keeps its filePath -- mirror must not clobber`() = runBlocking {
        // Critical regression-class for the v0.1.63 era: if mirror over-
        // writes a row that has a real local file, the file is orphaned
        // on disk and Library tab loses the entry. Pin the contract.
        episodes.upsert(
            LocalEpisodeEntity(
                providerId = "aio",
                externalId = "657",
                title = "Clutter",
                airDate = "2025-12-25",
                description = "stored locally",
                sourceUrl = "https://oneplace.com/657",   // real CDN url
                downloadUrl = "https://zcast/657.mp3",
                filePath = "/data/odyssey/aio/657.mp3",
                fileSize = 18000000L,
                durationMs = 25 * 60_000L,
                downloadedAt = 1L,
                archivedAt = 2L,                            // already archived
            ),
        )
        server.enqueue(json("""[{"episode_id":657,"title":"Clutter","file_size":1,"air_date":"2026-01-01"}]"""))
        server.enqueue(json("[]"))

        mirror.run().getOrThrow()

        val r = episodes.byKey("aio", "657")!!
        assertEquals("filePath preserved", "/data/odyssey/aio/657.mp3", r.filePath)
        assertEquals("title preserved (not overwritten)", "Clutter", r.title)
        assertEquals("real CDN sourceUrl preserved (NOT rewritten to backup://)",
            "https://oneplace.com/657", r.sourceUrl)
        assertEquals("real CDN downloadUrl preserved",
            "https://zcast/657.mp3", r.downloadUrl)
        assertEquals("archivedAt preserved (idempotent)", 2L, r.archivedAt)
    }

    @Test
    fun `existing row with null archivedAt gets timestamped (backup is up-to-date)`() = runBlocking {
        // Row exists locally but the phone never recorded the archive
        // timestamp — e.g. the file was uploaded by a previous install
        // we lost state from. Mirror must set archivedAt so the Albums
        // tab badge lights up, WITHOUT touching filePath/title.
        episodes.upsert(
            LocalEpisodeEntity(
                providerId = "aio",
                externalId = "657",
                title = "Clutter",
                airDate = null,
                description = null,
                sourceUrl = "https://oneplace.com/657",
                downloadUrl = "https://zcast/657.mp3",
                filePath = "/data/odyssey/aio/657.mp3",
                fileSize = 1L,
                durationMs = 0L,
                downloadedAt = 1L,
                archivedAt = null,
            ),
        )
        server.enqueue(json("""[{"episode_id":657,"title":"Clutter","file_size":1}]"""))
        server.enqueue(json("[]"))

        mirror.run().getOrThrow()

        val r = episodes.byKey("aio", "657")!!
        assertNotNull("archivedAt populated", r.archivedAt)
        assertEquals("filePath preserved", "/data/odyssey/aio/657.mp3", r.filePath)
    }

    @Test
    fun `mirror handles paging across many NAS pages`() = runBlocking {
        // pageSize=200 by default. Stage 350 episodes — 2 full pages +
        // a short tail. All AIO titles drawn from the bundled catalog.
        // Use titles we know exist (any AIO album episode names work).
        val titles = catalog.catalog.albums.flatMap { it.episodes }.take(350).map { it.name }
        assertTrue("test prerequisite: bundled catalog has ≥350 episodes", titles.size == 350)
        val page1 = titles.subList(0, 200).mapIndexed { i, t ->
            """{"episode_id":${10000 + i},"title":"${t.replace("\"", "\\\"")}","file_size":1}"""
        }.joinToString(",")
        val page2 = titles.subList(200, 350).mapIndexed { i, t ->
            """{"episode_id":${10200 + i},"title":"${t.replace("\"", "\\\"")}","file_size":1}"""
        }.joinToString(",")
        server.enqueue(json("[$page1]"))
        server.enqueue(json("[$page2]"))

        val outcome = mirror.run().getOrThrow()
        assertEquals(350, outcome.fetched)
        assertEquals(
            "every catalog-matched row should land in the DB across both pages",
            350, episodes.observeAll().first().size,
        )
    }

    @Test
    fun `NAS unreachable surfaces as failure (does not silently no-op)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val r = mirror.run()
        assertTrue("503 must propagate so caller can log + retry later", r.isFailure)
        assertEquals("no rows touched on failure", 0, episodes.observeAll().first().size)
    }

    @Test
    fun `partial paging failure must NOT half-update the DB`() = runBlocking {
        // Real-world scenario: user has 352 episodes on NAS. Page 1
        // (200 items) succeeds; page 2 errors. The mirror must NOT
        // write the 200 it got to the DB and then claim success — that
        // leaves the local catalog inconsistent every time the NAS
        // hiccups mid-page. NasMirror relies on listAllEpisodes being
        // all-or-nothing.
        val titles = catalog.catalog.albums.flatMap { it.episodes }.take(200).map { it.name }
        assertTrue("test prerequisite: bundled catalog has ≥200 episodes", titles.size == 200)
        val page1 = titles.mapIndexed { i, t ->
            """{"episode_id":${20000 + i},"title":"${t.replace("\"", "\\\"")}","file_size":1}"""
        }.joinToString(",")
        // Page 1: full 200 items → listAllEpisodes will continue paging
        server.enqueue(json("[$page1]"))
        // Page 2: 500 → listAllEpisodes returns Result.failure
        server.enqueue(MockResponse().setResponseCode(500))

        val r = mirror.run()
        assertTrue("partial failure must surface as failure", r.isFailure)
        assertEquals(
            "no rows must be inserted when paging fails partway — all-or-nothing",
            0, episodes.observeAll().first().size,
        )
    }

    @Test
    fun `idempotent -- two runs against the same NAS state leave one row`() = runBlocking {
        // Pin idempotency. Recovery flows depend on being able to call
        // mirror.run() repeatedly (launch + pull-to-refresh + after
        // an upload) without accumulating duplicate rows.
        server.enqueue(json("""[{"episode_id":657,"title":"Clutter","file_size":1}]"""))
        server.enqueue(json("[]"))
        mirror.run().getOrThrow()

        server.enqueue(json("""[{"episode_id":657,"title":"Clutter","file_size":1}]"""))
        server.enqueue(json("[]"))
        mirror.run().getOrThrow()

        assertEquals(1, episodes.observeAll().first().size)
    }

    @Test
    fun `null air_date and description from server are accepted`() = runBlocking {
        // Older NAS rows (imported via drop-folder before metadata was
        // mandatory) may have null air_date / description. The mirror
        // must accept them — both fields are nullable in the entity.
        server.enqueue(json("""[
            {"episode_id":657,"title":"Clutter","file_size":1,"air_date":null,"description":null}
        ]"""))
        server.enqueue(json("[]"))
        mirror.run().getOrThrow()
        val r = episodes.byKey("aio", "657")!!
        assertNull(r.airDate)
        assertNull(r.description)
    }

    @Test
    fun `duplicate episode_id in same page collapses to one row`() = runBlocking {
        // Defensive: if the server somehow returns the same episode
        // twice (caching glitch, or import double-insert race), the
        // mirror must NOT insert two local rows — DB has a composite
        // PK on (providerId, externalId) so the second upsert wins.
        server.enqueue(json("""[
            {"episode_id":657,"title":"Clutter","file_size":1},
            {"episode_id":657,"title":"Clutter","file_size":2}
        ]"""))
        server.enqueue(json("[]"))
        mirror.run().getOrThrow()
        assertEquals(1, episodes.observeAll().first().size)
    }

    // ---- helpers ----------------------------------------------------------

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
