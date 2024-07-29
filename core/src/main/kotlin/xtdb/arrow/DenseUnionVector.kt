package xtdb.arrow

import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType

class DenseUnionVector(
    private val allocator: BufferAllocator,
    override val name: String,
    override var nullable: Boolean,
    legs: List<Vector>
) : Vector() {

    private val legs = legs.toMutableList()

    inner class LegReader(val inner: VectorReader) : VectorReader {
        override val name get() = inner.name
        override val nullable get() = inner.nullable
        override val valueCount get() = inner.valueCount
        override val arrowField get() = inner.arrowField

        override fun isNull(idx: Int) = inner.isNull(getOffset(idx))
        override fun getByte(idx: Int) = inner.getByte(getOffset(idx))
        override fun getShort(idx: Int) = inner.getShort(getOffset(idx))
        override fun getInt(idx: Int) = inner.getInt(getOffset(idx))
        override fun getLong(idx: Int) = inner.getLong(getOffset(idx))
        override fun getFloat(idx: Int) = inner.getFloat(getOffset(idx))
        override fun getDouble(idx: Int) = inner.getDouble(getOffset(idx))
        override fun getBytes(idx: Int) = inner.getBytes(getOffset(idx))
        override fun getObject(idx: Int) = inner.getObject(getOffset(idx))

        override fun getListCount(idx: Int) = inner.getListCount(getOffset(idx))
        override fun getListStartIndex(idx: Int) = inner.getListStartIndex(getOffset(idx))

        override fun toList() = inner.toList()

        override fun close() = Unit
    }

    inner class LegWriter(private val typeId: Byte, val inner: Vector) : VectorWriter {

        private fun writeValueThen(): Vector {
            typeBuffer.writeByte(typeId)
            offsetBuffer.writeInt(inner.valueCount)
            this@DenseUnionVector.valueCount++
            return inner
        }

        override fun writeNull() = writeValueThen().writeNull()

        override fun writeByte(value: Byte) = writeValueThen().writeByte(value)

        override fun writeShort(value: Short) = writeValueThen().writeShort(value)

        override fun writeInt(value: Int) = writeValueThen().writeInt(value)

        override fun writeLong(value: Long) = writeValueThen().writeLong(value)

        override fun writeFloat(value: Float) = writeValueThen().writeFloat(value)

        override fun writeDouble(value: Double) = writeValueThen().writeDouble(value)

        override fun writeBytes(bytes: ByteArray) = writeValueThen().writeBytes(bytes)

        override fun writeObject(value: Any?) = writeValueThen().writeObject(value)

        override fun endStruct() = writeValueThen().endStruct()

        override fun endList() = writeValueThen().endList()

        override fun reset() = inner.reset()
        override fun close() = Unit

        override fun toList() = inner.toList()
    }

    override val arrowField = Field(name, FieldType.notNullable(MinorType.DENSEUNION.type), legs.map { it.arrowField })

    private val typeBuffer = ExtensibleBuffer(allocator)
    private fun getTypeId(idx: Int) = typeBuffer.getByte(idx)
    internal fun typeIds() = (0 until valueCount).map { typeBuffer.getByte(it) }

    private val offsetBuffer = ExtensibleBuffer(allocator)
    fun getOffset(idx: Int) = offsetBuffer.getInt(idx)
    internal fun offsets() = (0 until valueCount).map { offsetBuffer.getInt(it) }

    private fun leg(idx: Int): Vector? {
        val typeId = getTypeId(idx)
        return if (typeId >= 0) legs[getTypeId(idx).toInt()] else null
    }

    override fun isNull(idx: Int) = leg(idx)?.isNull(idx) ?: false

    override fun writeNull() {
        typeBuffer.writeByte(-1)
        offsetBuffer.writeInt(0)
    }

    override fun getObject(idx: Int) = leg(idx)?.getObject(getOffset(idx))
    override fun getObject0(idx: Int) = throw UnsupportedOperationException()
    override fun writeObject0(value: Any) = throw UnsupportedOperationException()

    override fun legReader(name: String): VectorReader {
        for (i in legs.indices) {
            val leg = legs[i]
            if (leg.name == name) return LegReader(leg)
        }

        error("no leg: $name")
    }

    override fun legWriter(name: String): VectorWriter {
        for (i in legs.indices) {
            val leg = legs[i]
            if (leg.name == name) return LegWriter(i.toByte(), leg)
        }

        TODO("auto-creation")
    }

    override fun legWriter(name: String, fieldType: FieldType): VectorWriter {
        for (i in legs.indices) {
            val leg = legs[i]
            if (leg.name == name) {
                if (leg.arrowField.fieldType != fieldType) TODO("promotion")

                return LegWriter(i.toByte(), leg)
            }
        }

        return LegWriter(
            legs.size.toByte(),
            fromField(Field(name, fieldType, emptyList()), allocator).also { legs.add(it) }
        )
    }

    override fun unloadBatch(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        nodes.add(ArrowFieldNode(valueCount.toLong(), -1))
        typeBuffer.unloadBuffer(buffers)
        offsetBuffer.unloadBuffer(buffers)

        legs.forEach { it.unloadBatch(nodes, buffers) }
    }

    override fun loadBatch(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        val node = nodes.removeFirst() ?: throw IllegalStateException("missing node")

        typeBuffer.loadBuffer(buffers.removeFirst() ?: throw IllegalStateException("missing type buffer"))
        offsetBuffer.loadBuffer(buffers.removeFirst() ?: throw IllegalStateException("missing offset buffer"))
        legs.forEach { it.loadBatch(nodes, buffers) }

        valueCount = node.length
    }

    override fun reset() {
        typeBuffer.reset()
        offsetBuffer.reset()
        legs.forEach { it.reset() }
        valueCount = 0
    }

    override fun close() {
        typeBuffer.close()
        offsetBuffer.close()
        legs.forEach { it.close() }
        valueCount = 0
    }
}
