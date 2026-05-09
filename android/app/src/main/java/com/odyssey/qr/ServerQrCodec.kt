package com.odyssey.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * QR-shareable backup-server credentials. The QR payload is plain
 * text so any general-purpose scanner can read it; the
 * `odyssey-server:` prefix lets the in-app scanner reject random
 * QRs without false-positiving on every URL the user might point
 * the camera at.
 *
 * `cfClientId` / `cfClientSecret` carry an optional Cloudflare
 * Access service-token pair — populated when the server is fronted
 * by a Cloudflare Tunnel + Access app, so a friend who scans the
 * QR walks away with everything needed to reach the public URL
 * (no separate "now also paste these two strings" step). Empty
 * strings on a LAN-only / Tailscale-only deployment.
 */
@Serializable
data class ServerQrPayload(
    val url: String,
    val token: String,
    val cfClientId: String = "",
    val cfClientSecret: String = "",
)

private const val PREFIX = "odyssey-server:"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Encode the full credential set as the canonical QR string. Used
 * by the "Show QR" affordance on Settings so another phone can
 * paint it via Scan instead of typing a 64-character bearer token
 * (and now an optional Cloudflare service-token pair).
 */
fun encodeServerQr(
    url: String,
    token: String,
    cfClientId: String = "",
    cfClientSecret: String = "",
): String = PREFIX + json.encodeToString(
    ServerQrPayload.serializer(),
    ServerQrPayload(
        url = url.trim(),
        token = token.trim(),
        cfClientId = cfClientId.trim(),
        cfClientSecret = cfClientSecret.trim(),
    ),
)

/**
 * Try to parse a scanned QR string back into a [ServerQrPayload].
 * Returns null for any non-Odyssey QR or malformed payload — the
 * caller surfaces a "not a backup-server QR" snackbar.
 *
 * The CF fields are optional: a payload from a LAN-only deployment
 * (no Cloudflare in front) decodes successfully with empty strings
 * for cfClientId / cfClientSecret.
 */
fun decodeServerQr(scanned: String): ServerQrPayload? {
    if (!scanned.startsWith(PREFIX)) return null
    val body = scanned.removePrefix(PREFIX)
    return runCatching { json.decodeFromString(ServerQrPayload.serializer(), body) }.getOrNull()
        ?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
}
