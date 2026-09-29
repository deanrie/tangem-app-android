package com.tangem.domain.tonconnect.transaction

import com.tangem.domain.tonconnect.model.TonConnectException
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import java.util.Base64

/**
 * Helpers for the base64 "raw one-cell BoC" fields dApps send (`payload`, `stateInit`, `cell`).
 *
 * A dApp-supplied BoC is untrusted input. Before ton-kotlin parses it, [preflight] walks the
 * `serialized_boc#b5ee9c72` layout and validates every length and index, so a hostile header is answered with a
 * `BAD_REQUEST` carrying a reason instead of whatever the deserialiser throws (or allocates) on the way.
 */
object TonConnectBoc {

    /** Upper bound on a serialised BoC accepted from a dApp; real payloads are a few hundred bytes. */
    const val MAX_SERIALIZED_BYTE_COUNT: Int = 64 * 1024

    /** Upper bound on the number of cells in one BoC. */
    const val MAX_CELL_COUNT: Int = 4096

    /**
     * Upper bound on the depth of the cell tree. Real payloads are a handful of levels deep (a jetton transfer is 3,
     * a DEX swap about 6); cell libraries hash and serialise trees recursively, and a chain a few hundred cells deep
     * can overflow a worker thread's stack. Refusing anything deeper than 64 keeps that path unreachable from a dApp.
     */
    const val MAX_DEPTH: Int = 64

    private const val MAGIC = 0xB5EE9C72L

    /** Decodes a base64 (standard or url-safe alphabet) BoC that must contain exactly one root cell. */
    fun singleRootCell(base64: String, field: String): Cell {
        val data = decodeBase64(base64) ?: throw TonConnectException.BadRequest("$field is not valid base64")

        if (data.size > MAX_SERIALIZED_BYTE_COUNT) {
            throw TonConnectException.BadRequest("$field exceeds $MAX_SERIALIZED_BYTE_COUNT bytes")
        }

        try {
            preflight(data)
        } catch (e: PreflightException) {
            throw TonConnectException.BadRequest("$field is not a valid BoC: ${e.reason.description}")
        }

        val roots = try {
            BagOfCells(data).roots
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw TonConnectException.BadRequest("$field is not a valid BoC")
        }

        return roots.singleOrNull() ?: throw TonConnectException.BadRequest("$field must contain exactly one root cell")
    }

    enum class PreflightReason(val description: String) {
        TRUNCATED("truncated"),
        BAD_MAGIC("unsupported magic"),
        BAD_FLAGS("reserved flag bits set"),
        BAD_SIZE_BYTES("invalid ref size"),
        BAD_OFFSET_BYTES("invalid offset size"),
        BAD_CELL_COUNT("invalid cell count"),
        NOT_SINGLE_ROOT("root count is not 1"),
        ABSENT_CELLS("absent cells are not supported"),
        BAD_ROOT_INDEX("root index out of range"),
        BAD_CELL_DATA_SIZE("cell data size mismatch"),
        TOO_MANY_REFS("cell has more than 4 refs"),
        BAD_LEVEL("non-zero level is not supported"),
        BAD_REF_INDEX("ref index is not a forward reference"),
        UNSUPPORTED_EXOTIC("exotic cells are not supported"),
        TOO_DEEP("cell tree is too deep"),
        TRAILING_BYTES("trailing bytes after BoC"),
    }

    class PreflightException(val reason: PreflightReason) : Exception(reason.description)

    /**
     * Only what a dApp legitimately sends is accepted: a single root, no absent cells, ordinary (non-exotic)
     * level-0 cells and forward-only references.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod", "LongMethod")
    fun preflight(data: ByteArray) {
        val cursor = ByteCursor(data)

        if (cursor.readUInt(4) != MAGIC) throw PreflightException(PreflightReason.BAD_MAGIC)

        val flags = cursor.readByte()
        val hasIndex = flags and 0x80 != 0
        val hasCrc32c = flags and 0x40 != 0
        if (flags and 0x18 != 0) throw PreflightException(PreflightReason.BAD_FLAGS) // reserved flags must be 0
        val refByteCount = flags and 0x07
        if (refByteCount !in 1..4) throw PreflightException(PreflightReason.BAD_SIZE_BYTES)

        val offsetByteCount = cursor.readByte()
        if (offsetByteCount !in 1..8) throw PreflightException(PreflightReason.BAD_OFFSET_BYTES)

        val cellCount = cursor.readUInt(refByteCount)
        val rootCount = cursor.readUInt(refByteCount)
        val absentCount = cursor.readUInt(refByteCount)
        val totalCellSize = cursor.readUInt(offsetByteCount)

        if (cellCount < 1 || cellCount > MAX_CELL_COUNT) throw PreflightException(PreflightReason.BAD_CELL_COUNT)
        if (rootCount != 1L) throw PreflightException(PreflightReason.NOT_SINGLE_ROOT)
        if (absentCount != 0L) throw PreflightException(PreflightReason.ABSENT_CELLS)

        val rootIndex = cursor.readUInt(refByteCount)
        if (rootIndex >= cellCount) throw PreflightException(PreflightReason.BAD_ROOT_INDEX)

        if (hasIndex) cursor.skip(cellCount.toInt() * offsetByteCount)

        // An 8-byte size can exceed Long.MAX_VALUE and wrap negative; either way it cannot fit the buffer.
        if (totalCellSize < 0 || totalCellSize > cursor.remaining) throw PreflightException(PreflightReason.TRUNCATED)
        val cellDataEnd = cursor.position + totalCellSize.toInt()

        // Refs only point forward, so the depth of every cell follows from its refs in a single reverse pass.
        val refsOf = Array(cellCount.toInt()) { IntArray(0) }

        for (index in 0 until cellCount.toInt()) {
            val d1 = cursor.readByte()
            val d2 = cursor.readByte()

            val refCount = d1 and 0x07
            val isExotic = d1 and 0x08 != 0
            val level = d1 shr 5
            if (refCount > TonCells.MAX_REFS) throw PreflightException(PreflightReason.TOO_MANY_REFS)
            if (level != 0) throw PreflightException(PreflightReason.BAD_LEVEL)
            // Exotic cells (pruned branches, Merkle proofs/updates, library cells) have no place in an outgoing
            // message body or a deploy StateInit sent by a dApp.
            if (isExotic) throw PreflightException(PreflightReason.UNSUPPORTED_EXOTIC)

            cursor.skip((d2 + 1) / 2)

            refsOf[index] = IntArray(refCount) {
                val ref = cursor.readUInt(refByteCount)
                if (ref <= index || ref >= cellCount) throw PreflightException(PreflightReason.BAD_REF_INDEX)
                ref.toInt()
            }

            if (cursor.position > cellDataEnd) throw PreflightException(PreflightReason.BAD_CELL_DATA_SIZE)
        }

        if (cursor.position != cellDataEnd) throw PreflightException(PreflightReason.BAD_CELL_DATA_SIZE)

        val depth = IntArray(cellCount.toInt())
        for (index in cellCount.toInt() - 1 downTo 0) {
            depth[index] = (refsOf[index].maxOfOrNull { depth[it] } ?: -1) + 1
            if (depth[index] > MAX_DEPTH) throw PreflightException(PreflightReason.TOO_DEEP)
        }

        if (hasCrc32c) cursor.skip(4)

        if (cursor.remaining != 0) throw PreflightException(PreflightReason.TRAILING_BYTES)
    }

    private class ByteCursor(private val data: ByteArray) {
        var position: Int = 0
            private set

        val remaining: Int get() = data.size - position

        fun readByte(): Int {
            if (remaining < 1) throw PreflightException(PreflightReason.TRUNCATED)
            return data[position++].toInt() and 0xFF
        }

        fun readUInt(byteCount: Int): Long {
            if (byteCount > 8 || remaining < byteCount) throw PreflightException(PreflightReason.TRUNCATED)
            var value = 0L
            repeat(byteCount) { value = (value shl 8) or readByte().toLong() }
            return value
        }

        fun skip(count: Int) {
            if (count < 0 || remaining < count) throw PreflightException(PreflightReason.TRUNCATED)
            position += count
        }
    }

    private fun decodeBase64(text: String): ByteArray? {
        var normalized = text.replace('-', '+').replace('_', '/')
        val remainder = normalized.length % 4
        if (remainder != 0) normalized += "=".repeat(4 - remainder)
        return runCatching { Base64.getDecoder().decode(normalized) }.getOrNull()
    }
}
