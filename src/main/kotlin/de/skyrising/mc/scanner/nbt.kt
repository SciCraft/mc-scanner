package de.skyrising.mc.scanner

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import java.io.DataInput
import java.io.DataOutput
import java.io.Writer
import java.util.*
import kotlin.reflect.KClass

interface TagType<T: Tag> {
    fun read(din: DataInput): T
}

sealed class Tag {
    abstract fun write(out: DataOutput)
    abstract fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String)

    fun writeSnbt(writer: Writer) {
        val sb = StringBuilder()
        toString(sb, 0, LinkedList(), "    ")
        writer.write(sb.toString())
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12
        const val INTEGER = 99

        val NO_INDENT = setOf(
            listOf("{}", "size", "[]"),
            listOf("{}", "data", "[]", "{}"),
            listOf("{}", "palette", "[]", "{}"),
            listOf("{}", "entities", "[]", "{}")
        )

        private val idToTag = Array<Class<*>>(13) { EndTag::class.java }
        private val idToType = Array<TagType<*>>(13) { EndTag }
        private val tagToId = Reference2IntOpenHashMap<Class<*>>(13)

        private fun <T: Tag> register(cls: KClass<T>, id: Int, type: TagType<T>) {
            idToTag[id] = cls.java
            idToType[id] = type
            tagToId[cls.java] = id
        }

        fun getId(tag: Tag) = tagToId.getInt(tag::class.java)
        fun <T: Tag> getId(type: KClass<T>) = tagToId.getInt(type.java)

        fun getReader(id: Int) = try {
            idToType[id]
        } catch (_: ArrayIndexOutOfBoundsException) {
            throw IllegalArgumentException("Unknown tag type $id")
        }

        inline fun read(id: Int, din: DataInput) = getReader(id).read(din)

        fun read(input: DataInput): Tag {
            val id = input.readByte().toInt()
            if (id == END) return EndTag
            input.readUTF()
            return read(id, input)
        }

        init {
            register(EndTag::class, END, EndTag)
            register(ByteTag::class, BYTE, ByteTag.Companion)
            register(ShortTag::class, SHORT, ShortTag.Companion)
            register(IntTag::class, INT, IntTag.Companion)
            register(LongTag::class, LONG, LongTag.Companion)
            register(FloatTag::class, FLOAT, FloatTag.Companion)
            register(DoubleTag::class, DOUBLE, DoubleTag.Companion)
            register(ByteArrayTag::class, BYTE_ARRAY, ByteArrayTag.Companion)
            register(StringTag::class, STRING, StringTag.Companion)
            register(ListTag::class, LIST, ListTag.Companion)
            register(CompoundTag::class, COMPOUND, CompoundTag.Companion)
            register(IntArrayTag::class, INT_ARRAY, IntArrayTag.Companion)
            register(LongArrayTag::class, LONG_ARRAY, LongArrayTag.Companion)
        }
    }
}

object EndTag : Tag(), TagType<EndTag> {
    override fun read(din: DataInput) = EndTag
    override fun write(out: DataOutput) {}
    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {}
}

data class ByteTag(val value: Byte) : Tag() {
    override fun write(out: DataOutput) = out.writeByte(value.toInt())
    companion object : TagType<ByteTag> {
        override fun read(din: DataInput) = ByteTag(din.readByte())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value.toInt()).append('b')
    }
}

data class ShortTag(val value: Short) : Tag() {
    override fun write(out: DataOutput) = out.writeShort(value.toInt())
    companion object : TagType<ShortTag> {
        override fun read(din: DataInput) = ShortTag(din.readShort())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value.toInt()).append('s')
    }
}

data class IntTag(val value: Int) : Tag() {
    override fun write(out: DataOutput) = out.writeInt(value)
    companion object : TagType<IntTag> {
        override fun read(din: DataInput) = IntTag(din.readInt())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value)
    }
}

data class LongTag(val value: Long) : Tag() {
    override fun write(out: DataOutput) = out.writeLong(value)
    companion object : TagType<LongTag> {
        override fun read(din: DataInput) = LongTag(din.readLong())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('L')
    }
}

data class FloatTag(val value: Float) : Tag() {
    override fun write(out: DataOutput) = out.writeFloat(value)
    companion object : TagType<FloatTag> {
        override fun read(din: DataInput) = FloatTag(din.readFloat())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('f')
    }
}

data class DoubleTag(val value: Double) : Tag() {
    override fun write(out: DataOutput) = out.writeDouble(value)
    companion object : TagType<DoubleTag> {
        override fun read(din: DataInput) = DoubleTag(din.readDouble())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('d')
    }
}

data class ByteArrayTag(val value: ByteArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        out.write(value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ByteArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[B;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i]).append('B')
        }
        sb.append(']')
    }

    companion object : TagType<ByteArrayTag> {
        override fun read(din: DataInput): ByteArrayTag {
            val value = ByteArray(din.readInt())
            din.readFully(value)
            return ByteArrayTag(value)
        }
    }
}

data class StringTag(val value: String) : Tag() {
    override fun write(out: DataOutput) = out.writeUTF(value)

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        escape(sb, value)
    }

    companion object : TagType<StringTag> {
        val SIMPLE = Regex("[A-Za-z0-9._+-]+")

        override fun read(din: DataInput) = StringTag(din.readUTF())

        fun escape(sb: StringBuilder, s: String) {
            val start = sb.length
            sb.append(' ')
            var quoteChar = 0.toChar()
            for (c in s) {
                if (c == '\\') sb.append('\\')
                else if (c == '"' || c == '\'') {
                    if (quoteChar == 0.toChar()) {
                        quoteChar = if (c == '"') '\'' else '"'
                    }
                    if (quoteChar == c) sb.append('\\')
                }
                sb.append(c)
            }
            if (quoteChar == 0.toChar()) quoteChar = '"'
            sb[start] = quoteChar
            sb.append(quoteChar)
        }
    }
}

data class ListTag<T : Tag>(val value: MutableList<T>) : Tag(), MutableList<T> by value {
    constructor(value: Collection<T>) : this(value.toMutableList())

    fun verify(): Int {
        var id = 0
        for (elem in value) {
            val elemId = getId(elem)
            if (id == 0) id = elemId
            else if (elemId != id) throw IllegalStateException("Conflicting types in ListTag")
        }
        return id
    }

    override fun write(out: DataOutput) {
        out.writeByte(verify())
        out.writeInt(value.size)
        for (tag in value) tag.write(out)
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        if (value.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        path.addLast("[]")
        val indent = indentString.isNotEmpty() && !NO_INDENT.contains(path)
        var first = true
        for (e in value) {
            if (!first) sb.append(',')
            if (indent) {
                sb.append('\n')
                for (i in 0 .. depth) sb.append(indentString)
            } else if (!first) {
                sb.append(' ')
            }
            first = false
            e.toString(sb, depth + 1, path, if (indent) indentString else "")
        }
        path.removeLast()
        if (indent) {
            sb.append('\n')
            for (i in 0 until depth) sb.append("    ")
        }
        sb.append(']')
    }

    override fun toString() = "ListTag$value"

    companion object : TagType<ListTag<*>> {
        override fun read(din: DataInput): ListTag<*> {
            val id = din.readByte().toInt()
            val size = din.readInt()
            val value = ArrayList<Tag>(size)
            val reader = getReader(id)
            for (i in 0 until size) {
                value.add(reader.read(din))
            }
            return ListTag(value)
        }
    }
}

data class CompoundTag(val value: MutableMap<String, Tag>) : Tag(), MutableMap<String, Tag> by value {
    override fun write(out: DataOutput) {
        for ((k, v) in value) {
            if (v is EndTag) throw IllegalStateException("EndTag in CompoundTag")
            out.writeByte(getId(v))
            out.writeUTF(k)
            v.write(out)
        }
        out.write(END)
    }

    fun has(key: String, type: Int) = get(key, type) != null

    private fun get(key: String, type: Int): Tag? {
        val value = this[key] ?: return null
        if (type == INTEGER) {
            if (value !is ByteTag && value !is ShortTag && value !is IntTag) return null
        } else if (getId(value) != type) {
            return null
        }
        return value
    }

    private inline fun <reified T : Tag, U> getTyped(key: String, value: (T) -> U): U {
        val tag = this[key]
        if (tag !is T) throw IllegalArgumentException("No ${T::class.java.name} for $key")
        return value(tag)
    }

    fun getCompound(key: String) = getTyped<CompoundTag, CompoundTag>(key) { it }
    fun getByteArray(key: String) = getTyped(key, ByteArrayTag::value)
    fun getString(key: String) = getTyped(key, StringTag::value)
    fun getLongArray(key: String) = getTyped(key, LongArrayTag::value)

    fun getInt(key: String): Int {
        val tag = get(key, INTEGER) ?: throw IllegalArgumentException("No int value for $key")
        return when (tag) {
            is IntTag -> tag.value
            is ShortTag -> tag.value.toInt()
            is ByteTag -> tag.value.toInt()
            else -> error("Unexpected type $tag")
        }
    }

    inline fun <reified T : Tag> getList(key: String): ListTag<T> {
        val tag = this[key]
        if (tag !is ListTag<*>) throw IllegalArgumentException("No list tag for $key")
        val listType = tag.verify()
        @Suppress("UNCHECKED_CAST")
        if (listType == END || getId(T::class) == listType) return tag as ListTag<T>
        throw IllegalArgumentException("Invalid list tag for $key")
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        if (value.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        path.addLast("{}")
        val indent = indentString.isNotEmpty() && !NO_INDENT.contains(path)
        var first = true
        for (k in getOrderedKeys(path)) {
            val v = value[k]!!
            if (!first) sb.append(',')
            if (indent) {
                sb.append('\n')
                for (i in 0 .. depth) sb.append("    ")
            } else if (!first) {
                sb.append(' ')
            }
            first = false
            if (StringTag.SIMPLE.matches(k)) {
                sb.append(k)
            } else {
                StringTag.escape(sb, k)
            }
            sb.append(": ")
            path.addLast(k)
            v.toString(sb, depth + 1, path, if (indent) indentString else "")
            path.removeLast()
        }
        path.removeLast()
        if (indent) {
            sb.append('\n')
            for (i in 0 until depth) sb.append("    ")
        }
        sb.append('}')
    }

    override fun toString() = "CompoundTag$value"

    private fun getOrderedKeys(path: List<String>): List<String> {
        var set = keys
        val ordered = mutableListOf<String>()
        KEY_ORDER[path]?.let {
            set = HashSet(set)
            for (key in it) {
                if (set.remove(key)) ordered.add(key)
            }
        }
        ordered.addAll(set.sorted())
        return ordered
    }

    companion object : TagType<CompoundTag> {
        private val KEY_ORDER = mapOf(
            listOf("{}") to listOf("DataVersion", "author", "size", "data", "entities", "palette", "palettes"),
            listOf("{}", "data", "[]", "{}") to listOf("pos", "state", "nbt"),
            listOf("{}", "entities", "[]", "{}") to listOf("blockPos", "pos")
        )

        override fun read(din: DataInput): CompoundTag {
            val map = LinkedHashMap<String, Tag>(8)
            while (true) {
                val id = din.readByte().toInt()
                if (id == 0) break
                map[din.readUTF()] = read(id, din)
            }
            return CompoundTag(map)
        }
    }
}

data class IntArrayTag(val value: IntArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        for (i in value) out.writeInt(i)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IntArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode() = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[I;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i])
        }
        sb.append(']')
    }

    companion object : TagType<IntArrayTag> {
        override fun read(din: DataInput): IntArrayTag {
            val value = IntArray(din.readInt())
            for (i in value.indices) value[i] = din.readInt()
            return IntArrayTag(value)
        }
    }
}

data class LongArrayTag(val value: LongArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        for (l in value) out.writeLong(l)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LongArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode() = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[L;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i]).append('L')
        }
        sb.append(']')
    }

    companion object : TagType<LongArrayTag> {
        override fun read(din: DataInput): LongArrayTag {
            val value = LongArray(din.readInt())
            for (i in value.indices) value[i] = din.readLong()
            return LongArrayTag(value)
        }
    }
}