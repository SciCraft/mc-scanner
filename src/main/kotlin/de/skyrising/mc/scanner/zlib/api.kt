package de.skyrising.mc.scanner.zlib

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.DataFormatException

private const val VERIFY_CHECKSUM = false

fun decodeZlib(buf: ByteBuffer, arr: ByteArray? = null): ByteBuffer {
    val cmf = buf.get().toUInt() and 0xffu
    val flg = buf.get().toUInt() and 0xffu
    if ((cmf * 256u + flg) % 31u != 0u) throw DataFormatException("Invalid ZLIB header checksum")
    val method = cmf and 0x0fu
    if (method != ZLIB_METHOD_DEFLATE) throw UnsupportedOperationException("ZLIB method $method not supported")
    val fdict = (flg and 0x20u != 0u)
    if (fdict) throw UnsupportedOperationException("ZLIB dictionary not supported")

    val result = inflate(buf, arr)

    buf.order(ByteOrder.BIG_ENDIAN)
    val checksum = buf.int
    if (VERIFY_CHECKSUM) {
        val resultChecksum = Adler32().apply { update(result.slice()) }.value.toInt()
        if (resultChecksum != checksum) throw DataFormatException("ZLIB checksum mismatch")
    }

    return result
}

fun decodeGzip(buf: ByteBuffer, arr: ByteArray? = null): ByteBuffer {
    buf.order(ByteOrder.LITTLE_ENDIAN)
    val magic = buf.short.toUInt() and 0xffffu
    if (magic != GZIP_MAGIC) throw DataFormatException("Invalid GZIP header")
    val method = buf.get().toUInt() and 0xffu
    if (method != GZIP_METHOD_DEFLATE) throw UnsupportedOperationException("GZIP method $method not supported")
    val flags = buf.get().toUInt() and 0xffu
    buf.position(buf.position() + 6)
    if ((flags and GZIP_FLAG_EXTRA) != 0u) {
        buf.position(buf.position() + buf.short.toInt() and 0xffff)
    }
    if ((flags and GZIP_FLAG_NAME) != 0u) {
        while (buf.get() != 0u.toByte()) {/**/}
    }
    if ((flags and GZIP_FLAG_COMMENT) != 0u) {
        while (buf.get() != 0u.toByte()) {/**/}
    }
    if ((flags and GZIP_FLAG_HCRC) != 0u) {
        buf.short
    }

    val result = inflate(buf, arr)

    buf.order(ByteOrder.LITTLE_ENDIAN)
    val checksum = buf.int
    if (VERIFY_CHECKSUM) {
        val resultChecksum = CRC32().apply { update(result.slice()) }.value.toInt()
        if (resultChecksum != checksum) throw DataFormatException("GZIP checksum mismatch")
    }
    val size = buf.int
    if (result.remaining() != size) throw DataFormatException("GZIP size mismatch")

    return result
}

fun inflate(buf: ByteBuffer, arr: ByteArray? = null) = inflate(BitStream(buf), arr ?: ByteArray(buf.remaining()))