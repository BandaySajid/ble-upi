package com.bleupi.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CooldownManagerTest {

    @Test
    fun `same nonce twice within window suppressed`() {
        val manager = CooldownManager(windowDurationSeconds = 180)
        assertTrue(manager.shouldAllow(0x12345678, 1000))
        assertFalse(manager.shouldAllow(0x12345678, 1000))
    }

    @Test
    fun `different nonce from same merchant allowed`() {
        val manager = CooldownManager(windowDurationSeconds = 180)
        assertTrue(manager.shouldAllow(0x12345678, 1000))
        assertTrue(manager.shouldAllow(0x12345678, 1001))
    }

    @Test
    fun `different merchant same nonce allowed`() {
        val manager = CooldownManager(windowDurationSeconds = 180)
        assertTrue(manager.shouldAllow(0x12345678, 1000))
        assertTrue(manager.shouldAllow(0x87654321.toInt(), 1000))
    }

    @Test
    fun `reset clears entries for specific merchant`() {
        val manager = CooldownManager(windowDurationSeconds = 180)
        manager.shouldAllow(0xAAA, 1000)
        manager.shouldAllow(0xBBB, 1000)
        assertEquals(2, manager.activeEntries)

        manager.reset(0xAAA)
        assertTrue(manager.shouldAllow(0xAAA, 1000))
        assertFalse(manager.shouldAllow(0xBBB, 1000))
    }

    @Test
    fun `clear removes all entries`() {
        val manager = CooldownManager(windowDurationSeconds = 180)
        manager.shouldAllow(0xAAA, 1000)
        manager.shouldAllow(0xBBB, 2000)
        manager.clear()
        assertEquals(0, manager.activeEntries)
        assertTrue(manager.shouldAllow(0xAAA, 1000))
    }
}
