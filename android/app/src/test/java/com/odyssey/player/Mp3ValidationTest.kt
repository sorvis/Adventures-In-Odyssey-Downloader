package com.odyssey.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3ValidationTest {

    @Test
    fun `ID3v2 tag header is recognized as MP3`() {
        // "ID3" + version + flags + size — a real ID3v2.4 header opener
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)
        assertTrue(looksLikeMp3(bytes))
    }

    @Test
    fun `MPEG frame sync 0xFF 0xFB is recognized as MP3`() {
        // 0xFF 0xFB = MPEG-1 Layer III, common for raw MP3s without ID3
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertTrue(looksLikeMp3(bytes))
    }

    @Test
    fun `MPEG frame sync 0xFF 0xF3 is recognized as MP3`() {
        // 0xFF 0xF3 = MPEG-2 Layer III
        val bytes = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x00, 0x00)
        assertTrue(looksLikeMp3(bytes))
    }

    @Test
    fun `MPEG frame sync 0xFF 0xE0 minimum sync pattern is recognized`() {
        // 0xFF 0xE0 — bare minimum sync (top 11 bits all 1)
        val bytes = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x00)
        assertTrue(looksLikeMp3(bytes))
    }

    @Test
    fun `HTML error page is rejected`() {
        // What you get when the server returns an error page with 200 OK
        val bytes = "<!DOCTYPE html><html>".toByteArray()
        assertFalse(looksLikeMp3(bytes))
    }

    @Test
    fun `JSON response is rejected`() {
        val bytes = """{"error":"unauthorized"}""".toByteArray()
        assertFalse(looksLikeMp3(bytes))
    }

    @Test
    fun `empty file is rejected`() {
        assertFalse(looksLikeMp3(ByteArray(0)))
    }

    @Test
    fun `tiny file (under 3 bytes) is rejected`() {
        assertFalse(looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
    }

    @Test
    fun `0xFF followed by non-sync byte is rejected`() {
        // 0xFF 0x00 isn't a valid frame sync (top bits not all 1)
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0x00)
        assertFalse(looksLikeMp3(bytes))
    }

    @Test
    fun `file starting with random binary data is rejected`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70)  // ftyp box (MP4)
        assertFalse(looksLikeMp3(bytes))
    }
}
