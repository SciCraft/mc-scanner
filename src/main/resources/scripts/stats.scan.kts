package scripts

import de.skyrising.mc.scanner.Needle
import it.unimi.dsi.fastutil.objects.*
import java.io.PrintStream
import java.nio.file.Files
import java.util.*
import java.util.function.ToIntFunction

val inventoriesFile = PrintStream(Files.newOutputStream(outPath.resolve("inventories.csv")), false, "UTF-8")
val total = Object2LongOpenHashMap<Needle>()
val stats = Object2ObjectOpenHashMap<ItemType, Object2DoubleMap<ItemType>>()
val inventoryCounts = Object2IntOpenHashMap<ItemType>()
val locationIds = Object2IntLinkedOpenHashMap<Location>()

scan {
    scan(needles, true)
}

onResults {
    for (result in this) {
        val location = result.location
        val needle = result.needle
        when (needle) {
            is StatsResults -> {
                val types = needle.types
                val stride = types.size
                val matrix = needle.matrix
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
                val loc = if (location is SubLocation) location else SubLocation(location, 0)
                val locId = locationIds.computeIfAbsent(loc.parent, ToIntFunction { locationIds.size })
                //val locStr = loc.parent.toString().replace("\"", "\\\"")
                //resultsFile.print('"')
                //resultsFile.print(locStr)
                //resultsFile.print('"')
                inventoriesFile.print(locId)
                inventoriesFile.print(',')
                inventoriesFile.print(loc.index)
                inventoriesFile.print(',')
                inventoriesFile.print(needle.id)
                inventoriesFile.print(',')
                inventoriesFile.print(result.count)
                inventoriesFile.println()
            }
        }
        if (location is SubLocation) continue
        total.addTo(needle, result.count)
    }
    inventoriesFile.flush()
}

after {
    PrintStream(Files.newOutputStream(outPath.resolve("locations.csv")), false, "UTF-8").use { locationsFile ->
        locationsFile.println("Id,Location")
        for ((location, id) in locationIds) {
            locationsFile.print(id)
            locationsFile.print(',')
            locationsFile.print('"')
            locationsFile.print(location.toString().replace("\"", "\\\""))
            locationsFile.println('"')
        }
    }
    val types = stats.keys.sorted()
    PrintStream(Files.newOutputStream(outPath.resolve("counts.csv")), false, "UTF-8").use { countsFile ->
        countsFile.println("Type,Total,Number of Inventories")
        for (type in types) {
            countsFile.print(type.format())
            countsFile.print(',')
            countsFile.print(total.getLong(type))
            countsFile.print(',')
            countsFile.println(inventoryCounts.getInt(type))
        }
    }
    PrintStream(Files.newOutputStream(outPath.resolve("stats.csv")), false, "UTF-8").use { statsFile ->
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
    }
}