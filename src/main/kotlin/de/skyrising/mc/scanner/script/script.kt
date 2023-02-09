package de.skyrising.mc.scanner.script

import de.skyrising.mc.scanner.*
import de.skyrising.mc.scanner.region.ChunkNode
import java.nio.file.Path
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    fileExtension = "scan.kts",
    compilationConfiguration = ScannerScriptConfiguration::class
)
abstract class ScannerScript(val scan: Scan) : ScanBase by scan {
    fun collect(predicate: Scannable.() -> Boolean) {
        scan.haystackPredicate = predicate
    }

    fun scan(scanner: Scannable.() -> List<SearchResult>) {
        scan.scanner = scanner
    }

    fun onResults(block: List<SearchResult>.() -> Unit) {
        scan.onResults = block
    }

    fun after(block: () -> Unit) {
        scan.postProcess = block
    }
}

object ScannerScriptConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        Scannable::class,
        Location::class,
        SubLocation::class,
        ChunkPos::class,
        Container::class,
        Entity::class,
        BlockPos::class,
        Vec3d::class,
        PlayerInventory::class,
        StatsResults::class,
        SearchResult::class,
        Identifier::class,
        BlockState::class,
        BlockIdMask::class,
        ItemType::class,
        RegionFile::class,
        ChunkNode::class,
    )
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

interface ScanBase {
    val outPath: Path
    var needles: List<Needle>
}

class Scan(override val outPath: Path, override var needles: List<Needle>) : ScanBase {
    var haystackPredicate: (Scannable) -> Boolean = { true }
    var scanner: (Scannable) -> List<SearchResult> = { it.scan(needles, false) }
    var onResults: (List<SearchResult>) -> Unit = {}
    var postProcess: () -> Unit = {}
}