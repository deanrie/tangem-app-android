package com.tangem.domain.tonconnect.transaction

import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.math.BigInteger
import java.util.Base64

/** Small helpers over ton-kotlin cells used by the transfer builder and the `signData` cell digest. */
internal object TonCells {

    const val MAX_BITS = 1023
    const val MAX_REFS = 4

    /** Representation hash (level 0) of a cell as raw bytes. */
    fun hash(cell: Cell): ByteArray = cell.hash(0).toByteArray()

    /** Standard base64 BoC (no index, CRC32C — ton-kotlin's default), the form dApps expect back. */
    fun toBocBase64(cell: Cell): String = Base64.getEncoder().encodeToString(toBoc(cell))

    fun toBoc(cell: Cell): ByteArray = BagOfCells(cell).toByteArray()

    /** Snake-encoded bytes: as many as fit in the current cell, the tail in a chained ref. */
    fun snakeData(data: ByteArray): Cell = CellBuilder.beginCell().also { writeSnakeData(it, data) }.endCell()

    private fun writeSnakeData(builder: CellBuilder, data: ByteArray) {
        if (data.isEmpty()) return
        val capacity = builder.remainingBits / 8
        if (data.size <= capacity) {
            builder.storeBytes(data)
        } else {
            builder.storeBytes(data.copyOfRange(0, capacity))
            builder.storeRef(snakeData(data.copyOfRange(capacity, data.size)))
        }
    }

    /** `VarUInteger 16` (Coins): 4-bit byte-length prefix followed by the big-endian magnitude. */
    fun storeCoins(builder: CellBuilder, value: BigInteger): CellBuilder {
        require(value.signum() >= 0 && value.bitLength() <= 120) { "coins out of range" }
        if (value.signum() == 0) return builder.storeUInt(0, 4)
        val magnitude = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return builder.storeUInt(magnitude.size, 4).storeBytes(magnitude)
    }
}
