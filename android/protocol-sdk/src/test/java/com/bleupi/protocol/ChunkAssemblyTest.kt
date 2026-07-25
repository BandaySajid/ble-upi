package com.bleupi.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChunkAssemblyTest {

    @Test
    fun `assemble single chunk returns matching bytes`() {
        val assembly = ChunkAssembly(1)
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val complete = assembly.addChunk(0, data)
        assertTrue(complete)
        assertTrue(assembly.isComplete())
        assertArrayEquals(data, assembly.assemble())
    }

    @Test
    fun `assemble multiple chunks out of order`() {
        val assembly = ChunkAssembly(3)
        val chunk0 = byteArrayOf(0x10, 0x20)
        val chunk1 = byteArrayOf(0x30, 0x40)
        val chunk2 = byteArrayOf(0x50, 0x60)

        assertFalse(assembly.addChunk(2, chunk2))
        assertFalse(assembly.isComplete())
        assertFalse(assembly.addChunk(0, chunk0))
        assertFalse(assembly.isComplete())
        assertTrue(assembly.addChunk(1, chunk1))
        assertTrue(assembly.isComplete())

        val expected = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        assertArrayEquals(expected, assembly.assemble())
    }

    @Test
    fun `out of range chunk index returns false`() {
        val assembly = ChunkAssembly(2)
        assertFalse(assembly.addChunk(-1, byteArrayOf(0x01)))
        assertFalse(assembly.addChunk(2, byteArrayOf(0x01)))
        assertFalse(assembly.isComplete())
    }

    @Test
    fun `reset clears chunks and received count`() {
        val assembly = ChunkAssembly(2)
        assembly.addChunk(0, byteArrayOf(0x01))
        assertEquals(byteArrayOf(0x01), assembly.peekChunk0())

        assembly.reset()
        assertNull(assembly.peekChunk0())
        assertFalse(assembly.isComplete())

        assertTrue(assembly.addChunk(0, byteArrayOf(0x02)))
        assertTrue(assembly.addChunk(1, byteArrayOf(0x03)))
        assertTrue(assembly.isComplete())
    }
}
