package com.odyssey.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Transfers screen merge logic + percent math.
 * Covers what the UI relies on: order stability, kind filtering,
 * fallback titles when an episode row hasn't been observed yet,
 * and zero-division on totalBytes.
 */
class TransferRowTest {

    @Test
    fun `mergeTransfers puts downloads before uploads, both ordered by id`() {
        val out = mergeTransfers(
            downloads = mapOf(
                200L to DownloadProgressEntry(50L, 100L),
                100L to DownloadProgressEntry(25L, 100L),
            ),
            uploads = mapOf(
                300L to DownloadProgressEntry(60L, 100L),
                250L to DownloadProgressEntry(40L, 100L),
            ),
            titlesById = mapOf(
                100L to "A", 200L to "B", 250L to "C", 300L to "D",
            ),
        )
        assertEquals(
            listOf(
                Triple(100L, TransferKind.DOWNLOAD, "A"),
                Triple(200L, TransferKind.DOWNLOAD, "B"),
                Triple(250L, TransferKind.UPLOAD, "C"),
                Triple(300L, TransferKind.UPLOAD, "D"),
            ),
            out.map { Triple(it.episodeId, it.kind, it.title) },
        )
    }

    @Test
    fun `missing title falls back to Episode id-string`() {
        val out = mergeTransfers(
            downloads = mapOf(999L to DownloadProgressEntry(1L, 10L)),
            uploads = emptyMap(),
            titlesById = emptyMap(),
        )
        assertEquals("Episode 999", out.single().title)
    }

    @Test
    fun `percent zero when total is unknown - LinearProgressIndicator falls back to indeterminate`() {
        val row = TransferRow(1L, "x", TransferKind.DOWNLOAD, 5L, 0L)
        assertEquals(0, row.percent)
    }

    @Test
    fun `percent clamps to 100 when bytes overshoots total`() {
        // Range responses can over-report; the chip should still cap at 100.
        val row = TransferRow(1L, "x", TransferKind.UPLOAD, 200L, 100L)
        assertEquals(100, row.percent)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(mergeTransfers(emptyMap(), emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `queued upload ids surface as QUEUED rows`() {
        val out = mergeTransfers(
            downloads = emptyMap(),
            uploads = emptyMap(),
            titlesById = mapOf(101L to "A", 102L to "B"),
            queuedUploadIds = setOf(101L, 102L),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.state == TransferState.QUEUED })
        assertTrue(out.all { it.kind == TransferKind.UPLOAD })
        assertEquals(listOf(101L, 102L), out.map { it.episodeId })
    }

    @Test
    fun `active upload suppresses the queued row for the same episode`() {
        val out = mergeTransfers(
            downloads = emptyMap(),
            uploads = mapOf(101L to DownloadProgressEntry(50L, 100L)),
            titlesById = mapOf(101L to "A", 102L to "B"),
            queuedUploadIds = setOf(101L, 102L),
        )
        // Two rows: 101 ACTIVE (50% bytes), 102 QUEUED.
        assertEquals(2, out.size)
        val by101 = out.first { it.episodeId == 101L }
        assertEquals(TransferState.ACTIVE, by101.state)
        assertEquals(50, by101.percent)
        val by102 = out.first { it.episodeId == 102L }
        assertEquals(TransferState.QUEUED, by102.state)
    }

    @Test
    fun `active rows sort before queued rows`() {
        val out = mergeTransfers(
            downloads = emptyMap(),
            uploads = mapOf(200L to DownloadProgressEntry(10L, 100L)),
            titlesById = mapOf(100L to "queued-A", 200L to "active-A"),
            queuedUploadIds = setOf(100L),
        )
        assertEquals(listOf(TransferState.ACTIVE, TransferState.QUEUED), out.map { it.state })
    }
}
