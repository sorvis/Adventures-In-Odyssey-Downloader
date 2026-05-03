package com.odyssey.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `percent is 0 at zero bytes read`() {
        assertEquals(0, DownloadProgressEntry(0L, 1000L).percent)
    }

    @Test
    fun `percent is 100 when bytesRead equals totalBytes`() {
        assertEquals(100, DownloadProgressEntry(1000L, 1000L).percent)
    }

    @Test
    fun `percent rounds toward zero on integer division`() {
        // 999/2000 = 49.95% — Int division gives 49, not 50.
        assertEquals(49, DownloadProgressEntry(999L, 2000L).percent)
    }

    @Test
    fun `percent caps at 100 if bytesRead exceeds totalBytes`() {
        // Defensive: if a server lies about Content-Length the
        // computed percent could exceed 100 — clamp to 100.
        assertEquals(100, DownloadProgressEntry(2000L, 1000L).percent)
    }

    @Test
    fun `percent is 0 when totalBytes is unknown (zero or negative)`() {
        // Indeterminate progress — UI should show a spinner, not NaN.
        assertEquals(0, DownloadProgressEntry(500L, 0L).percent)
        assertEquals(0, DownloadProgressEntry(500L, -1L).percent)
    }

    @Test
    fun `percent handles very large totals without integer overflow`() {
        // 5GB total, 2.5GB read — bytes * 100 must promote to Long.
        val total = 5L * 1024 * 1024 * 1024
        val half = total / 2
        assertEquals(50, DownloadProgressEntry(half, total).percent)
    }

    @Test
    fun `percent is never negative for nonsensical input`() {
        assertEquals(0, DownloadProgressEntry(-100L, 1000L).percent)
    }
}
