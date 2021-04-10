package de.skyrising.mc.scanner

import org.openjdk.jmh.annotations.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class McScannerBenchmark {
    @Param("true", "false")
    private var libDeflate = false

    @Benchmark
    fun benchmarkRegion(): Any {
        useLibDeflate = libDeflate
        val path = Paths.get("/home/simon/.local/share/multimc/instances/1.12.2 Liteloader/.minecraft/saves/SciCraft Test/region/r.2.2.mca")
        val region = RegionFile(path)
        return region.scan(listOf(BlockType(19), ItemType("minecraft:sponge")), false)
    }

    @Benchmark
    fun benchmarkPlayer(): Any {
        useLibDeflate = libDeflate
        val path = Paths.get("/home/simon/.local/share/multimc/instances/1.12.2 Liteloader/.minecraft/saves/SciCraft Test/playerdata/0563c907-745d-4e5a-bf5f-95a48097713f.dat")
        val player = PlayerFile(path)
        return player.scan(listOf(BlockType(19), ItemType("minecraft:sponge")), false)
    }
}