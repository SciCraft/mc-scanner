package de.skyrising.mc.scanner.region

import de.skyrising.mc.scanner.CompoundTag

abstract class RegionVisitor() {
    protected var delegate: RegionVisitor? = null

    constructor(regionVisitor: RegionVisitor) : this() {
        this.delegate = regionVisitor
    }

    open fun visitChunk(x: Int, z: Int): ChunkVisitor? {
        return delegate?.visitChunk(x, z)
    }

    companion object {
        fun visitAllChunks(visit: (x: Int, z: Int, version: Int, data: CompoundTag) -> Unit) = object : RegionVisitor() {
            override fun visitChunk(x: Int, z: Int): ChunkVisitor {
                return object : ChunkVisitor() {
                    override fun visit(version: Int, data: CompoundTag) {
                        visit(x, z, version, data)
                    }
                }
            }
        }
    }
}

abstract class ChunkVisitor() {
    protected var delegate: ChunkVisitor? = null

    constructor(chunkVisitor: ChunkVisitor) : this() {
        this.delegate = chunkVisitor
    }

    open fun visit(version: Int, data: CompoundTag) {
        delegate?.visit(version, data)
    }

    open fun onUnsupportedCompressionType(type: Int) {
        delegate?.onUnsupportedCompressionType(type)
    }

    open fun onInvalidData(ex: Exception) {
        delegate?.onInvalidData(ex)
    }
}