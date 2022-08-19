package de.skyrising.mc.scanner

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

interface Needle

private val BLOCK_STATE_MAP = readBlockStateMap()
private val ITEM_MAP = readItemMap()

data class Identifier(val namespace: String, val path: String) : Comparable<Identifier> {
    override fun compareTo(other: Identifier): Int {
        val namespaceCompare = namespace.compareTo(other.namespace)
        if (namespaceCompare != 0) return namespaceCompare
        return path.compareTo(other.path)
    }

    override fun toString() = "$namespace:$path"

    companion object {
        fun of(id: String): Identifier {
            val colon = id.indexOf(':')
            if (colon < 0) return ofMinecraft(id)
            val namespace = id.substring(0, colon)
            val path = id.substring(colon + 1)
            if (namespace == "minecraft") return ofMinecraft(path)
            return Identifier(namespace, path)
        }
        fun ofMinecraft(path: String) = Identifier("minecraft", path)
    }
}

data class BlockState(val id: Identifier, val properties: Map<String, String> = emptyMap()) : Needle, Comparable<BlockState> {
    fun unflatten(): List<BlockIdMask> {
        val list = mutableListOf<BlockIdMask>()
        var id: Int? = null
        var mask = 0
        for (i in BLOCK_STATE_MAP.indices) {
            val mapped = BLOCK_STATE_MAP[i] ?: continue
            if (!mapped.matches(this)) continue
            val currentId = i shr 4
            if (id != null && currentId != id) {
                list.add(BlockIdMask(id, mask, this))
                mask = 0
            }
            id = currentId
            mask = mask or (1 shl (i and 0xf))
        }
        if (id != null) list.add(BlockIdMask(id, mask, this))
        return list
    }

    fun matches(predicate: BlockState): Boolean {
        if (id != predicate.id) return false
        for (e in predicate.properties.entries) {
            if (!properties.containsKey(e.key) || properties[e.key] != e.value) return false
        }
        return true
    }

    override fun compareTo(other: BlockState): Int {
        val idComp = id.compareTo(other.id)
        if (idComp != 0) return idComp
        if (properties == other.properties) return 0
        return properties.hashCode().compareTo(other.properties.hashCode()) or 1
    }

    fun format(): String {
        if (properties.isEmpty()) return id.toString()
        val sb = StringBuilder(id.toString()).append('[')
        var first = true
        for (e in properties.entries) {
            if (!first) sb.append(',')
            first = false
            sb.append(e.key).append('=').append(e.value)
        }
        return sb.append(']').toString()
    }

    override fun toString() = "BlockState(${format()})"

    companion object {
        fun parse(desc: String): BlockState {
            if (!desc.contains('[')) return BlockState(Identifier.of(desc))
            val bracketIndex = desc.indexOf('[')
            val closingBracketIndex = desc.indexOf(']', bracketIndex + 1)
            if (closingBracketIndex != desc.lastIndex) throw IllegalArgumentException("Expected closing ]")
            val id = Identifier.of(desc.substring(0, bracketIndex))
            val properties = LinkedHashMap<String, String>()
            for (kvPair in desc.substring(bracketIndex + 1, closingBracketIndex).split(',')) {
                val equalsIndex = kvPair.indexOf('=')
                if (equalsIndex < 0) throw IllegalArgumentException("Invalid key-value pair")
                properties[kvPair.substring(0, equalsIndex)] = kvPair.substring(equalsIndex + 1)
            }
            return BlockState(id, properties)
        }

        fun from(nbt: CompoundTag): BlockState {
            val id = Identifier.of(nbt.getString("Name"))
            if (!nbt.has("Properties", Tag.COMPOUND)) return BlockState(id)
            val properties = LinkedHashMap<String, String>()
            for (e in nbt.getCompound("Properties").entries) {
                properties[e.key] = (e.value as StringTag).value
            }
            return BlockState(id, properties)
        }
    }
}

data class BlockIdMask(val id: Int, val metaMask: Int, val blockState: BlockState? = null) : Needle {
    fun matches(id: Int, meta: Int) = this.id == id && (1 shl meta) and metaMask != 0

    infix fun or(other: BlockIdMask): BlockIdMask {
        if (other.id != id) throw IllegalArgumentException("Cannot combine different ids")
        return BlockIdMask(id, metaMask or other.metaMask)
    }

    override fun toString(): String {
        if (blockState == null) return "BlockIdMask(%d:0x%04x)".format(id, metaMask)
        return "BlockIdMask(%d:0x%04x %s)".format(id, metaMask, blockState.format())
    }
}

data class ItemType(val id: Identifier, val damage: Int = -1, val flattened: Boolean = damage < 0) : Needle, Comparable<ItemType> {
    fun flatten(): ItemType {
        if (this.flattened) return this
        var flattened = ITEM_MAP[this]
        if (flattened == null) flattened = ITEM_MAP[ItemType(id, 0)]
        if (flattened == null) return ItemType(id, damage, true)
        return ItemType(flattened, -1, true)
    }

    fun unflatten(): List<ItemType> {
        if (!flattened) return emptyList()
        val list = mutableListOf<ItemType>()
        for (e in ITEM_MAP.entries) {
            if (e.value == this.id && e.key.id != this.id) {
                list.add(e.key)
            }
        }
        return list
    }

    override fun toString(): String {
        return "ItemType(${format()})"
    }

    fun format() = if (damage < 0 || (flattened && damage == 0)) "$id" else "$id.$damage"

    override fun compareTo(other: ItemType): Int {
        val idComp = id.compareTo(other.id)
        if (idComp != 0) return idComp
        return damage.compareTo(other.damage)
    }

    companion object {
        fun parse(str: String): ItemType {
            if (!str.contains('.')) return ItemType(Identifier.of(str))
            return ItemType(Identifier.of(str.substringBefore('.')), str.substringAfter('.').toInt())
        }
    }
}

private fun getFlatteningMap(name: String): JsonObject = Json.decodeFromString(BlockIdMask::class.java.getResourceAsStream("/flattening/$name.json")!!.reader().readText())

private fun readBlockStateMap(): Array<BlockState?> {
    val jsonMap = getFlatteningMap("block_states")
    val map = Array<BlockState?>(256 * 16) { null }
    for (e in jsonMap.entries) {
        val id = if (e.key.contains(':')) {
            e.key.substringBefore(':').toInt() shl 4 or e.key.substringAfter(':').toInt()
        } else {
            e.key.toInt() shl 4
        }
        map[id] = BlockState.parse(e.value.jsonPrimitive.content)
    }
    for (i in map.indices) {
        if (map[i] != null) continue
        map[i] = map[i and 0xff0]
    }
    return map
}

private fun readItemMap(): Map<ItemType, Identifier> {
    val jsonMap = getFlatteningMap("items")
    val map = LinkedHashMap<ItemType, Identifier>()
    for (e in jsonMap.entries) {
        map[ItemType.parse(e.key)] = Identifier.of(e.value.jsonPrimitive.content)
    }
    return map
}
