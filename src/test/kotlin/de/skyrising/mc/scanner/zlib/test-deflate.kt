@file:OptIn(ExperimentalUnsignedTypes::class)

package de.skyrising.mc.scanner.zlib

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TestDeflate {
    @Test
    fun smartHuffmanSimple() {
        val code = byteArrayOf(
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
        )
        val input = ubyteArrayOf(0b000_00000u, 0b0_10000_10u, 0b1000_0100u, 0b10_10100_1u, 0b10110_000u, 0b000_10101u)
        val output = uintArrayOf(0x00u, 0x01u, 0x01u, 0x02u, 0x03u, 0x05u, 0x08u, 0x0du, 0x15u)
        val huffman = SmartHuffmanDecoder.fromBytes(code, 0, code.size)
        assertNotNull(huffman)
        val stream = BitStream(ByteBuffer.wrap(input.toByteArray()))
        for (i in output) {
            assertEquals(i, huffman.decode(stream))
        }
    }

    @Test
    fun smartHuffmanComplex() {
        val code = byteArrayOf(3, 2, 3, 3, 2, 3)
        val input = ubyteArrayOf(0b101_00_001u, 0b111_10_011u, 0b101_00_001u, 0b111_10_011u)
        val output = uintArrayOf(0u, 1u, 2u, 3u, 4u, 5u, 0u, 1u, 2u, 3u, 4u, 5u)
        val huffman = SmartHuffmanDecoder.fromBytes(code, 0, code.size)
        assertNotNull(huffman)
        val stream = BitStream(ByteBuffer.wrap(input.toByteArray()))
        for (i in output) {
            assertEquals(i, huffman.decode(stream))
        }
    }

    @Test
    fun decompressCompressedBlock() {
        val compressed = ubyteArrayOf(
            0x0Bu, 0xC9u, 0xC8u, 0x2Cu, 0x56u, 0x00u, 0xA2u, 0x44u,
            0x85u, 0xE2u, 0xCCu, 0xDCu, 0x82u, 0x9Cu, 0x54u, 0x85u,
            0x92u, 0xD4u, 0x8Au, 0x12u, 0x85u, 0xB4u, 0x4Cu, 0x20u,
            0xCBu, 0x4Au, 0x13u, 0x00u
        )
        val decompressed = inflate(ByteBuffer.wrap(compressed.toByteArray()))
        val decompressedArray = ByteArray(decompressed.remaining())
        decompressed.get(decompressedArray)
        assertEquals("This is a simple text file :)", decompressedArray.toString(Charsets.UTF_8))
    }
}