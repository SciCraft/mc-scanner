package scripts

import de.skyrising.mc.scanner.*
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.io.PrintStream
import java.nio.file.Files

needles = listOf(BlockState(Identifier("minecraft", "budding_amethyst"), emptyMap()))

collect {
    this is RegionFile && dimension == "overworld"
}

scan {
    if (this is RegionFile) {
        scanChunks(needles, false)
    } else {
        emptyList()
    }
}

val resultsFile = PrintStream(Files.newOutputStream(outPath.resolve("results.txt")), false, "UTF-8")
val amethystCounts = Object2IntOpenHashMap<ChunkPos>()

onResults {
    forEach { result ->
        if (result.needle is BlockState) {
            amethystCounts.addTo(result.location as ChunkPos, result.count.toInt())
        }
    }
}

fun RandomTickRegion.sumChunks(mapFn: (Vec2i) -> Int): Int {
    var sum = 0
    for (i in 0 until 64) {
        if (chunks and (1UL shl i) == 0UL) continue
        sum += mapFn(SOMETIMES_RANDOM_TICKED[i])
    }
    return sum
}

after {
    val afkChunks = mutableMapOf<ChunkPos, Object2IntMap<ChunkPos>>()
    for (pos in amethystCounts.keys) {
        for (x in pos.x - 8 .. pos.x + 8) {
            for (z in pos.z - 8 .. pos.z + 8) {
                afkChunks.getOrPut(ChunkPos(pos.dimension, x, z)) { Object2IntOpenHashMap() }.put(pos, amethystCounts.getInt(pos))
            }
        }
    }
    var bestPos: Vec3d
    var bestCount = 0
    for ((pos, neighbors) in afkChunks) {
        val maxPossible = neighbors.values.sum()
        if (maxPossible < bestCount) continue
        val base = ALWAYS_RANDOM_TICKED.sumOf { (x, z) -> neighbors.getInt(ChunkPos(pos.dimension, pos.x + x, pos.z + z)) }
        for (region in RANDOM_TICK_REGIONS) {
            val count = base + region.sumChunks { (x, z) -> neighbors.getInt(ChunkPos(pos.dimension, pos.x + x, pos.z + z)) }
            if (count > bestCount) {
                bestPos = Vec3d("overworld", pos.x * 16 + region.pos.x, 0.0, pos.z * 16 + region.pos.y)
                bestCount = count
                resultsFile.println("$bestPos,$bestCount")
            }
        }
    }
    resultsFile.close()
}