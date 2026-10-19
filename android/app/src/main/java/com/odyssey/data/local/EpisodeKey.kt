package com.odyssey.data.local

/**
 * The real identity of an episode: which show it came from, plus its
 * stable id within that show. This is the primary key of both
 * `local_episodes` and `playback_positions`.
 *
 * It exists as a type because the player layer spent a long time
 * passing around [derivedEpisodeId] instead — a bare Long — which is
 * LOSSY. Two shows share that Long namespace, and worse, every code
 * path that had to turn a Long back into a database key simply assumed
 * `"aio"`. That assumption filed every Your Story Hour play under
 * Adventures in Odyssey (reported 2026-06-14 and again 2026-10-19 as
 * "ysh and aio are mixed up").
 */
data class EpisodeKey(val providerId: String, val externalId: String)

/** Separator between the two halves of an encoded media id. */
private const val MEDIA_ID_SEP = ':'

/**
 * Media3 identifies items by a single opaque String, so the whole key
 * is packed into it.
 *
 * Only the FIRST separator is significant: YSH external ids look like
 * `ysh-sku-1958` today but nothing stops a future provider from using
 * an id that itself contains a colon, and silently truncating one would
 * corrupt the key rather than fail loudly.
 */
fun encodeMediaId(providerId: String, externalId: String): String =
    "$providerId$MEDIA_ID_SEP$externalId"

/**
 * Recover the key from a media id, or null when [mediaId] is in the
 * pre-v0.1.91 bare-Long form (or is junk).
 *
 * Null means "no provider information available", which callers must
 * handle rather than defaulting — defaulting is the bug this type was
 * introduced to kill.
 */
fun decodeMediaId(mediaId: String?): EpisodeKey? {
    val raw = mediaId ?: return null
    val at = raw.indexOf(MEDIA_ID_SEP)
    if (at <= 0 || at == raw.length - 1) return null
    return EpisodeKey(
        providerId = raw.substring(0, at),
        externalId = raw.substring(at + 1),
    )
}

/**
 * The lossy Long form, kept because a great deal of row-level UI state
 * (expanded, progress, "is this row playing") is keyed on it, and
 * because it is the ExoPlayer cache key.
 *
 * Numeric for AIO (the oneplace CMS id); a hash for YSH, whose sku ids
 * are not always numeric. Collisions are possible in principle and
 * vanishingly unlikely across a catalog this size — but note that a
 * collision is only a cosmetic row-state mixup now, not a corrupted
 * playback position, because positions are keyed on [EpisodeKey].
 */
fun derivedEpisodeId(externalId: String): Long =
    externalId.toLongOrNull() ?: externalId.hashCode().toLong()

/**
 * The Long id for a media item, accepting both the encoded form and the
 * legacy bare-Long form so items already loaded in a running player (or
 * cached by a MediaSession that outlived the upgrade) keep working.
 */
fun episodeIdFromMediaId(mediaId: String?): Long? {
    val key = decodeMediaId(mediaId)
    if (key != null) return derivedEpisodeId(key.externalId)
    return mediaId?.toLongOrNull()
}

/**
 * The key to store a playback position under.
 *
 * Falls back to AIO only for a legacy bare-Long media id — an item
 * built before v0.1.91 and still loaded in a player that outlived the
 * upgrade. That is the one case where the provider genuinely is not
 * recoverable, and AIO is the right guess precisely because every
 * pre-v0.1.91 write was already filed under AIO: the fallback
 * reproduces the old row rather than inventing a new one.
 *
 * Every freshly built MediaItem carries a real key, so this must never
 * be the normal path.
 */
fun storageKeyFor(mediaId: String?, episodeId: Long): EpisodeKey =
    decodeMediaId(mediaId) ?: EpisodeKey("aio", episodeId.toString())
