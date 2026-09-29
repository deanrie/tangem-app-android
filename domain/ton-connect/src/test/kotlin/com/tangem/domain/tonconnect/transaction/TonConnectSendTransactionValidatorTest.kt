package com.tangem.domain.tonconnect.transaction

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.TonConnectSendTransactionPayload
import com.tangem.domain.tonconnect.model.TonConnectSendTransactionPayload.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.ton.cell.CellBuilder
import java.math.BigInteger
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectSendTransactionValidatorTest {

    private val walletAddress = TonAddress.parseRaw(Vectors.WALLET_ADDRESS_RAW)
    private val destination = TonAddress.parseRaw(Vectors.DESTINATION_RAW)
    private val validator = TonConnectSendTransactionValidator(nowEpochSeconds = { Vectors.NOW })
    private val account = TonConnectWalletAccount(walletAddress, TonConnectNetworkId.MAINNET)

    private fun payload(
        validUntil: Long? = Vectors.VALID_UNTIL,
        network: TonConnectNetworkId? = TonConnectNetworkId.MAINNET,
        from: String? = null,
        messages: List<Message>? = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "100000000")),
        hasItems: Boolean = false,
    ) = TonConnectSendTransactionPayload(validUntil, network, from, messages, hasItems)

    private fun badRequest(block: () -> Unit): String = assertThrows<TonConnectException.BadRequest>(block).protocolMessage

    @Test
    fun `GIVEN spec example JSON WHEN decoding THEN all fields are read`() {
        val json = """{"valid_until":1764424242,"network":"-239","from":"Ef8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAU","messages":[{"address":"Ef8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAU","amount":"100000000"}]}"""

        val decoded = TonConnectSendTransactionPayload.decode(json)

        assertThat(decoded.validUntil).isEqualTo(1_764_424_242L)
        assertThat(decoded.network).isEqualTo(TonConnectNetworkId.MAINNET)
        assertThat(decoded.from).isEqualTo("Ef8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAU")
        assertThat(decoded.messages).hasSize(1)
        assertThat(decoded.messages?.single()?.amount).isEqualTo("100000000")
        assertThat(decoded.hasItems).isFalse()
    }

    @Test
    fun `GIVEN optional fields and numeric strings WHEN decoding THEN they are accepted`() {
        val json = """{"valid_until":"1764424242","network":-239,"messages":[{"address":"a","amount":1,"payload":"p","stateInit":"s","extra_currency":{"239":"5"}}],"items":[{"type":"ton"}]}"""

        val decoded = TonConnectSendTransactionPayload.decode(json)

        assertThat(decoded.validUntil).isEqualTo(1_764_424_242L)
        assertThat(decoded.network).isEqualTo(TonConnectNetworkId.MAINNET)
        assertThat(decoded.messages?.single()).isEqualTo(Message("a", "1", "p", "s", mapOf("239" to "5")))
        assertThat(decoded.hasItems).isTrue()
    }

    @Test
    fun `GIVEN invalid JSON WHEN decoding THEN BadRequest is thrown`() {
        assertThat(badRequest { TonConnectSendTransactionPayload.decode("[]") }).isEqualTo("transaction payload is not valid JSON")
        assertThat(badRequest { TonConnectSendTransactionPayload.decode("""{"messages":"x"}""") }).isEqualTo("messages must be an array")
    }

    @Test
    fun `GIVEN valid payload WHEN validating THEN bounce flags amounts and payload cells are extracted`() {
        // Arrange
        val payloadCell = TonConnectBoc.singleRootCell(Vectors.COMMENT_PAYLOAD_BOC, "p")
        val payload = payload(
            from = walletAddress.toFriendly(bounceable = false),
            messages = listOf(
                Message(Vectors.DESTINATION_BOUNCEABLE, "100000000", payload = Vectors.COMMENT_PAYLOAD_BOC),
                Message(Vectors.DESTINATION_NON_BOUNCEABLE, "1"),
            ),
        )

        // Act
        val transaction = validator.validate(payload, account)

        // Assert
        assertThat(transaction.validUntil).isEqualTo(Vectors.VALID_UNTIL)
        assertThat(transaction.messages).hasSize(2)
        assertThat(transaction.messages[0].destination).isEqualTo(destination)
        assertThat(transaction.messages[0].bounce).isTrue()
        assertThat(transaction.messages[0].amount).isEqualTo(BigInteger.valueOf(100_000_000))
        assertThat(transaction.messages[0].payload).isEqualTo(payloadCell)
        assertThat(transaction.messages[0].stateInit).isNull()
        assertThat(transaction.messages[1].bounce).isFalse()
        assertThat(transaction.totalAmount).isEqualTo(BigInteger.valueOf(100_000_001))
    }

    @Test
    fun `GIVEN from in raw or friendly forms WHEN validating THEN all are accepted`() {
        validator.validate(payload(from = Vectors.WALLET_ADDRESS_RAW), account)
        validator.validate(payload(from = walletAddress.toFriendly(bounceable = false)), account)
        validator.validate(payload(from = walletAddress.toFriendly(bounceable = true)), account)
        assertThat(validator.validate(payload(validUntil = null, network = null, from = null), account).validUntil).isNull()
    }

    @Test
    fun `GIVEN url-safe base64 stateInit WHEN validating THEN cell is accepted`() {
        val stateInit = CellBuilder.beginCell().storeBits(false, false, false, false, false).endCell()
        val urlSafe = TonCells.toBocBase64(stateInit).replace('+', '-').replace('/', '_').trimEnd('=')

        val transaction = validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", stateInit = urlSafe))), account)

        assertThat(transaction.messages.single().stateInit).isEqualTo(stateInit)
    }

    @Test
    fun `GIVEN items or missing messages WHEN validating THEN BadRequest explains the shape`() {
        assertThat(badRequest { validator.validate(payload(messages = null, hasItems = true), account) })
            .isEqualTo("structured items are not supported by this wallet; send raw messages")
        assertThat(badRequest { validator.validate(payload(hasItems = true), account) })
            .isEqualTo("payload must contain either messages or items, not both")
        assertThat(badRequest { validator.validate(payload(messages = null), account) }).isEqualTo("payload must contain messages")
        assertThat(badRequest { validator.validate(payload(messages = emptyList()), account) }).isEqualTo("messages must not be empty")
    }

    @Test
    fun `GIVEN expired or non-positive valid_until WHEN validating THEN BadRequest is thrown`() {
        assertThat(badRequest { validator.validate(payload(validUntil = Vectors.NOW), account) })
            .isEqualTo("request expired: valid_until 1764424242 is in the past")
        assertThat(badRequest { validator.validate(payload(validUntil = 0), account) })
            .isEqualTo("valid_until must be a positive unix timestamp")
    }

    @Test
    fun `GIVEN network mismatch WHEN validating THEN exact string comparison rejects`() {
        assertThat(badRequest { validator.validate(payload(network = TonConnectNetworkId.TESTNET), account) })
            .isEqualTo("network -3 does not match the connected account network -239")
        assertThat(badRequest { validator.validate(payload(network = TonConnectNetworkId("-0239")), account) })
            .isEqualTo("network -0239 does not match the connected account network -239")
    }

    @Test
    fun `GIVEN foreign or malformed from WHEN validating THEN BadRequest is thrown`() {
        assertThat(badRequest { validator.validate(payload(from = Vectors.DESTINATION_BOUNCEABLE), account) })
            .isEqualTo("from does not match the connected account")
        assertThat(badRequest { validator.validate(payload(from = "not-an-address"), account) })
            .isEqualTo("from is not a valid TON address")
    }

    @Test
    fun `GIVEN five messages WHEN validating THEN limit of four is enforced`() {
        val five = List(5) { Message(Vectors.DESTINATION_BOUNCEABLE, "1") }

        assertThat(badRequest { validator.validate(payload(messages = five), account) })
            .isEqualTo("too many messages: 5, the wallet supports at most 4")
    }

    @Test
    fun `GIVEN raw or corrupted destination WHEN validating THEN BadRequest is thrown`() {
        val corrupted = Vectors.DESTINATION_BOUNCEABLE.dropLast(1) + if (Vectors.DESTINATION_BOUNCEABLE.last() == 'A') "B" else "A"

        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_RAW, "1"))), account) })
            .isEqualTo("messages[0].address must be in user-friendly format, raw addresses are not allowed")
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(corrupted, "1"))), account) })
            .isEqualTo("messages[0].address is not a valid TON address")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "-1", "1.5", "0x10", "1e9", " 1", "+1"])
    fun `GIVEN non-decimal amount WHEN validating THEN BadRequest is thrown`(amount: String) {
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, amount))), account) })
            .isEqualTo("messages[0].amount must be a non-negative decimal string of nanocoins")
    }

    @Test
    fun `GIVEN amount above 120 bits WHEN validating THEN BadRequest is thrown`() {
        val tooLarge = BigInteger.ONE.shiftLeft(120).toString()
        val maximum = BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE).toString()

        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, tooLarge))), account) })
            .isEqualTo("messages[0].amount exceeds the maximum representable value")
        validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, maximum))), account)
    }

    @Test
    fun `GIVEN extra currency WHEN validating THEN BadRequest is thrown unless map is empty`() {
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", extraCurrency = mapOf("239" to "1")))), account) })
            .isEqualTo("messages[0].extra_currency is not supported by this wallet")
        validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", extraCurrency = emptyMap()))), account)
    }

    @Test
    fun `GIVEN malformed payload and stateInit BoCs WHEN validating THEN BadRequest names the field`() {
        val notStateInit = TonCells.toBocBase64(CellBuilder.beginCell().storeBit(true).endCell())
        val oversized = Base64.getEncoder().encodeToString(ByteArray(TonConnectBoc.MAX_SERIALIZED_BYTE_COUNT + 1) { 0xB5.toByte() })

        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", payload = "%%%"))), account) })
            .isEqualTo("messages[0].payload is not valid base64")
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6))))), account) })
            .isEqualTo("messages[0].payload is not a valid BoC: unsupported magic")
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1"), Message(Vectors.DESTINATION_BOUNCEABLE, "1", stateInit = notStateInit))), account) })
            .isEqualTo("messages[1].stateInit is not a valid StateInit cell")
        assertThat(badRequest { validator.validate(payload(messages = listOf(Message(Vectors.DESTINATION_BOUNCEABLE, "1", payload = oversized))), account) })
            .isEqualTo("messages[0].payload exceeds ${TonConnectBoc.MAX_SERIALIZED_BYTE_COUNT} bytes")
    }
}
