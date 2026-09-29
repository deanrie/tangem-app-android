package com.tangem.domain.tonconnect.transaction

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.FakeTonConnectSigner
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.hexToBytes
import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.TonConnectSendTransactionPayload
import com.tangem.domain.tonconnect.toHex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.math.BigInteger
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectTransferBuilderTest {

    private val walletPublicKey = Vectors.WALLET_PUBLIC_KEY.hexToBytes()
    private val builder = TonConnectTransferBuilder(walletPublicKey, nowEpochSeconds = { Vectors.NOW })
    private val destination = TonAddress.parseRaw(Vectors.DESTINATION_RAW)

    /** The two-message transaction the iOS vectors were generated from, run through the validator like production. */
    private val vectorTransaction: TonConnectValidatedTransaction by lazy {
        val payload = TonConnectSendTransactionPayload(
            validUntil = Vectors.VALID_UNTIL,
            network = TonConnectNetworkId.MAINNET,
            from = null,
            messages = listOf(
                TonConnectSendTransactionPayload.Message(Vectors.DESTINATION_BOUNCEABLE, "100000000", payload = Vectors.COMMENT_PAYLOAD_BOC),
                TonConnectSendTransactionPayload.Message(Vectors.DESTINATION_NON_BOUNCEABLE, "1"),
            ),
        )
        TonConnectSendTransactionValidator(nowEpochSeconds = { Vectors.NOW })
            .validate(payload, TonConnectWalletAccount(builder.address, TonConnectNetworkId.MAINNET))
    }

    private fun rootOf(bocBase64: String): Cell = BagOfCells(Base64.getDecoder().decode(bocBase64)).roots.single()

    @Test
    fun `GIVEN BlockchainSdk test key WHEN deriving v4R2 address THEN it matches WalletCore and TonSwift`() {
        assertThat(builder.address.toRaw()).isEqualTo(Vectors.WALLET_ADDRESS_RAW)
        assertThat(builder.address.toFriendly(bounceable = false)).isEqualTo(Vectors.WALLET_ADDRESS_NON_BOUNCEABLE)
    }

    @Test
    fun `GIVEN wallet WHEN serialising StateInit THEN it equals the iOS module output and hashes to the address`() {
        val boc = builder.stateInitBoc()

        // ton-kotlin orders cells and omits the CRC differently from TonSwift; the cell trees are identical.
        assertThat(TonCells.hash(rootOf(boc)).toHex()).isEqualTo(TonCells.hash(rootOf(Vectors.WALLET_STATE_INIT_BOC)).toHex())
        assertThat(TonCells.hash(rootOf(boc)).toHex()).isEqualTo(builder.address.hash.toHex())
        assertThat(TonConnectTransferBuilder.isForwardableStateInit(rootOf(boc))).isTrue()
    }

    @Test
    fun `GIVEN wrong public key length WHEN constructing builder THEN InternalFailure is thrown`() {
        assertThrows<TonConnectException.InternalFailure> { TonConnectTransferBuilder(ByteArray(33)) }
    }

    @Test
    fun `GIVEN vector transaction WHEN preparing with seqno 7 THEN signing hash matches the iOS module`() {
        // Act
        val prepared = builder.prepare(vectorTransaction, seqno = 7)

        // Assert
        assertThat(prepared.hashToSign.toHex()).isEqualTo(Vectors.SIGNING_MESSAGE_HASH_SEQNO7)
        assertThat(prepared.expiresAt).isEqualTo(Vectors.VALID_UNTIL)
        val slice = prepared.signingMessage.beginParse()
        assertThat(slice.loadUInt(32).toLong()).isEqualTo(698_983_191L)
        assertThat(slice.loadUInt(32).toLong()).isEqualTo(Vectors.VALID_UNTIL)
        assertThat(slice.loadUInt(32).toLong()).isEqualTo(7L)
        assertThat(slice.loadUInt(8).toInt()).isEqualTo(0)
        assertThat(slice.loadUInt(8).toInt()).isEqualTo(3)
        assertThat(prepared.signingMessage.refs).hasSize(2)
    }

    @Test
    fun `GIVEN fixed signature WHEN assembling external messages THEN BoCs equal the iOS module for seqno 7 and 0`() {
        // Arrange
        val signature = ByteArray(64) { 0x44 }

        // Act
        val deployed = builder.assemble(builder.prepare(vectorTransaction, seqno = 7), signature)
        val undeployed = builder.assemble(builder.prepare(vectorTransaction, seqno = 0), signature)

        // Assert (cell-tree equality: serialisation order differs between ton-kotlin and TonSwift)
        assertThat(TonCells.hash(rootOf(deployed)).toHex()).isEqualTo(TonCells.hash(rootOf(Vectors.EXTERNAL_BOC_SEQNO7)).toHex())
        assertThat(TonCells.hash(rootOf(undeployed)).toHex()).isEqualTo(TonCells.hash(rootOf(Vectors.EXTERNAL_BOC_SEQNO0)).toHex())
        assertThat(builder.prepare(vectorTransaction, seqno = 0).hashToSign.toHex()).isEqualTo(Vectors.SIGNING_MESSAGE_HASH_SEQNO0)
    }

    @Test
    fun `GIVEN valid_until WHEN computing expiration THEN dApp deadline is honoured but capped`() {
        val cap = Vectors.NOW + TonConnectTransferBuilder.DEFAULT_TIMEOUT_SECONDS

        assertThat(builder.prepare(vectorTransaction.copy(validUntil = null), 1).expiresAt).isEqualTo(cap)
        assertThat(builder.prepare(vectorTransaction.copy(validUntil = cap - 100), 1).expiresAt).isEqualTo(cap - 100)
        assertThat(builder.prepare(vectorTransaction.copy(validUntil = cap + 100_000), 1).expiresAt).isEqualTo(cap)
    }

    @Test
    fun `GIVEN real signer WHEN signing THEN body starts with a verifiable signature over the signing message hash`() = runTest {
        // Arrange
        val signer = FakeTonConnectSigner()
        val ownBuilder = TonConnectTransferBuilder(signer.publicKey, nowEpochSeconds = { Vectors.NOW })

        // Act
        val boc = ownBuilder.sign(vectorTransaction, seqno = 3, signer)

        // Assert
        val external = rootOf(boc).beginParse()
        assertThat(external.loadBit()).isTrue() // ext_in_msg_info$10
        assertThat(external.loadBit()).isFalse()
        assertThat(external.loadBit()).isFalse() // src: addr_none
        assertThat(external.loadBit()).isFalse()
        val dest = external.loadBits(267)
        assertThat(dest).isEqualTo(CellBuilder.beginCell().also { ownBuilder.address.storeTo(it) }.endCell().bits)
        assertThat(external.loadUInt(4).toInt()).isEqualTo(0) // import_fee
        assertThat(external.loadBit()).isFalse() // no StateInit for a deployed wallet
        assertThat(external.loadBit()).isFalse() // body inline
        val signature = external.loadBits(512).toByteArray()
        val signingMessageHash = TonCells.hash(CellBuilder.beginCell().storeBits(external.loadBits(external.remainingBits)).storeRefs(rootOf(boc).refs).endCell())
        assertThat(signer.signedDigests.single().toHex()).isEqualTo(signingMessageHash.toHex())
        assertThat(signer.verify(signature, signingMessageHash)).isTrue()
    }

    @Test
    fun `GIVEN message with stateInit and seqno 0 WHEN assembling THEN wallet StateInit is attached and message StateInit forwarded`() {
        // Arrange
        val destinationStateInit = CellBuilder.beginCell()
            .storeBit(false).storeBit(false)
            .storeBit(true).storeRef(CellBuilder.beginCell().storeUInt(1, 8).endCell())
            .storeBit(true).storeRef(CellBuilder.beginCell().endCell())
            .storeBit(false)
            .endCell()
        val transaction = TonConnectValidatedTransaction(
            validUntil = null,
            messages = listOf(TonConnectValidatedTransaction.Message(destination, bounce = false, BigInteger.valueOf(5), null, destinationStateInit)),
        )

        // Act
        val boc = builder.assemble(builder.prepare(transaction, seqno = 0), ByteArray(64))

        // Assert
        val external = rootOf(boc).beginParse()
        external.loadBits(2 + 2 + 267 + 4)
        assertThat(external.loadBit()).isTrue() // StateInit present
        assertThat(external.loadBit()).isFalse() // inline
        val walletInit = CellBuilder.beginCell().storeBits(external.loadBits(5)).storeRefs(listOf(external.loadRef(), external.loadRef())).endCell()
        assertThat(TonCells.hash(walletInit).toHex()).isEqualTo(builder.address.hash.toHex())
        assertThat(external.loadBit()).isFalse() // body inline: one message ref still fits next to the two StateInit refs
        external.loadBits(512 + 32 + 32 + 32 + 8 + 8)
        val message = external.loadRef().beginParse()
        message.loadBits(4 + 2 + 267) // int_msg_info flags, src, dest
        message.loadUInt(4) // coins len 1
        message.loadBits(8) // amount 5
        message.loadBit() // extra currencies
        message.loadBits(4 + 4 + 64 + 32) // fees, lt, at
        assertThat(message.loadBit()).isTrue() // init present
        assertThat(message.loadBit()).isFalse() // inline
        val forwarded = CellBuilder.beginCell().storeBits(message.loadBits(5)).storeRefs(listOf(message.loadRef(), message.loadRef())).endCell()
        assertThat(forwarded).isEqualTo(destinationStateInit)
    }

    @Test
    fun `GIVEN malformed signature or too many messages WHEN building THEN exceptions are thrown`() {
        val prepared = builder.prepare(vectorTransaction, seqno = 1)
        val five = List(5) { vectorTransaction.messages.first() }

        assertThrows<TonConnectException.InternalFailure> { builder.assemble(prepared, ByteArray(63)) }
        assertThrows<TonConnectException.BadRequest> { builder.prepare(vectorTransaction.copy(messages = five), seqno = 1) }
        assertThrows<TonConnectException.InternalFailure> { builder.prepare(vectorTransaction, seqno = -1) }
    }
}
