package com.odyssey.player

/**
 * Where to play an episode from. Local if it's already downloaded;
 * otherwise stream from the source URL.
 *
 * Pure data — no Android types — so the dispatch decision in
 * `RecentVm.play()` can be unit-tested on the JVM without Room.
 */
sealed interface PlaySource {
    data class Local(val filePath: String) : PlaySource
    data class Stream(val url: String) : PlaySource
}

fun playSourceFor(filePath: String?, downloadUrl: String): PlaySource =
    if (filePath != null) PlaySource.Local(filePath)
    else PlaySource.Stream(downloadUrl)
