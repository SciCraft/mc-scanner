@file:OptIn(ExperimentalUnsignedTypes::class)

package de.skyrising.mc.scanner.zlib

interface HuffmanDecoder {
    fun decode(stream: BitStream): UInt

    companion object {
        val FIXED_LITERAL_CODES = fromBytes(FIXED_LITERAL_BIT_LENGTHS)!!
        val FIXED_DISTANCE_CODES = fromBytes(FIXED_DISTANCE_BIT_LENGTHS)!!

        fun fromBytes(buf: ByteArray) = fromBytes(buf, 0, buf.size)
        fun fromBytes(buf: ByteArray, off: Int, len: Int): HuffmanDecoder? = SmartHuffmanDecoder.fromBytes(buf, off, len)
    }
}

const val HUFFMAN_LUT_BITS = 9
const val HUFFMAN_LUT_MASK = (1 shl HUFFMAN_LUT_BITS) - 1
const val HUFFMAN_LUT_SYM_BITS = 9
const val HUFFMAN_LUT_SYM_SHIFT = 0
const val HUFFMAN_LUT_SYM_MASK = ((1 shl HUFFMAN_LUT_SYM_BITS) - 1) shl HUFFMAN_LUT_SYM_SHIFT
const val HUFFMAN_LUT_LEN_BITS = 7
const val HUFFMAN_LUT_LEN_SHIFT = HUFFMAN_LUT_SYM_SHIFT + HUFFMAN_LUT_SYM_BITS
const val HUFFMAN_LUT_LEN_MASK = ((1 shl HUFFMAN_LUT_LEN_BITS) - 1) shl HUFFMAN_LUT_LEN_SHIFT

const val MAX_HUFFMAN_BITS = 16
const val MAX_HUFFMAN_SYMBOLS = 288

class SmartHuffmanDecoder(
    private val lookupTable: UShortArray,
    private val sentinelBits: UIntArray,
    private val offsetFirstSymbolIndex: UIntArray,
    private val symbols: UShortArray
) : HuffmanDecoder {
    constructor() : this(
        UShortArray(1 shl HUFFMAN_LUT_BITS),
        UIntArray(MAX_HUFFMAN_BITS + 1),
        UIntArray(MAX_HUFFMAN_BITS + 1),
        UShortArray(MAX_HUFFMAN_SYMBOLS)
    )
    override fun decode(stream: BitStream): UInt {
        var bits = stream.peekBits(16)
        val lookupBits = bits and HUFFMAN_LUT_MASK
        val lutEntry = lookupTable[lookupBits].toInt()
        if (lutEntry and HUFFMAN_LUT_LEN_MASK != 0) {
            val len = (lutEntry and HUFFMAN_LUT_LEN_MASK) ushr HUFFMAN_LUT_LEN_SHIFT
            val sym = (lutEntry and HUFFMAN_LUT_SYM_MASK) ushr HUFFMAN_LUT_SYM_SHIFT
            stream.removeBits(len)
            return sym.toUInt()
        }
        bits = Integer.reverse(bits) ushr (32 - MAX_HUFFMAN_BITS)
        for (l in HUFFMAN_LUT_BITS + 1 .. MAX_HUFFMAN_BITS) {
            if (bits >= sentinelBits[l].toInt()) continue
            bits = bits ushr (MAX_HUFFMAN_BITS - l)
            val symIdx = (offsetFirstSymbolIndex[l].toInt() + bits) and 0xffff
            stream.removeBits(l)
            return symbols[symIdx].toUInt()
        }
        return UInt.MAX_VALUE
    }

    override fun toString(): String {
        val sb = StringBuilder("HuffmanDecoder([")
        for (i in lookupTable.indices) {
            if (i > 0) sb.append(", ")
            val lutEntry = lookupTable[i].toInt()
            val len = (lutEntry and HUFFMAN_LUT_LEN_MASK) ushr HUFFMAN_LUT_LEN_SHIFT
            val sym = (lutEntry and HUFFMAN_LUT_SYM_MASK) ushr HUFFMAN_LUT_SYM_SHIFT
            sb.append('(').append(len).append(',').append(sym).append(')')
        }
        sb.append("], ").append(sentinelBits.contentToString())
          .append(", ").append(offsetFirstSymbolIndex.contentToString())
          .append(", ").append(symbols.contentToString())
          .append(')')
        return sb.toString()
    }

    companion object {
        private fun count(buf: ByteArray, off: Int, len: Int): UShortArray {
            val count = UShortArray(MAX_HUFFMAN_BITS + 1)
            for (i in 0 until len) {
                count[buf[off + i].toInt()]++
            }
            count[0] = 0u
            return count
        }

        fun fromBytes(buf: ByteArray, off: Int, len: Int): SmartHuffmanDecoder? {
            val d = SmartHuffmanDecoder()

            val count = count(buf, off, len)
            val code = UShortArray(MAX_HUFFMAN_BITS + 1)
            val symbolIndex = UShortArray(MAX_HUFFMAN_BITS + 1)

            for (l in 1..MAX_HUFFMAN_BITS) {
                val codePlusCountPrev = code[l - 1] + count[l - 1]
                code[l] = (codePlusCountPrev shl 1).toUShort()
                val codePlusCount = code[l] + count[l]
                if (count[l] != 0u.toUShort() && codePlusCount > 1u shl l) return null
                d.sentinelBits[l] = codePlusCount shl (MAX_HUFFMAN_BITS - l)

                symbolIndex[l] = (symbolIndex[l - 1] + count[l - 1]).toUShort()
                d.offsetFirstSymbolIndex[l] = symbolIndex[l] - code[l]
            }

            for (i in 0 until len) {
                val l = buf[off + i].toInt()
                if (l == 0) continue
                d.symbols[symbolIndex[l].toInt()] = i.toUShort()
                symbolIndex[l]++
                if (l <= HUFFMAN_LUT_BITS) {
                    tableInsert(d.lookupTable, i, l, code[l])
                    code[l]++
                }
            }
            return d
        }

        private fun tableInsert(lookupTable: UShortArray, symbol: Int, length: Int, code: UShort) {
            val reverseCode = Integer.reverse(code.toInt()) ushr (32 - length)
            val padLen = HUFFMAN_LUT_BITS - length
            for (padding in 0 until (1 shl padLen)) {
                val index = reverseCode or (padding shl length)
                lookupTable[index] = ((length shl HUFFMAN_LUT_LEN_SHIFT) or (symbol shl HUFFMAN_LUT_SYM_SHIFT)).toUShort()
            }
        }
    }
}