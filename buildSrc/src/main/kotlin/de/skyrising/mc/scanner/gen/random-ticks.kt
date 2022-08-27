package de.skyrising.mc.scanner.gen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.lang.Long.bitCount
import java.lang.Long.toUnsignedString

val STEP = 1 / 32.0

data class ChunkOffset(val x: Int, val z: Int)
data class Vec2d(val x: Double, val z: Double)

fun isRandomTicked(player: Vec2d, chunk: ChunkOffset): Boolean {
    val cx = chunk.x * 16 + 8
    val cz = chunk.z * 16 + 8
    val dx = player.x - cx
    val dz = player.z - cz
    return dx * dx + dz * dz < 128 * 128
}

fun mantissaBits(d: Double) = bitCount(d.toRawBits() and ((1L shl 52) - 1))

fun generateRandomTicksKt(): FileSpec {
    val permaloaded = linkedSetOf<ChunkOffset>()
    val semiloaded = linkedSetOf<ChunkOffset>()
    val regions = mutableMapOf<Long, Vec2d>()
    val corners = arrayOf(
        Vec2d(0.0, 0.0),
        Vec2d(0.0, 16.0),
        Vec2d(16.0, 0.0),
        Vec2d(16.0, 16.0)
    )
    for (z in -8..8) {
        for (x in -8..8) {
            val chunk = ChunkOffset(x, z)
            val ticked = corners.map { isRandomTicked(it, chunk) }
            if (ticked.all { it }) {
                permaloaded.add(chunk)
            } else if (ticked.any { it }) {
                semiloaded.add(chunk)
            }
        }
    }
    require(semiloaded.size <= 64) { "Semi-loaded bits can't fit into Long" }
    var z = 0.0
    while (z <= 16.0) {
        var x = 0.0
        while (x <= 16.0) {
            val pos = Vec2d(x, z)
            val chunks = semiloaded
                .mapIndexed() { i, chunk -> (if (isRandomTicked(pos, chunk)) 1L else 0L) shl i }
                .reduce(Long::or)
            regions.compute(chunks) { _, old ->
                if (old == null) {
                    pos
                } else {
                    val oldBits = Math.pow(mantissaBits(old.x).toDouble(), 2.0) + Math.pow(mantissaBits(old.z).toDouble(), 2.0)
                    val newBits = Math.pow(mantissaBits(pos.x).toDouble(), 2.0) + Math.pow(mantissaBits(pos.z).toDouble(), 2.0)
                    if (newBits < oldBits) {
                        pos
                    } else {
                        old
                    }
                }
            }
            x += STEP
        }
        z += STEP
    }

    println(regions.size)
    val typeVec2i = ClassName("de.skyrising.mc.scanner", "Vec2i")
    val typeVec2d = ClassName("de.skyrising.mc.scanner", "Vec2d")
    val typeRegion = ClassName("de.skyrising.mc.scanner", "RandomTickRegion")
    val typeSpecRegion = TypeSpec.classBuilder("RandomTickRegion").addModifiers(KModifier.DATA)
        .primaryConstructor(FunSpec.constructorBuilder()
            .addParameter("pos", typeVec2d)
            .addParameter("chunks", U_LONG).build())
        .addProperty(PropertySpec.builder("pos", typeVec2d).initializer("pos").build())
        .addProperty(PropertySpec.builder("chunks", U_LONG).initializer("chunks").build())
        .build()

    val permaVal = PropertySpec.builder("ALWAYS_RANDOM_TICKED", ARRAY.parameterizedBy(typeVec2i))
        .initializer("arrayOf(%L)", permaloaded.map {
            CodeBlock.of("%T(%L,·%L)", typeVec2i, it.x, it.z)
        }.joinToCode(", "))
        .build()

    val semiVal = PropertySpec.builder("SOMETIMES_RANDOM_TICKED", ARRAY.parameterizedBy(typeVec2i))
        .initializer("arrayOf(%L)", semiloaded.map {
            CodeBlock.of("%T(%L,·%L)", typeVec2i, it.x, it.z)
        }.joinToCode(", "))
        .build()

    val regionsVal = PropertySpec.builder("RANDOM_TICK_REGIONS", ARRAY.parameterizedBy(typeRegion))
        .initializer("arrayOf(\n%L\n)", regions.map {
            CodeBlock.of("%T(%T(%L, %L), %L)", typeRegion, typeVec2d, it.value.x, it.value.z, "0x" + toUnsignedString(it.key, 16) + "UL")
        }.joinToCode(",\n"))
        .build()

    return FileSpec.builder("de.skyrising.mc.scanner", "random-ticks")
        .addType(typeSpecRegion)
        .addProperty(permaVal)
        .addProperty(semiVal)
        .addProperty(regionsVal)
        .build()
}
