package com.odyssey.download

/**
 * Progress snapshot for an in-flight download. Pure data — JVM-testable
 * so the percent math (zero-division, clamping) is locked down without a
 * test harness.
 */
data class DownloadProgressEntry(
    val bytesRead: Long,
    val totalBytes: Long,
) {
    /**
     * 0..100 inclusive. Returns 0 when totalBytes is unknown (the server
     * didn't send Content-Length on a Range response, etc.) so the UI
     * can render an indeterminate bar instead of NaN%.
     */
    val percent: Int
        get() = if (totalBytes <= 0L) 0
                else ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
}
