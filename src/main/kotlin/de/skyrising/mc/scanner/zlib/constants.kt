package de.skyrising.mc.scanner.zlib

const val ZLIB_METHOD_DEFLATE = 8u
const val GZIP_MAGIC = 0x8b1fu
const val GZIP_FLAG_TEXT = 0x01u
const val GZIP_FLAG_HCRC = 0x02u
const val GZIP_FLAG_EXTRA = 0x04u
const val GZIP_FLAG_NAME = 0x08u
const val GZIP_FLAG_COMMENT = 0x10u
const val GZIP_METHOD_DEFLATE = 8u

const val DEFLATE_BLOCKTYPE_UNCOMPRESSED = 0
const val DEFLATE_BLOCKTYPE_FIXED_HUFFMAN = 1
const val DEFLATE_BLOCKTYPE_DYNAMIC_HUFFMAN = 2

const val DEFLATE_CODE_LENGTHS_COPY: UShort = 16u
const val DEFLATE_CODE_LENGTHS_REPEAT_3: UShort = 17u
const val DEFLATE_CODE_LENGTHS_REPEAT_7: UShort = 18u

const val BITS_MAX = 64 - 8


// RFC 1951 - 3.2.5
private data class PackedLengthSymbol(val symbol: UShort, val baseLength: UShort, val extraBits: UShort)
private val PACKED_LENGTH_SYMBOLS = arrayOf(
    PackedLengthSymbol(257u, 3u, 0u),
    PackedLengthSymbol(258u, 4u, 0u),
    PackedLengthSymbol(259u, 5u, 0u),
    PackedLengthSymbol(260u, 6u, 0u),
    PackedLengthSymbol(261u, 7u, 0u),
    PackedLengthSymbol(262u, 8u, 0u),
    PackedLengthSymbol(263u, 9u, 0u),
    PackedLengthSymbol(264u, 10u, 0u),
    PackedLengthSymbol(265u, 11u, 1u),
    PackedLengthSymbol(266u, 13u, 1u),
    PackedLengthSymbol(267u, 15u, 1u),
    PackedLengthSymbol(268u, 17u, 1u),
    PackedLengthSymbol(269u, 19u, 1u),
    PackedLengthSymbol(270u, 23u, 2u),
    PackedLengthSymbol(271u, 27u, 2u),
    PackedLengthSymbol(272u, 31u, 2u),
    PackedLengthSymbol(273u, 35u, 3u),
    PackedLengthSymbol(274u, 43u, 3u),
    PackedLengthSymbol(275u, 51u, 3u),
    PackedLengthSymbol(276u, 59u, 4u),
    PackedLengthSymbol(277u, 67u, 4u),
    PackedLengthSymbol(278u, 83u, 4u),
    PackedLengthSymbol(279u, 99u, 5u),
    PackedLengthSymbol(280u, 115u, 5u),
    PackedLengthSymbol(281u, 131u, 5u),
    PackedLengthSymbol(282u, 163u, 5u),
    PackedLengthSymbol(283u, 195u, 5u),
    PackedLengthSymbol(284u, 227u, 5u),
    PackedLengthSymbol(285u, 258u, 0u)
)

val LENGTH_TO_SYMBOL = generateLengthToSymbol()
private fun generateLengthToSymbol(): UShortArray {
    val array = UShortArray(259)
    for (i in 0 until 3) array[i] = UShort.MAX_VALUE
    var baseLength = 0
    for (len in 3 until 259) {
        if (len == PACKED_LENGTH_SYMBOLS[baseLength + 1].baseLength.toInt()) {
            baseLength++
        }
        array[len] = PACKED_LENGTH_SYMBOLS[baseLength].symbol
    }
    return array
}

// RFC 1951 - 3.2.5
private data class PackedDistance(val symbol: UShort, val baseDistance: UShort, val extraBits: UShort)
private val PACKED_DISTANCES = arrayOf(
    PackedDistance(0u, 1u, 0u),
    PackedDistance(1u, 2u, 0u),
    PackedDistance(2u, 3u, 0u),
    PackedDistance(3u, 4u, 0u),
    PackedDistance(4u, 5u, 1u),
    PackedDistance(5u, 7u, 1u),
    PackedDistance(6u, 9u, 2u),
    PackedDistance(7u, 13u, 2u),
    PackedDistance(8u, 17u, 3u),
    PackedDistance(9u, 25u, 3u),
    PackedDistance(10u, 33u, 4u),
    PackedDistance(11u, 49u, 4u),
    PackedDistance(12u, 65u, 5u),
    PackedDistance(13u, 97u, 5u),
    PackedDistance(14u, 129u, 6u),
    PackedDistance(15u, 193u, 6u),
    PackedDistance(16u, 257u, 7u),
    PackedDistance(17u, 385u, 7u),
    PackedDistance(18u, 513u, 8u),
    PackedDistance(19u, 769u, 8u),
    PackedDistance(20u, 1025u, 9u),
    PackedDistance(21u, 1537u, 9u),
    PackedDistance(22u, 2049u, 10u),
    PackedDistance(23u, 3073u, 10u),
    PackedDistance(24u, 4097u, 11u),
    PackedDistance(25u, 6145u, 11u),
    PackedDistance(26u, 8193u, 12u),
    PackedDistance(27u, 12289u, 12u),
    PackedDistance(28u, 16385u, 13u),
    PackedDistance(29u, 24577u, 13u),
    PackedDistance(30u, 32769u, 0u)
)

// RFC 1951 - 3.2.6
private data class FixedLiteralBits(val baseValue: UShort, val bits: UShort)
private val FIXED_LITERAL_BITS_TABLE = arrayOf(
    FixedLiteralBits(0u, 8u),
    FixedLiteralBits(144u, 9u),
    FixedLiteralBits(256u, 7u),
    FixedLiteralBits(280u, 8u),
    FixedLiteralBits(288u, 0u)
)

val FIXED_LITERAL_BIT_LENGTHS = generateFixedLiteralBitLengths()
fun generateFixedLiteralBitLengths(): ByteArray {
    val lengths = ByteArray(288)
    for (i in 0 until 4) {
        val current = FIXED_LITERAL_BITS_TABLE[i]
        val next = FIXED_LITERAL_BITS_TABLE[i + 1]
        for (j in current.baseValue until next.baseValue) {
            lengths[j.toInt()] = current.bits.toByte()
        }
    }
    return lengths
}

val FIXED_DISTANCE_BIT_LENGTHS = ByteArray(32) { 5 }

// RFC 1951 - 3.2.7
val CODE_LENGTHS_CODE_LENGTHS_ORDER = byteArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)