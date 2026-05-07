package com.odyssey.download

/**
 * One row in the unified Transfers screen. Pure data so the merge
 * logic is JVM-testable.
 */
data class TransferRow(
    val episodeId: Long,
    val title: String,
    val kind: TransferKind,
    val bytesTransferred: Long,
    val totalBytes: Long,
    /**
     * QUEUED rows are episodes WorkManager has enqueued but not yet
     * started transferring bytes for. ACTIVE rows have a live byte
     * count from a Tracker. Settings → Backup says "N waiting" using
     * the same set of rows that produces QUEUED entries here, so
     * showing them keeps the two screens in sync.
     */
    val state: TransferState = TransferState.ACTIVE,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0
                else ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

enum class TransferKind { DOWNLOAD, UPLOAD }
enum class TransferState { ACTIVE, QUEUED }

/**
 * Combine the in-flight progress maps + the queued-uploads list into
 * a single sorted list for the Transfers screen. Sort: state (ACTIVE
 * first), then kind, then episodeId. ACTIVE uploads suppress the
 * QUEUED entry for the same episode — same row, just upgraded as
 * bytes start flowing.
 *
 * Pure — tests live in TransferRowTest.
 */
fun mergeTransfers(
    downloads: Map<Long, DownloadProgressEntry>,
    uploads: Map<Long, DownloadProgressEntry>,
    titlesById: Map<Long, String>,
    queuedUploadIds: Set<Long> = emptySet(),
): List<TransferRow> {
    val rows = mutableListOf<TransferRow>()
    for ((id, p) in downloads) {
        rows += TransferRow(
            episodeId = id,
            title = titlesById[id] ?: "Episode $id",
            kind = TransferKind.DOWNLOAD,
            bytesTransferred = p.bytesRead,
            totalBytes = p.totalBytes,
            state = TransferState.ACTIVE,
        )
    }
    for ((id, p) in uploads) {
        rows += TransferRow(
            episodeId = id,
            title = titlesById[id] ?: "Episode $id",
            kind = TransferKind.UPLOAD,
            bytesTransferred = p.bytesRead,
            totalBytes = p.totalBytes,
            state = TransferState.ACTIVE,
        )
    }
    // QUEUED uploads — but only for episodes NOT already actively
    // streaming. Once bytes start flowing the ACTIVE row above
    // replaces the QUEUED entry for the same episode.
    val activeUploadIds = uploads.keys
    for (id in queuedUploadIds) {
        if (id in activeUploadIds) continue
        rows += TransferRow(
            episodeId = id,
            title = titlesById[id] ?: "Episode $id",
            kind = TransferKind.UPLOAD,
            bytesTransferred = 0L,
            totalBytes = 0L,
            state = TransferState.QUEUED,
        )
    }
    return rows.sortedWith(
        compareBy({ it.state.ordinal }, { it.kind.ordinal }, { it.episodeId }),
    )
}
