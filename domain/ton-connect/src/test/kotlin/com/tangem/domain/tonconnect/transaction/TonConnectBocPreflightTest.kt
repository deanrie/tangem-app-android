package com.tangem.domain.tonconnect.transaction

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.transaction.TonConnectBoc.PreflightException
import com.tangem.domain.tonconnect.transaction.TonConnectBoc.PreflightReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.util.Base64

/** Every malformed BoC must become a `BadRequest` with a reason before ton-kotlin parses it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectBocPreflightTest {

    /** Root with a 32-bit payload and two child cells (one shared twice). */
    private fun makeTree(): Cell {
        val leaf = CellBuilder.beginCell().storeUInt(0xAB, 8).endCell()
        val branch = CellBuilder.beginCell().storeUInt(1, 8).storeRef(leaf).endCell()
        return CellBuilder.beginCell().storeUInt(0xDEADBEEFL, 32).storeRef(branch).storeRef(leaf).endCell()
    }

    private fun reasonOf(data: ByteArray): PreflightReason = assertThrows<PreflightException> { TonConnectBoc.preflight(data) }.reason

    private fun mutated(data: ByteArray, offset: Int, value: Int): ByteArray = data.copyOf().also { it[offset] = value.toByte() }

    @Test
    fun `GIVEN well-formed BoC WHEN preflighting and parsing THEN root round-trips`() {
        val root = makeTree()
        val data = TonCells.toBoc(root)

        TonConnectBoc.preflight(data)
        assertThat(TonConnectBoc.singleRootCell(Base64.getEncoder().encodeToString(data), "payload")).isEqualTo(root)
    }

    @Test
    fun `GIVEN canonical empty-cell BoC WHEN parsing THEN empty cell is returned`() {
        // `beginCell().endCell()` serialised by @ton/core.
        val empty = TonConnectBoc.singleRootCell("te6ccgEBAQEAAgAAAA==", "p")

        assertThat(empty.bits.size).isEqualTo(0)
        assertThat(empty.refs).isEmpty()
    }

    @Test
    fun `GIVEN truncated or foreign input WHEN preflighting THEN reasons are reported`() {
        val data = TonCells.toBoc(makeTree())

        assertThat(reasonOf(ByteArray(0))).isEqualTo(PreflightReason.TRUNCATED)
        assertThat(reasonOf(data.copyOf(5))).isEqualTo(PreflightReason.TRUNCATED)
        assertThat(reasonOf(data.copyOf(data.size - 1))).isEqualTo(PreflightReason.TRUNCATED)
        assertThat(reasonOf(ByteArray(16))).isEqualTo(PreflightReason.BAD_MAGIC)
        assertThat(reasonOf(data + byteArrayOf(0))).isEqualTo(PreflightReason.TRAILING_BYTES)
    }

    @Test
    fun `GIVEN corrupted header fields WHEN preflighting THEN each is rejected`() {
        // Layout: magic(4) flags(1: crc|size=1) offBytes(1) cells(1) roots(1) absent(1) totalSize(1) rootIdx(1) cells…
        val data = TonCells.toBoc(makeTree())
        val flags = data[4].toInt() and 0xFF

        assertThat(reasonOf(mutated(data, 4, flags or 0x10))).isEqualTo(PreflightReason.BAD_FLAGS)
        assertThat(reasonOf(mutated(data, 4, flags and 0x07.inv()))).isEqualTo(PreflightReason.BAD_SIZE_BYTES)
        assertThat(reasonOf(mutated(data, 4, flags or 0x07))).isEqualTo(PreflightReason.BAD_SIZE_BYTES)
        assertThat(reasonOf(mutated(data, 5, 0))).isEqualTo(PreflightReason.BAD_OFFSET_BYTES)
        assertThat(reasonOf(mutated(data, 5, 9))).isEqualTo(PreflightReason.BAD_OFFSET_BYTES)
        assertThat(reasonOf(mutated(data, 6, 0))).isEqualTo(PreflightReason.BAD_CELL_COUNT)
        assertThat(reasonOf(mutated(data, 7, 2))).isEqualTo(PreflightReason.NOT_SINGLE_ROOT)
        assertThat(reasonOf(mutated(data, 7, 0))).isEqualTo(PreflightReason.NOT_SINGLE_ROOT)
        assertThat(reasonOf(mutated(data, 8, 1))).isEqualTo(PreflightReason.ABSENT_CELLS)
        assertThat(reasonOf(mutated(data, 10, 3))).isEqualTo(PreflightReason.BAD_ROOT_INDEX)
        assertThat(reasonOf(mutated(data, 9, (data[9].toInt() and 0xFF) - 1))).isEqualTo(PreflightReason.BAD_CELL_DATA_SIZE)
    }

    @Test
    fun `GIVEN corrupted cell descriptors WHEN preflighting THEN each is rejected`() {
        val data = TonCells.toBoc(makeTree())
        val firstCell = 11 // d1 of the root cell: 2 refs, d2 = 8 (4 bytes), data(4), ref(1), ref(1)
        val firstRef = firstCell + 2 + 4

        assertThat(reasonOf(mutated(data, firstCell, 0x05))).isEqualTo(PreflightReason.TOO_MANY_REFS)
        assertThat(reasonOf(mutated(data, firstCell, 0x22))).isEqualTo(PreflightReason.BAD_LEVEL)
        assertThat(reasonOf(mutated(data, firstCell, 0x0A))).isEqualTo(PreflightReason.UNSUPPORTED_EXOTIC)
        assertThat(reasonOf(mutated(data, firstRef, 0))).isEqualTo(PreflightReason.BAD_REF_INDEX) // self-reference
        assertThat(reasonOf(mutated(data, firstRef, 200))).isEqualTo(PreflightReason.BAD_REF_INDEX) // out of table
    }

    @Test
    fun `GIVEN out-of-range ref index WHEN parsing via singleRootCell THEN BadRequest carries the reason`() {
        val corrupted = mutated(TonCells.toBoc(makeTree()), 17, 200)

        val error = assertThrows<TonConnectException.BadRequest> {
            TonConnectBoc.singleRootCell(Base64.getEncoder().encodeToString(corrupted), "payload")
        }

        assertThat(error.protocolMessage).isEqualTo("payload is not a valid BoC: ref index is not a forward reference")
    }
}
