package de.skyrising.mc.scanner

import java.io.EOFException

import java.nio.ByteBuffer

import java.nio.channels.ReadableByteChannel
import java.util.*


fun readFully(channel: ReadableByteChannel, b: ByteBuffer) {
    val expectedLength: Int = b.remaining()
    var read = 0
    do {
        val readNow = channel.read(b)
        if (readNow <= 0) {
            break
        }
        read += readNow
    } while (read < expectedLength)
    if (read < expectedLength) {
        throw EOFException()
    }
}

fun formatSize(size: Double): String {
    val kib = size / 1024
    if (kib < 1024) return String.format(Locale.ROOT, "%.1fKiB", kib)
    val mib = kib / 1024
    if (mib < 1024) return String.format(Locale.ROOT, "%.1fMiB", mib)
    val gib = mib / 1024
    if (gib < 1024) return String.format(Locale.ROOT, "%.1fGiB", gib)
    return String.format(Locale.ROOT, "%.1fTiB", gib / 1024)
}

fun hexdump(buf: ByteBuffer, addr: Int = 0): String {
    val readerIndex = buf.position()
    val writerIndex = buf.limit()
    val line = ByteArray(16)
    val length = writerIndex - readerIndex
    if (length == 0) return "empty"
    var digits = 0
    for (i in 0..30) {
        if ((addr + length - 1) shr i == 0) {
            digits = i + 3 shr 2
            break
        }
    }
    var i = 0
    val sb = StringBuilder()
    sb.append(String.format("%0" + digits + "x: ", addr + i))
    while (i < length) {
        val b = buf[readerIndex + i]
        if (i > 0 && i and 0xf == 0) {
            sb.append("| ")
            for (j in 0..15) {
                val c = line[j]
                sb.append(if (c < 0x20 || c >= 0x7f) '.' else c.toChar())
            }
            sb.append('\n')
            sb.append(String.format("%0" + digits + "x: ", addr + i))
        }
        sb.append(String.format("%02x ", b.toInt() and 0xff))
        line[i and 0xf] = b
        i++
    }
    if (i > 0) {
        i = (i - 1 and 0xf) + 1
        for (j in 16 downTo i + 1) sb.append("   ")
        sb.append("| ")
        for (j in 0 until i) {
            val c = line[j]
            sb.append(if (c < 0x20 || c >= 0x7f) '.' else c.toChar())
        }
        sb.append('\n')
    }
    return sb.toString()
}