package com.odyssey.player

/**
 * Pure magic-byte sniff: does this look like the start of an MP3 file?
 * Two valid prefixes:
 *
 *   1. "ID3"  — ID3v2 tag header (most tagged MP3s on the web).
 *   2. 0xFF then a byte whose top 3 bits are 111 — raw MPEG frame sync.
 *      (Top 3 bits 111 = sync; remaining bits encode version + layer.)
 *
 * Anything else means the file isn't an MP3 — most commonly an HTML
 * error page the server returned with status 200, or a truncated /
 * mangled download.
 *
 * JVM-testable; no Android dependencies.
 */
fun looksLikeMp3(firstBytes: ByteArray): Boolean {
    if (firstBytes.size < 3) return false
    val b0 = firstBytes[0].toInt() and 0xFF
    val b1 = firstBytes[1].toInt() and 0xFF
    val b2 = firstBytes[2].toInt() and 0xFF
    // ID3v2 tag: literal "ID3"
    if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return true
    // Raw MPEG frame sync: 0xFF + top 3 bits of next byte are 111
    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
    return false
}
