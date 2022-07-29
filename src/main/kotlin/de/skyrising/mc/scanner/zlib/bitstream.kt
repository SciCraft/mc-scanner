package de.skyrising.mc.scanner.zlib

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException


class BitStream(private val buf: ByteBuffer) {
    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
    }
    private var offset = buf.position()
    private var overrun = 0
    private var bitbuf: Long = 0
    private var bitsleft = 0

    private fun fillBitsSlow(bytes: Int) {
        for(i in 0 until bytes) {
            val off = offset++
            val buf = this.buf
            val bl = bitsleft
            if (off < buf.limit()) {
                bitbuf = bitbuf or ((buf.get(off).toLong() and 0xffL) shl bl)
            } else {
                overrun++
                offset--
            }
            bitsleft = bl + 8
        }
    }

    private fun fillBitsFast(bytes: Int, off: Int, bl: Int) {
        bitbuf = bitbuf or (buf.getLong(off) shl bl)
        bitsleft = bl + (bytes shl 3)
        offset = off + bytes
    }

    private fun fillBits() {
        val bl = bitsleft
        val off = offset
        val desired = (64 - bl) shr 3
        if (buf.limit() - off >= 8) {
            fillBitsFast(desired, off, bl)
        } else {
            fillBitsSlow(desired)
        }
    }

    fun ensureBits(bits: Int) {
        if (bits > BITS_MAX) throw IllegalArgumentException()
        if (bitsleft < bits) fillBits()
        if (bitsleft < bits) throw EOFException()
    }

    fun align() {
        if (overrun > bitsleft / 8) throw DataFormatException()
        offset -= bitsleft / 8 - overrun
        overrun = 0
        bitbuf = 0
        bitsleft = 0
    }

    fun end() {
        align()
        buf.position(offset)
    }

    fun peekBits(n: Int): Int {
        ensureBits(n)
        return bitbuf.toInt() and ((1 shl n) - 1)
    }

    fun removeBits(n: Int) {
        bitbuf = bitbuf ushr n
        bitsleft -= n
    }

    fun popBits(n: Int): Int {
        val bits = peekBits(n)
        removeBits(n)
        return bits
    }

    fun readUnsignedShort(): Int {
        val value = buf.getShort(offset).toInt() and 0xffff
        offset += 2
        return value
    }

    fun copyBytes(b: ByteArray, off: Int, len: Int) {
        val p = buf.position()
        buf.position(offset)
        buf.get(b, off, len)
        buf.position(p)
        offset += len
    }
}