package de.skyrising.mc.scanner.region

import de.skyrising.mc.scanner.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.concurrent.getOrSet

private val decompressedBuf = ThreadLocal<ByteArray>()

class RegionReader(private val channel: SeekableByteChannel): AutoCloseable {
    private var fileSize: Long = 0
    private var chunkBuf: ByteBuffer? = null

    fun accept(visitor: RegionVisitor) {
        channel.position(0)
        fileSize = channel.size()
        if (fileSize == 0L) return
        if (fileSize < 1024 * 2 * 4) throw IOException("File is too small")
        val tableBuf = ByteBuffer.allocateDirect(1024 * 2 * 4)
        readFully(channel, tableBuf)
        tableBuf.flip()
        val tableInts = tableBuf.asIntBuffer()
        for (i in 0 until 1024) {
            val location = tableInts[i]
            if (location == 0) continue
            val chunkX = i and 0x1f
            val chunkZ = i shr 5
            val chunkVisitor = visitor.visitChunk(chunkX, chunkZ) ?: continue
            val offset = location shr 8
            val sectors = location and 0xff
            visitChunk(chunkVisitor, offset, sectors, chunkX, chunkZ)
        }
    }

    override fun close() {
        channel.close()
    }

    private fun readChunk(offset: Int, sectors: Int, chunkX: Int, chunkZ: Int): ByteBuffer {
        var chunkBuf = this.chunkBuf
        if (chunkBuf == null || chunkBuf.capacity() < sectors * 4096) {
            chunkBuf = ByteBuffer.allocateDirect(sectors * 4096)!!
        }
        chunkBuf.position(0)
        val byteOffset = offset * 4096L
        chunkBuf.limit(minOf(sectors * 4096, (fileSize - byteOffset).toInt()))
        channel.position(byteOffset)
        try {
            readFully(channel, chunkBuf)
        } catch (e: IOException) {
            throw IOException("Could not read chunk $chunkX, $chunkZ at offset ${offset * 4096L}, size ${sectors * 4096}, file size $fileSize", e)
        }
        chunkBuf.flip()
        return chunkBuf
    }

    private fun visitChunk(visitor: ChunkVisitor, offset: Int, sectors: Int, chunkX: Int, chunkZ: Int) {
        val chunkBuf = readChunk(offset, sectors, chunkX, chunkZ)
        val length = chunkBuf.int - 1
        chunkBuf.limit(length + 5)
        val compression = chunkBuf.get()
        //println("$chunkPos, offset=$offset, sectors=$sectors, length=$length, compression=$compression, buf=$chunkBuf")
        val dbuf = decompressedBuf.getOrSet {
            ByteArray(length)
        }

        val chunk = when (compression.toInt()) {
            1 -> {
                try {
                    val buf = DECOMPRESSOR.decodeGzip(chunkBuf, dbuf)
                    decompressedBuf.set(buf.array())
                    Tag.read(ByteBufferDataInput(buf))
                } catch (e: Exception) {
                    visitor.onInvalidData(e)
                    return
                }
            }
            2 -> {
                try {
                    val buf = DECOMPRESSOR.decodeZlib(chunkBuf, dbuf)
                    val copy = buf.slice()
                    decompressedBuf.set(buf.array())
                    try {
                        Tag.read(ByteBufferDataInput(buf))
                    } catch (e: Exception) {
                        println(hexdump(copy))
                        throw e
                    }
                } catch (e: Exception) {
                    visitor.onInvalidData(e)
                    return
                }
            }
            else -> {
                visitor.onUnsupportedCompressionType(compression.toInt())
                return
            }
        }
        if (chunk !is CompoundTag) {
            visitor.onInvalidData(IllegalArgumentException("Expected chunk to be a CompoundTag"))
            return
        }
        if (!chunk.has("DataVersion", Tag.INTEGER)) {
            visitor.onInvalidData(IllegalArgumentException("No data version"))
            return
        }
        val version = chunk.getInt("DataVersion");
        if (!chunk.has("Level", Tag.COMPOUND) && version < DataVersion.REMOVE_LEVEL_TAG) {
            visitor.onInvalidData(IllegalArgumentException("No level tag"))
            return
        }
        visitor.visit(version, if (version >= DataVersion.REMOVE_LEVEL_TAG) chunk else chunk.getCompound("Level"))
    }
}