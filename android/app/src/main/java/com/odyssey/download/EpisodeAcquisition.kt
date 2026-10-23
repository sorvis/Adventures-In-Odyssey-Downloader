package com.odyssey.download

/**
 * Where an episode's audio should come from when the user asks for it
 * on the phone, and what to tell them about it.
 *
 * Pure so the decision can be tested without WorkManager or a network.
 * The logic used to live inside RecentVm, which meant the Recent tab
 * was the only surface in the app that chose correctly: the AIO album
 * screen always enqueued a backup restore (so it silently did nothing
 * for an episode that was never archived, or with no NAS configured),
 * and the YSH album screen had no download affordance at all.
 */
enum class AcquireSource {
    /** Pull from the NAS archive. */
    BACKUP,

    /** Download from the provider's public CDN. */
    CDN,
}

/**
 * Prefer the NAS over the public CDN when the server actually has the
 * episode AND a NAS is configured. LAN/Tailscale beats internet
 * bandwidth, and the archive is canonical — CDN copies rotate off,
 * which for YSH's free pool happens routinely.
 *
 * Steven's ask, 2026-05-23: "if recents has a server version always
 * prefer the server to download from."
 */
fun acquisitionSourceFor(archivedAt: Long?, nasConfigured: Boolean): AcquireSource =
    if (archivedAt != null && nasConfigured) AcquireSource.BACKUP else AcquireSource.CDN

/**
 * User-facing confirmation for a queued acquisition.
 *
 * [startsNow] is false when the work is held for WiFi, in which case
 * the title is omitted — the message is about the wait, not the
 * episode, and a long title buries the part that matters.
 */
fun acquisitionMessage(title: String, source: AcquireSource, startsNow: Boolean): String =
    when {
        source == AcquireSource.BACKUP && startsNow -> "Pulling from backup: $title"
        source == AcquireSource.BACKUP -> "Pull queued — will start on WiFi"
        startsNow -> "Download started: $title"
        else -> "Download queued — will start on WiFi"
    }

/**
 * Whether the enqueued work will begin immediately: either we are off
 * a metered network, or the user has opted into metered transfers.
 */
fun startsImmediately(onMeteredNetwork: Boolean, allowMetered: Boolean): Boolean =
    !onMeteredNetwork || allowMetered
