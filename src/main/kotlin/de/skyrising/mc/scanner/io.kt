package de.skyrising.mc.scanner

import java.io.*
import java.nio.ByteBuffer
import java.util.zip.*

class ByteBufferDataInput(private val buf: ByteBuffer) : DataInput {
    override fun readFully(b: ByteArray) {
        if (buf.remaining() < b.size) throw EOFException()
        buf.get(b)
    }

    override fun readFully(b: ByteArray, off: Int, len: Int) {
        if (buf.remaining() < len) throw EOFException()
        buf.get(b, off, len)
    }

    override fun skipBytes(n: Int): Int {
        buf.position(buf.position() + n)
        return n
    }

    override fun readBoolean() = buf.get() != 0.toByte()
    override fun readByte() = buf.get()
    override fun readUnsignedByte() = buf.get().toInt() and 0xff
    override fun readShort() = buf.short
    override fun readUnsignedShort() = buf.short.toInt() and 0xffff
    override fun readChar() = buf.char
    override fun readInt() = buf.int
    override fun readLong() = buf.long
    override fun readFloat() = buf.float
    override fun readDouble() = buf.double

    override fun readLine(): String {
        TODO("Not yet implemented")
    }

    override fun readUTF(): String {
        val utflen = readUnsignedShort()
        if (buf.remaining() < utflen) throw EOFException()
        var count = 0
        val pos = buf.position()
        val chars = CharArray(utflen)
        while (count < utflen) {
            val c = buf[pos + count].toInt() and 0xff
            chars[count] = c.toChar()
            if (c > 127) break
            count++
        }
        if (count == utflen) {
            skipBytes(count)
            return String(chars)
        }
        return readUTF(chars, count, pos)
    }

    private fun readUTF(chararr: CharArray, start: Int, pos: Int): String {
        var count = start
        var char2: Int
        var char3: Int
        var chararrCount = 0
        while (count < chararr.size) {
            val c = buf[pos + count].toInt() and 0xff
            when (c shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> { /* 0xxxxxxx*/
                    count++
                    chararr[chararrCount++] = c.toChar()
                }
                12, 13 -> { /* 110x xxxx   10xx xxxx*/
                    count += 2
                    if (count > chararr.size) throw UTFDataFormatException(
                            "malformed input: partial character at end")
                    char2 = buf[pos + count - 1].toInt()
                    if (char2 and 0xC0 != 0x80) throw UTFDataFormatException(
                            "malformed input around byte $count")
                    chararr[chararrCount++] = (c and 0x1F shl 6 or
                            (char2 and 0x3F)).toChar()
                }
                14 -> { /* 1110 xxxx  10xx xxxx  10xx xxxx */
                    count += 3
                    if (count > chararr.size) throw UTFDataFormatException(
                            "malformed input: partial character at end")
                    char2 = buf[pos + count - 2].toInt()
                    char3 = buf[pos + count - 1].toInt()
                    if (char2 and 0xC0 != 0x80 || char3 and 0xC0 != 0x80) throw UTFDataFormatException(
                            "malformed input around byte " + (count - 1))
                    chararr[chararrCount++] = (c and 0x0F shl 12 or
                            (char2 and 0x3F shl 6) or
                            (char3 and 0x3F shl 0)).toChar()
                }
                else -> throw UTFDataFormatException(
                        "malformed input around byte $count")
            }
        }
        skipBytes(chararr.size)
        return String(chararr, 0, chararrCount)
    }
}

var useLibDeflate = false

fun inflate(buf: ByteBuffer, arr: ByteArray? = null) = inflateJava2(buf, arr)
fun gunzip(buf: ByteBuffer, arr: ByteArray? = null) = gunzipJava(buf, arr)

fun inflateJava2(buf: ByteBuffer, arr: ByteArray? = null): ByteBuffer {
    val length = buf.remaining()
    var bytes = arr ?: ByteArray(length)
    if (bytes.size < length) bytes = ByteArray(length)
    buf.get(bytes, 0, length)
    val inflater = Inflater()
    inflater.setInput(bytes.copyOfRange(0, length))
    var uncompressedBytes = inflater.inflate(bytes)
    while (!inflater.finished()) {
        if (inflater.needsInput() || inflater.needsDictionary()) throw ZipException()
        bytes = bytes.copyOf(bytes.size * 2)
        uncompressedBytes += inflater.inflate(bytes, uncompressedBytes, bytes.size - uncompressedBytes)
    }
    inflater.end()
    return ByteBuffer.wrap(bytes, 0, uncompressedBytes)
}

fun inflateJava(buf: ByteBuffer, arr: ByteArray? = null): ByteBuffer {
    val length = buf.remaining()
    var bytes = arr ?: ByteArray(length)
    if (bytes.size < length) bytes = ByteArray(length)
    buf.get(bytes, 0, length)
    val inflater = InflaterInputStream(ByteArrayInputStream(bytes, 0, length))
    return ByteBuffer.wrap(inflater.readBytes())
}

fun gunzipJava(buf: ByteBuffer, arr: ByteArray? = null): ByteBuffer {
    val length = buf.remaining()
    var bytes = arr ?: ByteArray(length)
    if (bytes.size < length) bytes = ByteArray(length)
    buf.get(bytes, 0, length)
    val gzip = GZIPInputStream(ByteArrayInputStream(bytes, 0, length))
    return ByteBuffer.wrap(gzip.readBytes())
}