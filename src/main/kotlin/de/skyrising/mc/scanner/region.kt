package de.skyrising.mc.scanner

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

data class RegionFile(private val path: Path) : Scannable {
    private val x: Int
    private val z: Int
    private val dimension = when (val dim = path.getName(path.nameCount - 3).toString()) {
        "." -> "overworld"
        "DIM-1" -> "the_nether"
        "DIM1" -> "the_end"
        else -> dim
    }
    override val size = Files.size(path)
    init {
        val fileName = path.fileName.toString()
        val parts = fileName.split('.')
        if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") {
            throw IllegalArgumentException("Not a valid region file name: $fileName")
        }
        x = parts[1].toInt()
        z = parts[2].toInt()
    }

    override fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val blockNeedles = needles.filterIsInstance<BlockType>().mapTo(mutableSetOf(), BlockType::id).toTypedArray().toIntArray()
        val itemNeedles: Set<ItemType> = needles.filterIsInstanceTo(mutableSetOf())
        val channel = Files.newByteChannel(path, StandardOpenOption.READ)
        val fileSize = channel.size()
        val tableBuf = ByteBuffer.allocateDirect(1024 * 2 * 4)
        readFully(channel, tableBuf)
        tableBuf.flip()
        var chunkBuf: ByteBuffer? = null
        var decompressedBuf: ByteArray? = null
        val tableInts = tableBuf.asIntBuffer()
        for (i in 0 until 1024) {
            val location = tableInts[i]
            if (location == 0) continue
            val chunkPos = ChunkPos(dimension, (x shl 5) or (i and 0x1f), (z shl 5) or (i shr 5))
            val offset = location shr 8
            val sectors = location and 0xff
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
                throw IOException("Could not read chunk $chunkPos in $this at offset ${offset * 4096L}, size ${sectors * 4096}, file size $fileSize", e)
            }
            chunkBuf.flip()
            val length = chunkBuf.int - 1
            chunkBuf.limit(length + 5)
            val compression = chunkBuf.get()
            //println("$chunkPos, offset=$offset, sectors=$sectors, length=$length, compression=$compression, buf=$chunkBuf")
            if (decompressedBuf == null) decompressedBuf = ByteArray(length)

            val chunk = when (compression.toInt()) {
                1 -> {
                    val buf = gunzip(chunkBuf, decompressedBuf)
                    decompressedBuf = buf.array()
                    Tag.read(ByteBufferDataInput(buf))
                }
                2 -> {
                    val buf = inflate(chunkBuf, decompressedBuf)
                    decompressedBuf = buf.array()
                    Tag.read(ByteBufferDataInput(buf))
                }
                else -> {
                    System.err.println("Unsupported compression: $compression")
                    continue
                }
            }
            if (chunk !is CompoundTag) {
                System.err.println("Expected chunk to be a CompoundTag")
                continue
            }
            if (!chunk.has("DataVersion", Tag.INTEGER)) {
                //System.err.println("No data version $chunkPos")
                continue
            }
            val dataVersion = chunk.getInt("DataVersion")
            val flattened = dataVersion >= 1451 // 17w47a
            val level = chunk.getCompound("Level")
            if (blockNeedles.isNotEmpty() && !flattened) {
                val sections = level.getList<CompoundTag>("Sections")
                val matches = mutableMapOf<BlockType, Int>()
                for (section in sections) {
                    val y = section.getInt("Y")
                    val blocks = section.getByteArray("Blocks")
                    //val add = if (section.has("Add", Tag.BYTE_ARRAY)) section.getByteArray("Add") else null
                    for (blockNeedle in blockNeedles) {
                        val count = blocks.count { it == blockNeedle.toByte() }
                        if (count != 0) {
                            val type = BlockType(blockNeedle)
                            matches[type] = (matches[type] ?: 0) + count
                        }
                    }
                    if (matches.size == blockNeedles.size) break
                }
                for (match in matches) {
                    results.add(SearchResult(match.key, chunkPos, match.value.toLong()))
                }
            }
            if (itemNeedles.isNotEmpty() || statsMode) {
                if (level.has("TileEntities", Tag.LIST)) {
                    for (blockEntity in level.getList<CompoundTag>("TileEntities")) {
                        if (!blockEntity.has("Items", Tag.LIST)) continue
                        val contents = scanInventory(blockEntity.getList("Items"), itemNeedles, statsMode)
                        val pos = BlockPos(dimension, blockEntity.getInt("x"), blockEntity.getInt("y"), blockEntity.getInt("z"))
                        val container = Container(blockEntity.getString("id"), pos)
                        addResults(results, container, contents, statsMode)
                    }
                }
                if (level.has("Entities", Tag.LIST)) {
                    for (entity in level.getList<CompoundTag>("Entities")) {
                        val id = entity.getString("id")
                        val items = mutableListOf<CompoundTag>()
                        if (entity.has("HandItems", Tag.LIST)) items.addAll(entity.getList("HandItems"))
                        if (entity.has("ArmorItems", Tag.LIST)) items.addAll(entity.getList("ArmorItems"))
                        if (entity.has("Inventory", Tag.LIST)) items.addAll(entity.getList("Inventory"))
                        if (entity.has("Item", Tag.COMPOUND)) items.add(entity.getCompound("Item"))
                        val listTag = ListTag(items.filter(CompoundTag::isNotEmpty))
                        if (listTag.isNotEmpty()) {
                            val posTag = entity.getList<DoubleTag>("Pos")
                            val pos = Vec3d(dimension, posTag[0].value, posTag[1].value, posTag[2].value)
                            val entityLocation = Entity(id, pos)
                            val contents = scanInventory(listTag, itemNeedles, statsMode)
                            addResults(results, entityLocation, contents, statsMode)
                        }
                    }
                }
            }
        }
        channel.close()
        return results
    }

    override fun toString(): String {
        return "RegionFile($dimension, x=$x, z=$z)"
    }
}