package de.skyrising.mc.scanner

import de.skyrising.mc.scanner.script.Scan
import de.skyrising.mc.scanner.script.ScannerScript
import it.unimi.dsi.fastutil.objects.*
import joptsimple.OptionException
import joptsimple.OptionParser
import joptsimple.ValueConverter
import java.io.PrintStream
import java.net.URI
import java.nio.file.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.ToIntFunction
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

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
    val path: Path
    val outPath: Path
    val zip: FileSystem?
    val script: Path
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
        if (options.has(loopArg)) {
            loopCount = if (options.hasArgument(loopArg)) {
                options.valueOf(loopArg).toInt()
            } else {
                -1
            }
        }
        val paths = options.valuesOf(nonOptions).toMutableList()
        script = when {
            options.has(statsArg) -> builtinScript("stats")
            options.has(geode) -> builtinScript("geode")
            paths.isNotEmpty() && paths[0].endsWith(".scan.kts") -> Paths.get(paths.removeAt(0))
            else -> builtinScript("search")
        }
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
    val executor = when {
        threads <= 0 -> Executors.newWorkStealingPool()
        threads == 1 -> Executors.newSingleThreadExecutor()
        else -> Executors.newWorkStealingPool(threads)
    }
    do {
        runScript(path, outPath, executor, needles, script)
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

fun builtinScript(name: String): Path {
    val url = ScannerScript::class.java.getResource("/scripts/$name.scan.kts")
    return Paths.get(url?.toURI() ?: throw IllegalArgumentException("Script not found: $name"))
}

fun evalScript(path: Path, scan: Scan): ResultWithDiagnostics<EvaluationResult> {
    val source = Files.readAllBytes(path).toString(Charsets.UTF_8).toScriptSource(path.fileName.toString())
    return BasicJvmScriptingHost().evalWithTemplate<ScannerScript>(source, evaluation = {
        constructorArgs(scan)
    })
}

fun runScript(path: Path, outPath: Path, executor: ExecutorService, needles: List<Needle>, script: Path) {
    val scan = Scan(outPath, needles)
    evalScript(script, scan).valueOrThrow()
    val haystack = getHaystack(path).filterTo(mutableSetOf(), scan.haystackPredicate)
    var totalSize = 0L
    for (s in haystack) totalSize += s.size
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
    val index = AtomicInteger()
    printStatus(0)
    val before = System.nanoTime()
    val futures = haystack.map { CompletableFuture.runAsync({
        val results = try {
            scan.scanner(it)
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
        synchronized(scan) {
            scan.onResults(results)
            resultCount += results.size
        }
        printStatus(index.incrementAndGet(), it)
    }, executor)}
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    scan.postProcess()
    printStatus(haystack.size)
    println()

}

interface Scannable {
    val size: Long
    fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult>
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