package scripts

import de.skyrising.mc.scanner.Needle
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import java.io.PrintStream
import java.nio.file.Files
import kotlin.system.exitProcess

val resultsFile = PrintStream(Files.newOutputStream(outPath.resolve("results.txt")), false, "UTF-8")
val total = Object2LongOpenHashMap<Needle>()

if (needles.isEmpty()) {
    println("Nothing to search for.")
    exitProcess(0)
}

println(needles)

onResults {
    for (result in this) {
        resultsFile.println("${result.location}: ${result.needle} x ${result.count}")
        if (result.location is SubLocation) continue
        total.addTo(result.needle, result.count)
    }
    resultsFile.flush()
}

after {
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