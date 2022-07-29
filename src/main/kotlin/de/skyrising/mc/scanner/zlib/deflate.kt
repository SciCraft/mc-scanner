package de.skyrising.mc.scanner.zlib

import java.nio.ByteBuffer
import java.util.*
import java.util.zip.DataFormatException

private const val DEFLATE_DEBUG = false

private fun readUncompressedBlockLength(stream: BitStream): Int {
    stream.align()
    val len = stream.readUnsignedShort()
    val nLen = stream.readUnsignedShort()
    if (len xor 0xffff != nLen) throw DataFormatException("invalid uncompressed block")
    return len
}

private fun decodeLength(symbol: Int, stream: BitStream) = when {
    symbol <= 264 -> symbol - 254
    symbol <= 284 -> {
        val extraBits = (symbol - 261) / 4
        (((symbol - 265) % 4 + 4) shl extraBits) + 3 + stream.popBits(extraBits)
    }
    symbol == 285 -> 258
    else -> throw DataFormatException("invalid length symbol")
}

fun decodeDistance(symbol: Int, stream: BitStream) = when {
    symbol <= 3 -> symbol + 1
    symbol <= 29 -> {
        val extraBits = symbol / 2 - 1
        ((symbol % 2 + 2) shl extraBits) + 1 + stream.popBits(extraBits)
    }
    else -> throw DataFormatException("invalid distance symbol $symbol")
}

private fun readCompressedBlock(stream: BitStream, literalCode: HuffmanDecoder, distanceCode: HuffmanDecoder?, out: OutputBuffer) {
    if (DEFLATE_DEBUG) println("compressed $literalCode $distanceCode")
    while (true) {
        val symbol = literalCode.decode(stream).toInt()
        when {
            symbol >= 286 -> throw DataFormatException("invalid symbol")
            symbol < 256 -> {
                if (DEFLATE_DEBUG) print("literal '")
                out.write(symbol.toUByte())
                if (DEFLATE_DEBUG) println("'")
                continue
            }
            symbol == 256 -> return
            else -> {
                if (distanceCode == null) throw DataFormatException("invalid symbol")
                val length = decodeLength(symbol, stream)
                val distanceSymbol = distanceCode.decode(stream).toInt()
                if (distanceSymbol >= 30) throw DataFormatException("invalid distance symbol")
                val distance = decodeDistance(distanceSymbol, stream)
                if (DEFLATE_DEBUG) print("backref -$distance,$length '")
                out.outputBackref(distance, length)
                if (DEFLATE_DEBUG) println("'")
            }
        }
    }
}

private fun decodeCodes(state: InflatorState, stream: BitStream) {
    val literalCodeCount = stream.popBits(5) + 257
    val distanceCodeCount = stream.popBits(5) + 1
    val codeLengthCount = stream.popBits(4) + 4

    val codeLengthsCodeLengths = state.codeLengthsCodeLengths
    Arrays.fill(codeLengthsCodeLengths, 0)
    for (i in 0 until codeLengthCount) {
        codeLengthsCodeLengths[CODE_LENGTHS_CODE_LENGTHS_ORDER[i].toInt()] = stream.popBits(3).toByte()
    }

    val codeLengthCode = HuffmanDecoder.fromBytes(codeLengthsCodeLengths) ?: throw DataFormatException("invalid code lengths code")

    val codeLengths = ByteArray(literalCodeCount + distanceCodeCount)
    var i = 0
    while (i < codeLengths.size) {
        val symbol = codeLengthCode.decode(stream)
        if (symbol > UShort.MAX_VALUE) throw DataFormatException("invalid code length")
        when (symbol.toUShort()) {
            DEFLATE_CODE_LENGTHS_COPY -> {
                val repeat = stream.popBits(2) + 3
                val value = codeLengths[i - 1]
                for (j in 0 until repeat) {
                    codeLengths[i++] = value
                }
            }
            DEFLATE_CODE_LENGTHS_REPEAT_3 -> {
                for (j in 0 until stream.popBits(3) + 3) codeLengths[i++] = 0
            }
            DEFLATE_CODE_LENGTHS_REPEAT_7 -> {
                for (j in 0 until stream.popBits(7) + 11) codeLengths[i++] = 0
            }
            else -> {
                codeLengths[i++] = symbol.toByte()
            }
        }
    }

    state.literalCode = HuffmanDecoder.fromBytes(codeLengths, 0, literalCodeCount) ?: throw DataFormatException("invalid literal code")

    if (distanceCodeCount == 1) {
        val length = codeLengths[literalCodeCount].toInt()
        if (length == 0) {
            state.distanceCode = null
            return
        }
        if (length != 1) throw DataFormatException("invalid distance code")
    }

    state.distanceCode = HuffmanDecoder.fromBytes(codeLengths, literalCodeCount, distanceCodeCount) ?: throw DataFormatException("invalid distance code")
}

private fun updateCodes(state: InflatorState, type: Int, stream: BitStream) = when (type) {
    DEFLATE_BLOCKTYPE_FIXED_HUFFMAN -> {
        state.literalCode = HuffmanDecoder.FIXED_LITERAL_CODES
        state.distanceCode = HuffmanDecoder.FIXED_DISTANCE_CODES
    }
    DEFLATE_BLOCKTYPE_DYNAMIC_HUFFMAN -> {
        decodeCodes(state, stream)
    }
    else -> throw DataFormatException("invalid block type")
}

private fun printChar(c: Char) {
    if (c in ' '..'~') {
        print(c)
    } else {
        print("\\x%02x".format(c.code and 0xff))
    }
}

class OutputBuffer(internal var arr: ByteArray) {
    internal var pos = 0

    fun ensureSpace(space: Int) {
        if (pos + space > arr.size) {
            expand(space)
        }
    }

    private fun expand(space: Int) {
        var newSize = arr.size
        while (pos + space > newSize) newSize *= 2
        arr = arr.copyOf(newSize)
    }

    fun write(b: UByte) {
        ensureSpace(1)
        arr[pos++] = b.toByte()
        if (DEFLATE_DEBUG) {
            printChar(b.toInt().toChar())
        }
    }

    fun outputBackref(distance: Int, length: Int) {
        ensureSpace(length)
        outputBackref(arr, pos, distance, length)
        if (DEFLATE_DEBUG) {
            for (i in 0 until length) {
                printChar(arr[pos + i].toInt().toChar())
            }
        }
        pos += length
    }

    fun buffer(): ByteBuffer {
        return ByteBuffer.wrap(arr, 0, pos)
    }
}

private fun outputBackref(arr: ByteArray, pos: Int, distance: Int, length: Int) {
    if (distance == 1) {
        Arrays.fill(arr, pos, pos + length, arr[pos - 1])
    } else if (length > distance) {
        outputBackrefBytewise(arr, pos, distance, length)
    } else {
        System.arraycopy(arr, pos - distance, arr, pos, length)
    }
}

private fun outputBackrefBytewise(arr: ByteArray, pos: Int, distance: Int, length: Int) {
    val srcPos = pos - distance
    for (i in 0 until length) {
        arr[pos + i] = arr[srcPos + i]
    }
}

private class InflatorState {
    var literalCode = HuffmanDecoder.FIXED_LITERAL_CODES
    var distanceCode: HuffmanDecoder? = HuffmanDecoder.FIXED_DISTANCE_CODES
    val codeLengthsCodeLengths = ByteArray(19)
}

fun inflate(stream: BitStream, arr: ByteArray): ByteBuffer {
    val state = InflatorState()
    val out = OutputBuffer(arr)
    var finalBlock = false
    while (!finalBlock) {
        stream.ensureBits(3)
        finalBlock = stream.popBits(1) != 0
        when (val type = stream.popBits(2)) {
            DEFLATE_BLOCKTYPE_UNCOMPRESSED -> {
                val len = readUncompressedBlockLength(stream)
                out.ensureSpace(len)
                stream.copyBytes(out.arr, out.pos, len)
                if (DEFLATE_DEBUG) {
                    print("uncompressed $len '")
                    for (i in out.pos until out.pos + len) {
                        val c = out.arr[i].toInt().toChar()
                        if (c in ' '..'~') {
                            print(c)
                        } else {
                            print("\\u%04x".format(c.toInt()))
                        }
                    }
                    println("'")
                }
                out.pos += len
            }
            DEFLATE_BLOCKTYPE_FIXED_HUFFMAN, DEFLATE_BLOCKTYPE_DYNAMIC_HUFFMAN -> {
                updateCodes(state, type, stream)
                readCompressedBlock(stream, state.literalCode, state.distanceCode, out)
            }
            else -> throw DataFormatException()
        }
    }
    stream.end()
    return out.buffer()
}