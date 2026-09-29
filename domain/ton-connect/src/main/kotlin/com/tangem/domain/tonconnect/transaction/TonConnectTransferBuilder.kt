package com.tangem.domain.tonconnect.transaction

import com.tangem.domain.tonconnect.crypto.TonConnectSigner
import com.tangem.domain.tonconnect.crypto.requireSignatureLength
import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.math.BigInteger
import java.util.Base64

/**
 * Builds and signs the wallet-v4R2 external message for a validated `sendTransaction` request.
 *
 * `TonTransactionBuilder` in blockchain-sdk-kotlin goes through WalletCore, whose `Transfer` message only
 * supports a text comment or a jetton transfer. dApps send arbitrary `payload` / `stateInit` cells, so TON Connect
 * transfers are assembled here with ton-kotlin cells — same contract code, wallet id and signing layout, just with
 * caller-supplied bodies. Inline-vs-reference decisions follow `@ton/core` / TonSwift so the signed bytes are
 * identical across platforms.
 */
class TonConnectTransferBuilder(
    publicKey: ByteArray,
    private val workchain: Int = 0,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {

    /** Everything needed to sign and later assemble the external message. */
    class Prepared internal constructor(
        /** 32-byte hash of the signing message — this is what the card signs. */
        val hashToSign: ByteArray,
        /** Unix seconds; the transfer is rejected by the contract after this moment. */
        val expiresAt: Long,
        internal val signingMessage: Cell,
        internal val walletAddress: TonAddress,
        internal val stateInit: Cell?,
    )

    init {
        if (publicKey.size != PUBLIC_KEY_BYTE_COUNT) {
            throw TonConnectException.InternalFailure("TON public key must be 32 bytes")
        }
    }

    private val walletId: Int = WALLET_V4_BASE_ID + workchain

    private val stateInitCell: Cell = CellBuilder.beginCell()
        .storeBit(false) // split_depth: nothing
        .storeBit(false) // special: nothing
        .storeBit(true).storeRef(WALLET_V4R2_CODE) // code
        .storeBit(true).storeRef(
            CellBuilder.beginCell()
                .storeUInt(0, 32) // seqno
                .storeUInt(walletId, 32)
                .storeBytes(publicKey)
                .storeBit(false) // plugins: empty dict
                .endCell(),
        )
        .storeBit(false) // library: empty
        .endCell()

    val address: TonAddress = TonAddress(workchain, TonCells.hash(stateInitCell))

    /** The wallet's `StateInit` as a standard base64 BoC for the `ton_addr` reply. */
    fun stateInitBoc(): String = TonCells.toBocBase64(stateInitCell)

    /**
     * Builds the unsigned transfer. [seqno] is the account's current sequence number; `0` means the contract is not
     * deployed yet and its `StateInit` is attached to the external message.
     */
    fun prepare(transaction: TonConnectValidatedTransaction, seqno: Long): Prepared {
        if (transaction.messages.size > TonConnectSendTransactionValidator.WALLET_V4_MAX_MESSAGES) {
            throw TonConnectException.BadRequest("wallet v4 accepts at most 4 messages per transfer")
        }
        if (seqno < 0 || seqno > UINT32_MAX) throw TonConnectException.InternalFailure("seqno out of range")

        val expiresAt = expiration(transaction.validUntil)

        val signingMessage = CellBuilder.beginCell()
            .storeUInt(walletId, 32)
            .storeUInt(expiresAt, 32)
            .storeUInt(seqno, 32)
            .storeUInt(0, 8) // op: simple send
        transaction.messages.forEach { message ->
            signingMessage.storeUInt(SEND_MODE, 8)
            signingMessage.storeRef(internalMessage(message))
        }
        val signingCell = signingMessage.endCell()

        return Prepared(
            hashToSign = TonCells.hash(signingCell),
            expiresAt = expiresAt,
            signingMessage = signingCell,
            walletAddress = address,
            stateInit = if (seqno == 0L) stateInitCell else null,
        )
    }

    /**
     * Wraps the signature and the signing message into the external message the network accepts. Returns the
     * standard base64 BoC that is both broadcast and returned to the dApp as `result`.
     */
    fun assemble(prepared: Prepared, signature: ByteArray): String {
        signature.requireSignatureLength()

        // Wallet v4 expects `signature ‖ signing_message` as the external message body.
        val body = CellBuilder.beginCell()
            .storeBytes(signature)
            .storeBits(prepared.signingMessage.bits)
            .storeRefs(prepared.signingMessage.refs)
            .endCell()

        val external = CellBuilder.beginCell()
            .storeBits(true, false) // ext_in_msg_info$10
            .storeBits(false, false) // src: addr_none
        prepared.walletAddress.storeTo(external)
        TonCells.storeCoins(external, BigInteger.ZERO) // import_fee
        storeMaybeEither(external, prepared.stateInit, reservedBits = 2)
        storeEither(external, body, reservedBits = 1)

        return TonCells.toBocBase64(external.endCell())
    }

    /** `prepare` + sign + `assemble` in one step. */
    suspend fun sign(transaction: TonConnectValidatedTransaction, seqno: Long, signer: TonConnectSigner): String {
        val prepared = prepare(transaction, seqno)
        return assemble(prepared, signer.sign(prepared.hashToSign))
    }

    /** `int_msg_info$0` relaxed message with the dApp-supplied body and optional deploy `StateInit`. */
    private fun internalMessage(message: TonConnectValidatedTransaction.Message): Cell {
        val builder = CellBuilder.beginCell()
            .storeBit(false) // int_msg_info$0
            .storeBit(true) // ihr_disabled
            .storeBit(message.bounce)
            .storeBit(false) // bounced
            .storeBits(false, false) // src: addr_none
        message.destination.storeTo(builder)
        TonCells.storeCoins(builder, message.amount)
        builder.storeBit(false) // extra currencies: empty
        TonCells.storeCoins(builder, BigInteger.ZERO) // ihr_fee
        TonCells.storeCoins(builder, BigInteger.ZERO) // fwd_fee
        builder.storeUInt(0, 64) // created_lt
        builder.storeUInt(0, 32) // created_at
        storeMaybeEither(builder, message.stateInit, reservedBits = 2)
        storeEither(builder, message.payload ?: EMPTY_CELL, reservedBits = 1)
        return builder.endCell()
    }

    /** `Maybe (Either X ^X)`: absent → `0`; present → `1` then inline or ref like [storeEither]. */
    private fun storeMaybeEither(builder: CellBuilder, cell: Cell?, reservedBits: Int) {
        if (cell == null) {
            builder.storeBit(false)
            return
        }
        builder.storeBit(true)
        storeEither(builder, cell, reservedBits)
    }

    /** `Either X ^X`: inline (`0`) when the cell fits with [reservedBits] to spare, otherwise by reference (`1`). */
    private fun storeEither(builder: CellBuilder, cell: Cell, reservedBits: Int) {
        val fitsInline = builder.remainingBits - cell.bits.size >= reservedBits &&
            TonCells.MAX_REFS - builder.refs.size >= cell.refs.size
        if (fitsInline) {
            builder.storeBit(false)
            builder.storeBits(cell.bits)
            builder.storeRefs(cell.refs)
        } else {
            builder.storeBit(true)
            builder.storeRef(cell)
        }
    }

    private fun expiration(validUntil: Long?): Long {
        val fallback = nowEpochSeconds() + DEFAULT_TIMEOUT_SECONDS
        // Honour the dApp's deadline but never let a signed message outlive the wallet's own bound.
        return if (validUntil == null) fallback else minOf(validUntil, fallback)
    }

    companion object {
        /** Default lifetime of the signed message when the dApp did not set `valid_until`. */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 5 * 60

        /** Outgoing messages are sent with mode 3 (`PAY_GAS_SEPARATELY | IGNORE_ERRORS`), as the spec requires. */
        const val SEND_MODE: Int = 3

        private const val PUBLIC_KEY_BYTE_COUNT = 32
        private const val WALLET_V4_BASE_ID = 698_983_191
        private const val UINT32_MAX = 0xFFFF_FFFFL
        private const val MILLIS_PER_SECOND = 1000L

        private val EMPTY_CELL: Cell = CellBuilder.beginCell().endCell()

        /** wallet-v4-r2 contract code, identical to the constant in TonSwift / WalletCore. */
        private val WALLET_V4R2_CODE: Cell = BagOfCells(
            Base64.getDecoder().decode(
                "te6ccgECFAEAAtQAART/APSkE/S88sgLAQIBIAIDAgFIBAUE+PKDCNcYINMf0x/THwL4I7vyZO1E0NMf0x/T//QE0VFDuvKhUVG68qIF+QFUEGT5EPKj+AAkpMjLH1JAyx9SMMv/UhD0AMntVPgPAdMHIcAAn2xRkyDXSpbTB9QC+wDoMOAhwAHjACHAAuMAAcADkTDjDQOkyMsfEssfy/8QERITAubQAdDTAyFxsJJfBOAi10nBIJJfBOAC0x8hghBwbHVnvSKCEGRzdHK9sJJfBeAD+kAwIPpEAcjKB8v/ydDtRNCBAUDXIfQEMFyBAQj0Cm+hMbOSXwfgBdM/yCWCEHBsdWe6kjgw4w0DghBkc3RyupJfBuMNBgcCASAICQB4AfoA9AQw+CdvIjBQCqEhvvLgUIIQcGx1Z4MesXCAGFAEywUmzxZY+gIZ9ADLaRfLH1Jgyz8gyYBA+wAGAIpQBIEBCPRZMO1E0IEBQNcgyAHPFvQAye1UAXKwjiOCEGRzdHKDHrFwgBhQBcsFUAPPFiP6AhPLassfyz/JgED7AJJfA+ICASAKCwBZvSQrb2omhAgKBrkPoCGEcNQICEekk30pkQzmkD6f+YN4EoAbeBAUiYcVnzGEAgFYDA0AEbjJftRNDXCx+AA9sp37UTQgQFA1yH0BDACyMoHy//J0AGBAQj0Cm+hMYAIBIA4PABmtznaiaEAga5Drhf/AABmvHfaiaEAQa5DrhY/AAG7SB/oA1NQi+QAFyMoHFcv/ydB3dIAYyMsFywIizxZQBfoCFMtrEszMyXP7AMhAFIEBCPRR8qcCAHCBAQjXGPoA0z/IVCBHgQEI9FHyp4IQbm90ZXB0gBjIywXLAlAGzxZQBPoCFMtqEssfyz/Jc/sAAgBsgQEI1xj6ANM/MFIkgQEI9Fnyp4IQZHN0cnB0gBjIywXLAlAFzxZQA/oCE8tqyx8Syz/Jc/sAAAr0AMntVA==",
            ),
        ).roots.single()

        /**
         * Cheap structural check that a dApp-supplied cell can be read as `StateInit`
         * (`split_depth:(Maybe (## 5)) special:(Maybe TickTock) code:(Maybe ^Cell) data:(Maybe ^Cell) library:(HashmapE 256 SimpleLib)`).
         */
        internal fun isStateInitShaped(cell: Cell): Boolean {
            return runCatching {
                val slice = cell.beginParse()
                var refsNeeded = 0
                if (slice.loadBit()) slice.loadUInt(5) // split_depth
                if (slice.loadBit()) slice.loadBits(2) // special: TickTock
                if (slice.loadBit()) refsNeeded++ // code
                if (slice.loadBit()) refsNeeded++ // data
                if (slice.loadBit()) refsNeeded++ // library: non-empty HashmapE root ref
                cell.refs.size == refsNeeded && slice.remainingBits == 0
            }.getOrDefault(false)
        }
    }
}
