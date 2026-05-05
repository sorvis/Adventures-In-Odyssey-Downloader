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
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0
                else ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

enum class TransferKind { DOWNLOAD, UPLOAD }

/**
 * Combine the two in-flight progress maps into a single sorted list
 * for the Transfers screen. Sort: kind ASC (downloads first), then
 * episodeId ASC for stability. When the title isn't known (episode
 * row not yet inserted, etc.) falls back to "Episode <id>".
 *
 * Pure — tests live in TransferRowTest.
 */
fun mergeTransfers(
    downloads: Map<Long, DownloadProgressEntry>,
    uploads: Map<Long, DownloadProgressEntry>,
    titlesById: Map<Long, String>,
): List<TransferRow> {
    val rows = mutableListOf<TransferRow>()
    for ((id, p) in downloads) {
        rows += TransferRow(
            episodeId = id,
            title = titlesById[id] ?: "Episode $id",
            kind = TransferKind.DOWNLOAD,
            bytesTransferred = p.bytesRead,
            totalBytes = p.totalBytes,
        )
    }
    for ((id, p) in uploads) {
        rows += TransferRow(
            episodeId = id,
            title = titlesById[id] ?: "Episode $id",
            kind = TransferKind.UPLOAD,
            bytesTransferred = p.bytesRead,
            totalBytes = p.totalBytes,
        )
    }
    return rows.sortedWith(compareBy({ it.kind.ordinal }, { it.episodeId }))
}
