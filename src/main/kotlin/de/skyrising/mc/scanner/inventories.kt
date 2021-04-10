package de.skyrising.mc.scanner

import it.unimi.dsi.fastutil.objects.Object2LongMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

data class PlayerFile(private val path: Path) : Scannable {
    override val size: Long = Files.size(path)
    private val uuid: UUID
    init {
        val nameParts = path.fileName.toString().split('.')
        if (nameParts.size != 2 || nameParts[1] != "dat") throw IllegalArgumentException("Invalid player file: ${path.fileName}")
        uuid = UUID.fromString(nameParts[0])
    }

    override fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult> {
        val itemNeedles = needles.filterIsInstance<ItemType>()
        if (itemNeedles.isEmpty()) return emptyList()
        val raw = Files.readAllBytes(path)
        val data = Tag.read(ByteBufferDataInput(gunzip(ByteBuffer.wrap(raw))))
        if (data !is CompoundTag) return emptyList()
        val results = mutableListOf<SearchResult>()
        if (data.has("Inventory", Tag.LIST)) {
            val invScan = scanInventory(data.getList("Inventory"), itemNeedles, statsMode)
            addResults(results, PlayerInventory(uuid, false), invScan, statsMode)
        }
        if (data.has("EnderItems", Tag.LIST)) {
            val enderScan = scanInventory(data.getList("EnderItems"), itemNeedles, statsMode)
            addResults(results, PlayerInventory(uuid, true), enderScan, statsMode)
        }
        return results
    }
}

fun scanInventory(slots: ListTag<CompoundTag>, needles: Collection<ItemType>, statsMode: Boolean): List<Object2LongMap<ItemType>> {
    val ids = needles.mapTo(mutableSetOf(), ItemType::id)
    val result = Object2LongOpenHashMap<ItemType>()
    val inventories = mutableListOf<Object2LongMap<ItemType>>(result)
    for (slot in slots) {
        if (!slot.has("id", Tag.STRING)) continue
        val id = slot.getString("id")
        val contained = getSubResults(slot, needles, statsMode)
        if (id in ids || (ids.isEmpty() && statsMode && contained.isEmpty())) {
            val dmg = if (slot.has("Damage", Tag.INTEGER)) slot.getInt("Damage") else null
            if (dmg != null && dmg != 0 && dmg < 16) {
                result.addTo(ItemType("$id:$dmg"), slot.getInt("Count").toLong())
            } else {
                result.addTo(ItemType(id), slot.getInt("Count").toLong())
            }
        }
        if (contained.isEmpty()) continue
        if (statsMode) inventories.addAll(contained)
        for (e in contained[0].object2LongEntrySet()) {
            result.addTo(e.key, e.longValue)
        }
    }
    return inventories
}

fun getSubResults(slot: CompoundTag, needles: Collection<ItemType>, statsMode: Boolean): List<Object2LongMap<ItemType>> {
    if (!slot.has("tag", Tag.COMPOUND)) return emptyList()
    val tag = slot.getCompound("tag")
    if (!tag.has("BlockEntityTag", Tag.COMPOUND)) return emptyList()
    val blockEntityTag = tag.getCompound("BlockEntityTag")
    if (!blockEntityTag.has("Items", Tag.LIST)) return emptyList()
    return scanInventory(blockEntityTag.getList("Items"), needles, statsMode)
}

fun tallyStats(scan: Object2LongMap<ItemType>): StatsResults {
    val types = scan.keys.toTypedArray()
    val stride = types.size
    val results = DoubleArray(stride * stride)
    val counts = scan.values.toLongArray()
    var total = counts.sum()
    for (i in types.indices) {
        // val count = counts[i]
        for (j in types.indices) {
            val otherCount = counts[j]
            val avg = otherCount.toDouble() / total
            results[i * stride + j] = avg
        }
    }
    return StatsResults(types, results)
}