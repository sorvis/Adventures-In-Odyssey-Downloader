package com.odyssey.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {

    private fun entry(i: Int) = DebugLogEntry(
        timestampMs = i.toLong(),
        level = LogLevel.DEBUG,
        tag = "T",
        message = "msg-$i",
    )

    @Test
    fun `appendCapped grows buffer until cap is reached`() {
        var buf = emptyList<DebugLogEntry>()
        repeat(3) { buf = appendCapped(buf, entry(it), cap = 5) }
        assertEquals(3, buf.size)
        assertEquals("msg-0", buf.first().message)
        assertEquals("msg-2", buf.last().message)
    }

    @Test
    fun `appendCapped drops oldest entries past the cap`() {
        var buf = emptyList<DebugLogEntry>()
        repeat(7) { buf = appendCapped(buf, entry(it), cap = 5) }
        assertEquals(5, buf.size)
        // Oldest two (msg-0, msg-1) are gone; msg-2..msg-6 remain.
        assertEquals("msg-2", buf.first().message)
        assertEquals("msg-6", buf.last().message)
    }

    @Test
    fun `appendCapped at cap=1 keeps only the latest`() {
        var buf = emptyList<DebugLogEntry>()
        repeat(10) { buf = appendCapped(buf, entry(it), cap = 1) }
        assertEquals(1, buf.size)
        assertEquals("msg-9", buf.single().message)
    }

    @Test
    fun `appendCapped with cap=0 returns an empty buffer`() {
        val buf = appendCapped(listOf(entry(0)), entry(1), cap = 0)
        assertTrue("cap 0 should erase the buffer", buf.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `appendCapped rejects negative caps`() {
        appendCapped(emptyList(), entry(0), cap = -1)
    }

    @Test
    fun `appendCapped preserves insertion order`() {
        var buf = emptyList<DebugLogEntry>()
        listOf(5, 1, 9, 2, 3).forEach { buf = appendCapped(buf, entry(it), cap = 10) }
        assertEquals(listOf("msg-5", "msg-1", "msg-9", "msg-2", "msg-3"), buf.map { it.message })
    }
}
