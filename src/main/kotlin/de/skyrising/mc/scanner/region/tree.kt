package de.skyrising.mc.scanner.region

import de.skyrising.mc.scanner.CompoundTag

class RegionNode : RegionVisitor() {
    private val chunks = Array<ChunkNode?>(32 * 32) { null }

    fun accept(visitor: RegionVisitor) {
        for (i in chunks.indices) {
            val chunk = chunks[i] ?: continue
            visitor.visitChunk(i and 0x1f, i shr 5)?.let { chunk.accept(it) }
        }
    }

    override fun visitChunk(x: Int, z: Int): ChunkNode {
        val node = ChunkNode()
        chunks[(z shl 5) or x] = node
        return node
    }
}

class ChunkNode : ChunkVisitor() {
    private var version = 0
    private var data: CompoundTag? = null

    fun accept(visitor: ChunkVisitor) {
        val data = this.data
        if (data != null) visitor.visit(version, data)
    }
}