package com.tangem.domain.tonconnect.crypto

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.BrokenTonConnectSigner
import com.tangem.domain.tonconnect.FakeTonConnectSigner
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.hexToBytes
import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.TonConnectSignDataPayload
import com.tangem.domain.tonconnect.toHex
import com.tangem.domain.tonconnect.transaction.TonConnectBoc
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.ton.cell.CellBuilder
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectSignatureMessagesTest {

    private val address = TonAddress.parseRaw(Vectors.WALLET_ADDRESS_RAW)
    private val domain = Vectors.APP_DOMAIN
    private val timestamp = Vectors.NOW

    @Test
    fun `GIVEN spec inputs WHEN building ton_proof message THEN layout follows the spec`() {
        // Act
        val message = TonConnectProofMessage.message(address, domain, timestamp, "nonce")

        // Assert
        val expected = "ton-proof-item-v2/".toByteArray() +
            byteArrayOf(0, 0, 0, 0) + address.hash + // workchain int32 BE ++ hash
            byteArrayOf(0x15, 0, 0, 0) + domain.toByteArray() + // 21 = len(domain), uint32 LE
            "32fa2a6900000000".hexToBytes() + // 1764424242, uint64 LE
            "nonce".toByteArray()
        assertThat(message.toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun `GIVEN masterchain address WHEN building ton_proof message THEN workchain is negative big-endian`() {
        val masterchain = TonAddress(-1, ByteArray(32) { 0x11 })

        val message = TonConnectProofMessage.message(masterchain, "a.b", 0, "")

        assertThat(message.copyOfRange(18, 22).toHex()).isEqualTo("ffffffff")
    }

    @Test
    fun `GIVEN iOS vector inputs WHEN computing ton_proof digest THEN it matches the iOS module`() {
        assertThat(TonConnectProofMessage.digest(address, domain, timestamp, "nonce").toHex())
            .isEqualTo(Vectors.PROOF_DIGEST_NONCE)
    }

    @Test
    fun `GIVEN signer WHEN making proof THEN digest is signed and reply is packaged`() = runTest {
        // Arrange
        val signer = FakeTonConnectSigner()

        // Act
        val proof = TonConnectProofMessage.makeProof(address, domain, "nonce", timestamp, signer)

        // Assert
        val digest = TonConnectProofMessage.digest(address, domain, timestamp, "nonce")
        assertThat(signer.signedDigests.single().toHex()).isEqualTo(digest.toHex())
        assertThat(signer.verify(proof.signature, digest)).isTrue()
        val json = proof.toJson()
        assertThat(json["timestamp"].toString()).isEqualTo("\"1764424242\"")
        assertThat(json["payload"].toString()).isEqualTo("\"nonce\"")
        assertThat(json["domain"].toString()).isEqualTo("""{"lengthBytes":21,"value":"ton-connect.github.io"}""")
    }

    @Test
    fun `GIVEN broken signer WHEN making proof THEN InternalFailure is thrown`() = runTest {
        assertThrows<TonConnectException.InternalFailure> {
            TonConnectProofMessage.makeProof(address, domain, "n", 1, BrokenTonConnectSigner)
        }
    }

    @Test
    fun `GIVEN text payload WHEN building signData message THEN layout is big-endian per reference verifier`() {
        // Act
        val message = TonConnectSignDataMessage.flatMessage(
            address = address,
            appDomain = domain,
            timestamp = timestamp,
            content = TonConnectSignDataPayload.Content.Text("Hi"),
        )

        // Assert
        val expected = "ffff".hexToBytes() + "ton-connect/sign-data/".toByteArray() +
            byteArrayOf(0, 0, 0, 0) + address.hash +
            byteArrayOf(0, 0, 0, 0x15) + domain.toByteArray() + // uint32 BE
            "00000000692afa32".hexToBytes() + // uint64 BE
            "txt".toByteArray() + byteArrayOf(0, 0, 0, 2) + "Hi".toByteArray()
        assertThat(message.toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun `GIVEN text and binary payloads WHEN computing signData digests THEN they match the iOS module`() {
        val text = TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Text("Hi"), TonConnectNetworkId.MAINNET, null)
        val binary = TonConnectSignDataPayload(
            content = TonConnectSignDataPayload.Content.Binary("deadbeef".hexToBytes()),
            network = TonConnectNetworkId.MAINNET,
            from = null,
        )

        assertThat(TonConnectSignDataMessage.digest(address, domain, timestamp, text).toHex())
            .isEqualTo(Vectors.SIGN_DATA_TEXT_HI_DIGEST)
        assertThat(TonConnectSignDataMessage.digest(address, domain, timestamp, binary).toHex())
            .isEqualTo(Vectors.SIGN_DATA_BINARY_DEADBEEF_DIGEST)
    }

    @Test
    fun `GIVEN domain WHEN encoding TEP-81 wire format THEN labels are reversed and NUL-terminated`() {
        assertThat(TonConnectSignDataMessage.dnsWireFormat("ton-connect.github.io"))
            .isEqualTo("io\u0000github\u0000ton-connect\u0000".toByteArray())
        assertThat(TonConnectSignDataMessage.dnsWireFormat("stonfi.com")).isEqualTo("com\u0000stonfi\u0000".toByteArray())
    }

    @Test
    fun `GIVEN reference strings WHEN computing crc32 THEN IEEE values are produced`() {
        assertThat(TonConnectSignDataMessage.crc32("123456789".toByteArray())).isEqualTo(0xCBF43926L)
        assertThat(TonConnectSignDataMessage.crc32(ByteArray(0))).isEqualTo(0L)
    }

    @Test
    fun `GIVEN cell payload WHEN building signData cell THEN structure follows the spec`() {
        // Arrange
        val payloadCell = CellBuilder.beginCell().storeUInt(42, 32).endCell()

        // Act
        val cell = TonConnectSignDataMessage.cellMessage(address, domain, timestamp, Vectors.SIGN_DATA_CELL_SCHEMA, payloadCell)

        // Assert
        val slice = cell.beginParse()
        assertThat(slice.loadUInt(32).toLong()).isEqualTo(TonConnectSignDataMessage.CELL_MAGIC)
        assertThat(slice.loadUInt(32).toLong()).isEqualTo(TonConnectSignDataMessage.crc32(Vectors.SIGN_DATA_CELL_SCHEMA.toByteArray()))
        assertThat(slice.loadUInt(64).toLong()).isEqualTo(timestamp)
        assertThat(slice.loadBits(267)).isEqualTo(CellBuilder.beginCell().also { address.storeTo(it) }.endCell().bits)
        assertThat(slice.loadRef().bits.toByteArray()).isEqualTo("io\u0000github\u0000ton-connect\u0000".toByteArray())
        assertThat(slice.loadRef()).isEqualTo(payloadCell)
        assertThat(slice.remainingBits).isEqualTo(0)
    }

    @Test
    fun `GIVEN iOS cell vector WHEN computing signData cell digest THEN it matches the iOS module`() {
        // Arrange
        val payload = TonConnectSignDataPayload(
            content = TonConnectSignDataPayload.Content.Cell(Vectors.SIGN_DATA_CELL_SCHEMA, Vectors.SIGN_DATA_CELL_PAYLOAD_BOC),
            network = null,
            from = null,
        )

        // Act
        val digest = TonConnectSignDataMessage.digest(address, domain, timestamp, payload)

        // Assert
        assertThat(digest.toHex()).isEqualTo(Vectors.SIGN_DATA_CELL_DIGEST)
        assertThat(TonConnectBoc.singleRootCell(Vectors.SIGN_DATA_CELL_PAYLOAD_BOC, "cell").bits.toByteArray().toHex())
            .isEqualTo("0000002a")
    }

    @Test
    fun `GIVEN malformed cell BoC WHEN computing digest THEN BadRequest is thrown`() {
        val payload = TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Cell("x", "not-a-boc"), null, null)

        assertThrows<TonConnectException.BadRequest> { TonConnectSignDataMessage.digest(address, domain, 0, payload) }
    }

    @Test
    fun `GIVEN signer WHEN signing text payload THEN result echoes payload and signature verifies`() = runTest {
        // Arrange
        val signer = FakeTonConnectSigner()
        val payload = TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Text("Confirm"), TonConnectNetworkId.MAINNET, "UQAAA")

        // Act
        val result = TonConnectSignDataMessage.sign(payload, address, domain, timestamp, signer)

        // Assert
        assertThat(result.address).isEqualTo(Vectors.WALLET_ADDRESS_RAW)
        assertThat(result.timestamp).isEqualTo(timestamp)
        assertThat(result.domain).isEqualTo(domain)
        assertThat(result.payload).isEqualTo(payload)
        val digest = TonConnectSignDataMessage.digest(address, domain, timestamp, payload)
        assertThat(signer.verify(result.signature, digest)).isTrue()
        val json = result.toJson().toString()
        assertThat(json).contains("\"signature\":\"${Base64.getEncoder().encodeToString(result.signature)}\"")
        assertThat(json).contains("\"payload\":{\"type\":\"text\",\"text\":\"Confirm\",\"network\":\"-239\",\"from\":\"UQAAA\"}")
    }
}
