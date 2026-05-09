package com.odyssey.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * QR-shareable backup-server credentials. The QR payload is plain
 * text so any general-purpose scanner can read it; the
 * `odyssey-server:` prefix lets the in-app scanner reject random
 * QRs without false-positiving on every URL the user might point
 * the camera at.
 */
@Serializable
data class ServerQrPayload(
    val url: String,
    val token: String,
)

private const val PREFIX = "odyssey-server:"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Encode `(url, token)` as the canonical QR string. Used by the
 * "Show QR" affordance on Settings so a second phone can paint it
 * via Scan instead of typing a 64-character bearer token.
 */
fun encodeServerQr(url: String, token: String): String =
    PREFIX + json.encodeToString(ServerQrPayload.serializer(), ServerQrPayload(url.trim(), token.trim()))

/**
 * Try to parse a scanned QR string back into a [ServerQrPayload].
 * Returns null for any non-Odyssey QR or malformed payload — the
 * caller surfaces a "not a backup-server QR" snackbar.
 */
fun decodeServerQr(scanned: String): ServerQrPayload? {
    if (!scanned.startsWith(PREFIX)) return null
    val body = scanned.removePrefix(PREFIX)
    return runCatching { json.decodeFromString(ServerQrPayload.serializer(), body) }.getOrNull()
        ?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
}
