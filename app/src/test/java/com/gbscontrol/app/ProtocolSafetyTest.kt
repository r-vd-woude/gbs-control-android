package com.gbscontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSafetyTest {
    @Test
    fun `ASCII preset names use the full byte budget`() {
        assertEquals("abcdefghijklmnopqrstuvwx", truncateUtf8("abcdefghijklmnopqrstuvwxyz", 24))
    }

    @Test
    fun `multibyte preset names stop before a split code point`() {
        assertEquals("12345678901234567890éé", truncateUtf8("12345678901234567890ééé", 24))
        assertEquals("12345678901234567890🙂", truncateUtf8("12345678901234567890🙂x", 24))
    }

    @Test
    fun `applied sequence comparison handles uint32 wraparound`() {
        assertTrue(sequenceHasReached(42, 42))
        assertTrue(sequenceHasReached(43, 42))
        assertFalse(sequenceHasReached(41, 42))
        assertTrue(sequenceHasReached(0, 0xffff_ffffL))
    }

    @Test
    fun `UTF-8 truncation handles empty input and unpaired surrogates`() {
        assertEquals("", truncateUtf8("name", 0))
        assertEquals("", truncateUtf8("", 24))
        assertEquals("\ud800", truncateUtf8("\ud800x", 1))
        assertEquals("\udc00", truncateUtf8("\udc00x", 1))
        assertEquals("", truncateUtf8("\ud83d\ude42", 3))
    }

    @Test
    fun `confirmation polling backs off and caps at one second`() {
        assertEquals(250L, confirmationPollDelayMs(0))
        assertEquals(400L, confirmationPollDelayMs(1))
        assertEquals(650L, confirmationPollDelayMs(2))
        assertEquals(1_000L, confirmationPollDelayMs(3))
        assertEquals(1_000L, confirmationPollDelayMs(100))
    }
}
