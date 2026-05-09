package com.odyssey.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerQrCodecTest {
    @Test
    fun `round-trip encode-decode preserves url and token`() {
        val payload = encodeServerQr("http://archive.example:8088", "abc123")
        val decoded = decodeServerQr(payload)
        assertEquals("http://archive.example:8088", decoded?.url)
        assertEquals("abc123", decoded?.token)
        assertEquals("", decoded?.cfClientId)
        assertEquals("", decoded?.cfClientSecret)
    }

    @Test
    fun `round-trip carries optional Cloudflare Access service tokens when present`() {
        val payload = encodeServerQr(
            url = "https://aio.example.com",
            token = "abc",
            cfClientId = "id-xyz.access",
            cfClientSecret = "supersecret",
        )
        val decoded = decodeServerQr(payload)
        assertEquals("id-xyz.access", decoded?.cfClientId)
        assertEquals("supersecret", decoded?.cfClientSecret)
    }

    @Test
    fun `decoding an old LAN-only QR (no cf fields in JSON) still succeeds with blank cf strings`() {
        val decoded = decodeServerQr("odyssey-server:{\"url\":\"http://lan\",\"token\":\"t\"}")
        assertEquals("http://lan", decoded?.url)
        assertEquals("t", decoded?.token)
        assertEquals("", decoded?.cfClientId)
        assertEquals("", decoded?.cfClientSecret)
    }

    @Test
    fun `decode trims whitespace before encoding so scanned codes round-trip cleanly`() {
        val payload = encodeServerQr("  http://x:8088  ", "  tok  ")
        val decoded = decodeServerQr(payload)
        assertEquals("http://x:8088", decoded?.url)
        assertEquals("tok", decoded?.token)
    }

    @Test
    fun `decode rejects non-Odyssey QR strings`() {
        assertNull(decodeServerQr("https://example.com"))
        assertNull(decodeServerQr("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(decodeServerQr(""))
    }

    @Test
    fun `decode rejects an Odyssey-prefixed but malformed payload`() {
        assertNull(decodeServerQr("odyssey-server:not-json"))
        assertNull(decodeServerQr("odyssey-server:{\"url\":\"x\"}")) // missing token
    }

    @Test
    fun `decode rejects payloads with blank fields — a blank token would silently kill auth`() {
        assertNull(decodeServerQr("odyssey-server:{\"url\":\"\",\"token\":\"x\"}"))
        assertNull(decodeServerQr("odyssey-server:{\"url\":\"x\",\"token\":\"\"}"))
    }

    @Test
    fun `encoded payload starts with the canonical odyssey-server prefix so scanners can filter`() {
        val payload = encodeServerQr("u", "t")
        assertTrue(payload.startsWith("odyssey-server:"))
    }
}
