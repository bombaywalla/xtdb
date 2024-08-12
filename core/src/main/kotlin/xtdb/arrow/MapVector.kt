package xtdb.arrow

import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.util.ByteFunctionHelpers
import org.apache.arrow.memory.util.hash.ArrowBufHasher
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType

class MapVector(private val listVector: ListVector, private val keysSorted: Boolean) : Vector() {

    override val name get() = listVector.name
    override var nullable: Boolean
        get() = listVector.nullable
        set(value) {
            listVector.nullable = value
        }

    override val field: Field
        get() = Field(name, FieldType(nullable, ArrowType.Map(keysSorted), null), listVector.field.children)

    override var valueCount: Int
        get() = listVector.valueCount
        set(value) {
            listVector.valueCount = value
        }

    override fun isNull(idx: Int) = listVector.isNull(idx)
    override fun writeNull() = listVector.writeNull()

    override fun getListCount(idx: Int) = listVector.getListCount(idx)
    override fun getListStartIndex(idx: Int) = listVector.getListStartIndex(idx)

    @Suppress("MemberVisibilityCanBePrivate")
    internal fun getListEndIndex(idx: Int) = listVector.getListEndIndex(idx)

    override fun hashCode0(idx: Int, hasher: ArrowBufHasher) =
        (getListStartIndex(idx) until getListEndIndex(idx)).fold(0) { hash, elIdx ->
            // this should technically be an unsorted hash, because maps are unordered
            // but Arrow Java does it this way, so for now we'll be consistent with them.
            ByteFunctionHelpers.combineHash(hash, listVector.hashCode(elIdx, hasher))
        }

    override fun unloadBatch(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) =
        listVector.unloadBatch(nodes, buffers)

    override fun loadBatch(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) =
        listVector.loadBatch(nodes, buffers)

    override fun getObject0(idx: Int): Any {
        val startIdx = listVector.getListStartIndex(idx)
        val entryCount = listVector.getListCount(idx)

        val elReader = listVector.elementReader()
        val keyReader = elReader.mapKeyReader()
        val valueReader = elReader.mapValueReader()

        return (0 until entryCount).associate { elIdx ->
            (startIdx + elIdx).let { entryIdx ->
                keyReader.getObject(entryIdx) to valueReader.getObject(entryIdx)
            }
        }
    }

    override fun writeObject0(value: Any) = when (value) {
        is Map<*, *> -> {
            val elWriter = listVector.elementWriter()
            val keyWriter = elWriter.mapKeyWriter()
            val valueWriter = elWriter.mapValueWriter()

            value.forEach { k, v ->
                keyWriter.writeObject(k)
                valueWriter.writeObject(v)
                elWriter.endStruct()
            }

            listVector.endList()
        }

        else -> TODO("promotion: ${value::class.simpleName}")
    }

    override fun elementReader() = listVector.elementReader()
    override fun elementWriter() = listVector.elementWriter()
    override fun elementWriter(fieldType: FieldType) = listVector.elementWriter(fieldType)

    override fun endList() = listVector.endList()

    override fun mapKeyReader() = elementReader().mapKeyReader()
    override fun mapValueReader() = elementReader().mapValueReader()
    override fun mapKeyWriter() = elementWriter().mapKeyWriter()
    override fun mapKeyWriter(fieldType: FieldType) = elementWriter().mapKeyWriter(fieldType)
    override fun mapValueWriter() = elementWriter().mapValueWriter()
    override fun mapValueWriter(fieldType: FieldType) = elementWriter().mapValueWriter(fieldType)

    override fun reset() = listVector.reset()
    override fun close() = listVector.close()
}