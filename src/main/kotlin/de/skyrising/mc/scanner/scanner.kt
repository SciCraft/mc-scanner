package de.skyrising.mc.scanner

import it.unimi.dsi.fastutil.objects.*
import joptsimple.OptionException
import joptsimple.OptionParser
import joptsimple.ValueConverter
import java.io.PrintStream
import java.net.URI
import java.nio.file.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.ToIntFunction

var DECOMPRESSOR = Decompressor.INTERNAL

fun main(args: Array<String>) {
    val parser = OptionParser()
    val helpArg = parser.accepts("help").forHelp()
    val nonOptions = parser.nonOptions()
    val blockArg = parser.acceptsAll(listOf("b", "block"), "Add a block to search for").withRequiredArg()
    val itemArg = parser.acceptsAll(listOf("i", "item"), "Add an item to search for").withRequiredArg()
    val statsArg = parser.accepts("stats", "Calculate statistics for storage tech")
    val geode = parser.accepts("geode", "Calculate AFK spots for geodes")
    val threadsArg = parser.acceptsAll(listOf("t", "threads"), "Set the number of threads to use").withRequiredArg().ofType(Integer::class.java)
    val loopArg = parser.accepts("loop").withOptionalArg().ofType(Integer::class.java)
    val decompressorArg = parser.accepts("decompressor", "Decompressor to use").withOptionalArg().withValuesConvertedBy(object : ValueConverter<Decompressor> {
        override fun convert(value: String) = Decompressor.valueOf(value.uppercase())
        override fun valueType() = Decompressor::class.java
        override fun valuePattern() = "internal|java"
    })
    val needles = mutableListOf<Needle>()
    fun printUsage() {
        System.err.println("Usage: mc-scanner (-i <item> | -b <block>)* [options] <path> [output]")
        parser.printHelpOn(System.err)
    }
    var threads = 0
    var loopCount = 0
    val mode: Mode
    val path: Path
    val outPath: Path
    val zip: FileSystem?
    try {
        val options = parser.parse(*args)
        if (options.has(helpArg)) {
            printUsage()
            return
        }
        for (block in options.valuesOf(blockArg)) {
            val state = BlockState.parse(block)
            needles.add(state)
            needles.addAll(state.unflatten())
        }
        for (item in options.valuesOf(itemArg)) {
            val itemType = ItemType.parse(item)
            needles.add(itemType)
            needles.addAll(itemType.unflatten())
        }
        if (options.has(threadsArg)) threads = options.valueOf(threadsArg).toInt()
        if (options.has(decompressorArg)) DECOMPRESSOR = options.valueOf(decompressorArg)
        mode = when {
            options.has(statsArg) -> Mode.STATS
            options.has(geode) -> Mode.GEODE
            else -> Mode.SEARCH
        }
        if (options.has(loopArg)) {
            loopCount = if (options.hasArgument(loopArg)) {
                options.valueOf(loopArg).toInt()
            } else {
                -1
            }
        }
        val paths = options.valuesOf(nonOptions)
        if (paths.size > 2 || paths.isEmpty()) throw IllegalArgumentException("Expected 1 or 2 paths")
        path = Paths.get(paths[0])
        if (paths.size == 1) {
            outPath = Paths.get("")
            zip = null
        } else {
            val out = Paths.get(paths[1])
            if (Files.exists(out) && Files.isDirectory(out)) {
                outPath = out
                zip = null
            } else if (!out.fileName.toString().endsWith(".zip")) {
                Files.createDirectories(out)
                outPath = out
                zip = null
            } else {
                val uri = out.toUri()
                val fsUri = URI("jar:${uri.scheme}", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
                zip = FileSystems.newFileSystem(fsUri, mapOf<String, Any>("create" to "true"))
                outPath = zip.getPath("/")
            }
        }
    } catch (e: RuntimeException) {
        if (e is OptionException || e is IllegalArgumentException) {
            System.err.println(e.message)
        } else {
            e.printStackTrace()
        }
        println()
        printUsage()
        return
    }
    if (needles.isEmpty() && mode == Mode.SEARCH) {
        println("Nothing to search for.")
        return
    }
    if (mode == Mode.GEODE) {
        needles.clear()
        needles.add(BlockState(Identifier("minecraft", "budding_amethyst"), emptyMap()))
    }
    val executor = when {
        threads <= 0 -> Executors.newWorkStealingPool()
        threads == 1 -> Executors.newSingleThreadExecutor()
        else -> Executors.newWorkStealingPool(threads)
    }
    do {
        runScan(path, outPath, executor, needles, mode)
    } while (loopCount == -1 || loopCount-- > 0)
    zip?.close()
    executor.shutdownNow()
}

fun getHaystack(path: Path): Set<Scannable> {
    val haystack = mutableSetOf<Scannable>()
    if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".mca")) {
        return setOf(RegionFile(path))
    }
    val playerDataPath = path.resolve("playerdata")
    if (Files.exists(playerDataPath)) {
        Files.list(playerDataPath).forEach {
            val fileName = it.fileName.toString()
            if (fileName.endsWith(".dat") && fileName.split('-').size == 5) {
                try {
                    haystack.add(PlayerFile(it))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    for (dim in listOf(".", "DIM-1", "DIM1")) {
        val dimPath = path.resolve(dim)
        if (!Files.exists(dimPath)) continue
        val dimRegionPath = dimPath.resolve("region")
        if (!Files.exists(dimRegionPath)) continue
        Files.list(dimRegionPath).forEach {
            if (it.fileName.toString().endsWith(".mca")) {
                try {
                    haystack.add(RegionFile(it))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    return haystack
}

enum class Mode {
    SEARCH, STATS, GEODE
}

fun runScan(path: Path, outPath: Path, executor: ExecutorService, needles: Collection<Needle>, mode: Mode) {
    println(needles)
    val haystack = getHaystack(path)
    var totalSize = 0L
    for (s in haystack) totalSize += s.size
    val resultsFile = if (mode != Mode.STATS) {
        PrintStream(Files.newOutputStream(outPath.resolve("results.txt")), false, "UTF-8")
    } else {
        PrintStream(Files.newOutputStream(outPath.resolve("inventories.csv")), false, "UTF-8")
    }
    if (mode == Mode.STATS) resultsFile.println("Location,Sub Location,Type,Count")
    val progressSize = AtomicLong()
    var speed = 0.0
    var resultCount = 0
    val lock = Object()
    fun printStatus(i: Int, current: Any? = null) {
        synchronized(lock) {
            print("\u001b[2K$i/${haystack.size} ")
            print("${formatSize(progressSize.toDouble())}/${formatSize(totalSize.toDouble())} ")
            print("${formatSize(speed)}/s $resultCount result${if (resultCount == 1) "" else "s"} ")
            if (current != null) print(current)
            print("\u001B[G")
        }
    }
    val total = Object2LongOpenHashMap<Needle>()
    val index = AtomicInteger()
    printStatus(0)
    val before = System.nanoTime()
    val stats = Object2ObjectOpenHashMap<ItemType, Object2DoubleMap<ItemType>>()
    val inventoryCounts = Object2IntOpenHashMap<ItemType>()
    val locationIds = Object2IntLinkedOpenHashMap<Location>()
    val amethystCounts = Object2IntOpenHashMap<ChunkPos>()
    val futures = haystack.map { CompletableFuture.runAsync({
        val results = try {
            it.scan(needles, mode)
        } catch (e: Exception) {
            print("\u001b[2K")
            System.err.println("Error scanning $it")
            e.printStackTrace()
            println()
            return@runAsync
        }
        val time = System.nanoTime() - before
        val progressAfter = progressSize.addAndGet(it.size)
        speed = progressAfter * 1e9 / time
        resultCount += onResults(
            resultsFile,
            results,
            inventoryCounts,
            stats,
            amethystCounts,
            mode,
            locationIds,
            total
        )
        printStatus(index.incrementAndGet(), it)
    }, executor)}
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    when (mode) {
        Mode.SEARCH -> postProcessSearch(total, resultsFile)
        Mode.STATS -> postProcessStats(outPath, locationIds, stats, total, inventoryCounts)
        Mode.GEODE -> postProcessGeode(amethystCounts, resultsFile)
    }
    resultsFile.close()
    printStatus(haystack.size)
    println()
}

fun postProcessGeode(amethystCounts: Object2IntMap<ChunkPos>, resultsFile: PrintStream) {
    val afkChunks = mutableMapOf<ChunkPos, MutableSet<ChunkPos>>()
    for (pos in amethystCounts.keys) {
        for (x in pos.x - 8 .. pos.x + 8) {
            for (z in pos.z - 8 .. pos.z + 8) {
                afkChunks.getOrPut(ChunkPos(pos.dimension, x, z)) { mutableSetOf() }.add(pos)
            }
        }
    }
    var bestPos: BlockPos
    var bestCount = 0
    for ((pos, neighbors) in afkChunks) {
        val maxPossible = neighbors.sumOf(amethystCounts::getInt)
        if (maxPossible < bestCount) continue
        for (ix in 0 until 16) {
            for (iz in 0 until 16) {
                var count = 0
                val x = pos.x * 16 + ix + 0.5
                val z = pos.z * 16 + iz + 0.5
                for (neighbor in neighbors) {
                    val cx = neighbor.x * 16 + 8
                    val cz = neighbor.z * 16 + 8
                    val dx = x - cx
                    val dz = z - cz
                    if (dx * dx + dz * dz > 128 * 128) continue
                    count += amethystCounts.getInt(neighbor)
                }
                if (count > bestCount) {
                    bestPos = BlockPos("overworld", x.toInt(), 0, z.toInt())
                    bestCount = count
                    resultsFile.println("$bestPos,$bestCount,$ix,$iz")
                }
            }
        }
    }
}

private fun onResults(
    resultsFile: PrintStream,
    results: List<SearchResult>,
    inventoryCounts: Object2IntOpenHashMap<ItemType>,
    stats: Object2ObjectMap<ItemType, Object2DoubleMap<ItemType>>,
    amethystCounts: Object2IntOpenHashMap<ChunkPos>,
    mode: Mode,
    locationIds: Object2IntMap<Location>,
    total: Object2LongOpenHashMap<Needle>
): Int {
    synchronized(resultsFile) {
        for (result in results) {
            when (mode) {
                Mode.STATS -> {
                    when (result.needle) {
                        is StatsResults -> {
                            val types = result.needle.types
                            val stride = types.size
                            val matrix = result.needle.matrix
                            for (i in 0 until stride) {
                                inventoryCounts.addTo(types[i], 1)
                                val map = stats.computeIfAbsent(types[i], Object2ObjectFunction { Object2DoubleOpenHashMap() })
                                for (j in 0 until stride) {
                                    val type = types[j]
                                    val value = matrix[i * stride + j]
                                    map[type] = map.getDouble(type) + value
                                }
                            }
                            continue
                        }
                        is ItemType -> {
                            val loc = if (result.location is SubLocation) result.location else SubLocation(result.location, 0)
                            val locId = locationIds.computeIfAbsent(loc.parent, ToIntFunction { locationIds.size })
                            //val locStr = loc.parent.toString().replace("\"", "\\\"")
                            //resultsFile.print('"')
                            //resultsFile.print(locStr)
                            //resultsFile.print('"')
                            resultsFile.print(locId)
                            resultsFile.print(',')
                            resultsFile.print(loc.index)
                            resultsFile.print(',')
                            resultsFile.print(result.needle.id)
                            resultsFile.print(',')
                            resultsFile.print(result.count)
                            resultsFile.println()
                        }
                    }
                }
                Mode.SEARCH -> {
                    resultsFile.println("${result.location}: ${result.needle} x ${result.count}")
                }
                Mode.GEODE -> {
                    if (result.needle is BlockState) {
                        amethystCounts.addTo(result.location as ChunkPos, result.count.toInt())
                    }
                }
            }
            if (result.location is SubLocation) continue
            total.addTo(result.needle, result.count)
        }
        resultsFile.flush()
        return results.size
    }
}

private fun postProcessSearch(
    total: Object2LongOpenHashMap<Needle>,
    resultsFile: PrintStream
) {
    val totalTypes = total.keys.sortedWith { a, b ->
        if (a.javaClass != b.javaClass) return@sortedWith a.javaClass.hashCode() - b.javaClass.hashCode()
        if (a is ItemType && b is ItemType) return@sortedWith a.compareTo(b)
        if (a is BlockState && b is BlockState) return@sortedWith a.compareTo(b)
        0
    }
    for (type in totalTypes) {
        resultsFile.println("Total $type: ${total.getLong(type)}")
    }
}

private fun postProcessStats(
    outPath: Path,
    locationIds: Object2IntLinkedOpenHashMap<Location>,
    stats: Object2ObjectOpenHashMap<ItemType, Object2DoubleMap<ItemType>>,
    total: Object2LongOpenHashMap<Needle>,
    inventoryCounts: Object2IntOpenHashMap<ItemType>
) {
    val locationsFile = PrintStream(Files.newOutputStream(outPath.resolve("locations.csv")), false, "UTF-8")
    locationsFile.println("Id,Location")
    for ((location, id) in locationIds) {
        locationsFile.print(id)
        locationsFile.print(',')
        locationsFile.print('"')
        locationsFile.print(location.toString().replace("\"", "\\\""))
        locationsFile.println('"')
    }
    locationsFile.close()
    val types = stats.keys.sorted()
    val countsFile = PrintStream(Files.newOutputStream(outPath.resolve("counts.csv")), false, "UTF-8")
    countsFile.println("Type,Total,Number of Inventories")
    for (type in types) {
        countsFile.print(type.format())
        countsFile.print(',')
        countsFile.print(total.getLong(type))
        countsFile.print(',')
        countsFile.println(inventoryCounts.getInt(type))
    }
    countsFile.close()
    val statsFile = PrintStream(Files.newOutputStream(outPath.resolve("stats.csv")), false, "UTF-8")
    for (type in types) {
        statsFile.print(',')
        statsFile.print(type.format())
    }
    statsFile.println()
    for (a in types) {
        statsFile.print(a.id)
        val map = stats[a]!!
        val sum = map.values.sum()
        for (b in types) {
            statsFile.print(',')
            val value = map.getDouble(b)
            statsFile.printf(Locale.ROOT, "%1.5f", value / sum)
        }
        statsFile.println()
    }
    statsFile.close()
}

interface Scannable {
    val size: Long
    fun scan(needles: Collection<Needle>, mode: Mode): List<SearchResult>
}
interface Location

data class SubLocation(val parent: Location, val index: Int): Location
data class ChunkPos(val dimension: String, val x: Int, val z: Int) : Location
data class Container(val type: String, val location: Location) : Location
data class Entity(val type: String, val location: Location) : Location
data class BlockPos(val dimension: String, val x: Int, val y: Int, val z: Int) : Location
data class Vec3d(val dimension: String, val x: Double, val y: Double, val z: Double) : Location
data class PlayerInventory(val player: UUID, val enderChest: Boolean) : Location
data class StatsResults(val types: Array<ItemType>, val matrix: DoubleArray): Needle {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StatsResults

        if (!types.contentEquals(other.types)) return false
        if (!matrix.contentEquals(other.matrix)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = types.contentHashCode()
        result = 31 * result + matrix.contentHashCode()
        return result
    }
}

data class SearchResult(val needle: Needle, val location: Location, val count: Long)

fun addResults(results: MutableList<SearchResult>, location: Location, contents: List<Object2LongMap<ItemType>>, statsMode: Boolean) {
    for (e in contents[0].object2LongEntrySet()) {
        results.add(SearchResult(e.key, location, e.longValue))
    }
    if (statsMode) {
        results.add(SearchResult(tallyStats(contents[0]), location, 1))
        for (i in 1 until contents.size) {
            val subLocation = SubLocation(location, i)
            for (e in contents[i].object2LongEntrySet()) {
                results.add(SearchResult(e.key, subLocation, e.longValue))
            }
        }
    }
}