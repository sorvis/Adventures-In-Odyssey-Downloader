package com.odyssey.player

/**
 * What [PlaybackRecovery] should do with a row whose downloaded file
 * ExoPlayer refused to parse.
 *
 * Pure policy — no IO, no Android, no per-provider branching — so it
 * runs in the fast JVM lane and behaves identically for every
 * `ShowProvider`. The provider-specific part (which worker re-fetches
 * the bytes) is expressed as [Redownload] vs [Restore]; the caller maps
 * those onto the provider-aware enqueue calls.
 */
sealed interface RecoveryAction {
    /** Nothing worth doing; [reason] goes straight into the log. */
    data class Skip(val reason: String) : RecoveryAction

    /** Delete the file and re-fetch it from the row's own downloadUrl. */
    data object Redownload : RecoveryAction

    /**
     * Delete the file and re-pull it from the NAS backup. Rows restored
     * from the archive service keep `downloadUrl = "backup://<id>"`,
     * which `DownloadEpisodeWorker` rejects on purpose (v0.1.75) — so a
     * corrupt restored file has to go back through `RestoreEpisodeWorker`
     * or it can never be repaired.
     */
    data object Restore : RecoveryAction
}

private const val BACKUP_URL_PREFIX = "backup://"

/**
 * Decide how to repair a row after ExoPlayer rejected its container.
 *
 * @param filePath the row's filePath. Null means the row is already
 *  streaming and there is nothing on disk to repair — defensive, since
 *  [PlaybackRecovery] only resolves rows that have a file.
 * @param downloadUrl the row's downloadUrl. A `backup://` URL means the
 *  bytes came from — and must come back from — the archive service.
 * @param firstBytes the first few bytes of the file on disk. Empty when
 *  the file is missing or unreadable, which counts as "not an MP3."
 */
fun decideRecovery(
    filePath: String?,
    downloadUrl: String,
    firstBytes: ByteArray,
): RecoveryAction = when {
    filePath == null ->
        RecoveryAction.Skip("row has no filePath (already streaming) — nothing to recover")
    looksLikeMp3(firstBytes) ->
        RecoveryAction.Skip("file is a valid MP3 — re-fetching would return the same bytes")
    downloadUrl.startsWith(BACKUP_URL_PREFIX) -> RecoveryAction.Restore
    else -> RecoveryAction.Redownload
}
