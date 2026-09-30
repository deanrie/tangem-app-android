package com.tangem.data.walletconnect.network.hedera

import com.hedera.hashgraph.sdk.AccountId
import com.hedera.hashgraph.sdk.Hbar
import com.hedera.hashgraph.sdk.PrivateKey
import com.hedera.hashgraph.sdk.TokenId
import com.hedera.hashgraph.sdk.Transaction
import com.hedera.hashgraph.sdk.TransactionId
import com.hedera.hashgraph.sdk.TransferTransaction
import com.hedera.hashgraph.sdk.proto.AccountID
import com.hedera.hashgraph.sdk.proto.SignatureMap
import com.hedera.hashgraph.sdk.proto.SignedTransaction
import com.hedera.hashgraph.sdk.proto.TransactionBody
import com.hedera.hashgraph.sdk.proto.TransactionList
import com.hedera.hashgraph.sdk.proto.Transaction as ProtoTransaction
import com.tangem.blockchain.blockchains.ethereum.EthereumUtils.toKeccak
import com.tangem.domain.walletconnect.model.WcHederaTransactionSummary
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Vectors are built with the same Hedera SDK the wallet uses (same scenario as the iOS suite): 7.2 ℏ from
 * 0.0.3573746 to 0.0.1654 with a memo, addressed to nodes 0.0.3 and 0.0.6, valid start 1708443033.758181256.
 */
internal class WcHederaTransactionParserTest {

    // region TransactionList

    @Test
    fun `decodes transaction list into summary`() {
        val decoded = WcHederaTransactionParser.decodeTransactionList(transferListBytes())

        assertEquals(2, decoded.chunks.size)
        assertEquals(
            WcHederaTransactionSummary(
                transactionType = "CRYPTOTRANSFER",
                payerAccountId = PAYER,
                transactionId = "$PAYER@1708443033.758181256",
                nodeAccountIds = listOf("0.0.3", "0.0.6"),
                memo = "wc test",
                maxFeeTinybars = 100_000_000,
                hbarTransfers = listOf(
                    WcHederaTransactionSummary.HbarTransfer(accountId = RECIPIENT, tinybars = 720_000_000),
                    WcHederaTransactionSummary.HbarTransfer(accountId = PAYER, tinybars = -720_000_000),
                ),
                tokenTransfers = listOf(
                    WcHederaTransactionSummary.TokenTransfer(tokenId = TOKEN, accountId = RECIPIENT, amount = 5),
                    WcHederaTransactionSummary.TokenTransfer(tokenId = TOKEN, accountId = PAYER, amount = -5),
                ),
            ),
            decoded.summary,
        )
    }

    @Test
    fun `decoded chunks sign the same bodies as the original transaction`() {
        val original = transferTransaction()
        val decoded = WcHederaTransactionParser.decodeTransactionList(original.toBytes())

        val originalBodies = original.innerSignedTransactions.map { it.bodyBytes.toByteArray() }
        assertEquals(originalBodies.size, decoded.chunks.size)
        originalBodies.zip(decoded.chunks).forEach { (expected, chunk) -> assertArrayEquals(expected, chunk.bodyBytes) }
    }

    @Test
    fun `a single transaction body decodes to the same summary`() {
        val listSummary = WcHederaTransactionParser.decodeTransactionList(transferListBytes()).summary
        val body = transferTransaction().innerSignedTransactions.first().bodyBytes.toByteArray()

        val bodySummary = WcHederaTransactionParser.summarize(WcHederaTransactionParser.decodeTransactionBody(body))

        assertEquals(listSummary.copy(nodeAccountIds = listOf("0.0.3")), bodySummary)
    }

    @Test
    fun `rejects chunks that differ in more than the node account id`() {
        val a = SignedTransaction.parseFrom(
            TransactionList.parseFrom(transferListBytes()).getTransactionList(0).signedTransactionBytes,
        )
        val otherBody = TransactionBody.parseFrom(a.bodyBytes).toBuilder()
            .setNodeAccountID(AccountID.newBuilder().setAccountNum(6))
            .setMemo("something else")
            .build()
        val b = a.toBuilder().setBodyBytes(otherBody.toByteString()).build()
        val list = TransactionList.newBuilder()
            .addTransactionList(ProtoTransaction.newBuilder().setSignedTransactionBytes(a.toByteString()))
            .addTransactionList(ProtoTransaction.newBuilder().setSignedTransactionBytes(b.toByteString()))
            .build()

        val error = assertThrows(WcHederaTransactionParser.ParseError.InvalidTransaction::class.java) {
            WcHederaTransactionParser.decodeTransactionList(list.toByteArray())
        }
        assertTrue(error.message.orEmpty().contains("differ"))
    }

    @Test
    fun `rejects bytes that are not a transaction list`() {
        assertThrows(WcHederaTransactionParser.ParseError.InvalidTransaction::class.java) {
            WcHederaTransactionParser.decodeTransactionList(byteArrayOf(1, 2, 3))
        }
        assertThrows(WcHederaTransactionParser.ParseError.InvalidTransaction::class.java) {
            WcHederaTransactionParser.decodeTransactionList(TransactionList.getDefaultInstance().toByteArray())
        }
        assertThrows(WcHederaTransactionParser.ParseError.InvalidTransaction::class.java) {
            WcHederaTransactionParser.decodeTransactionBody("not protobuf at all".toByteArray())
        }
        assertThrows(WcHederaTransactionParser.ParseError.InvalidBase64::class.java) {
            WcHederaTransactionParser.decodeTransactionBytes("***", "transactionList")
        }
        assertThrows(WcHederaTransactionParser.ParseError.TooLarge::class.java) {
            val oversized = ByteArray(WcHederaTransactionParser.MAX_TRANSACTION_BYTE_COUNT + 1).toByteString().base64()
            WcHederaTransactionParser.decodeTransactionBytes(oversized, "transactionList")
        }
    }

    // endregion

    // region signerAccountId

    @Test
    fun `parses signerAccountId and checks it against the request chain`() {
        val signer = WcHederaTransactionParser.parseSignerAccountId("hedera:mainnet:0.0.3573746-vfmkw")
        assertEquals(WcHederaTransactionParser.SignerAccountId("mainnet", PAYER), signer)

        WcHederaTransactionParser.validateNetwork(signer, "mainnet")
        assertThrows(WcHederaTransactionParser.ParseError.NetworkMismatch::class.java) {
            WcHederaTransactionParser.validateNetwork(signer, "testnet")
        }
        assertThrows(WcHederaTransactionParser.ParseError.NetworkMismatch::class.java) {
            WcHederaTransactionParser.validateNetwork(signer, null)
        }

        listOf("0.0.1", "hedera:0.0.1", "eip155:1:0.0.1", "hedera:mainnet:0.0", "hedera:mainnet:a.b.c", "hedera::0.0.1")
            .forEach { raw ->
                assertThrows(
                    WcHederaTransactionParser.ParseError.InvalidSignerAccountId::class.java,
                    { WcHederaTransactionParser.parseSignerAccountId(raw) },
                    raw,
                )
            }
    }

    // endregion

    // region Signing

    @Test
    fun `hash to sign and signature map follow the account key curve`() {
        val body = "body".toByteArray()

        val ed = WcHederaTransactionParser.hederaPublicKey(ByteArray(32) { 7 })
        assertTrue(ed.isED25519)
        assertArrayEquals(body, WcHederaTransactionParser.hashToSign(body, ed))
        val edMap = SignatureMap.parseFrom(WcHederaTransactionParser.signatureMapBytes(ByteArray(64) { 1 }, ed))
        assertEquals(1, edMap.sigPairCount)
        assertTrue(edMap.getSigPair(0).hasEd25519())
        assertEquals(32, edMap.getSigPair(0).pubKeyPrefix.size())

        val ecdsa = PrivateKey.generateECDSA().publicKey
        val ecdsaKey = WcHederaTransactionParser.hederaPublicKey(ecdsa.toBytesRaw())
        assertFalse(ecdsaKey.isED25519)
        assertArrayEquals(body.toKeccak(), WcHederaTransactionParser.hashToSign(body, ecdsaKey))
        val ecMap = SignatureMap.parseFrom(WcHederaTransactionParser.signatureMapBytes(ByteArray(64) { 1 }, ecdsaKey))
        assertTrue(ecMap.getSigPair(0).hasECDSASecp256K1())
        assertEquals(33, ecMap.getSigPair(0).pubKeyPrefix.size(), "compressed secp256k1 key as prefix")

        assertThrows(WcHederaTransactionParser.ParseError.UnsupportedKey::class.java) {
            WcHederaTransactionParser.hederaPublicKey(ByteArray(31))
        }
    }

    @Test
    fun `signed message bytes use the reference prefix and UTF-16 length`() {
        assertArrayEquals(
            "\u0019Hedera Signed Message:\n5hello".toByteArray(),
            WcHederaTransactionParser.messageBytes("hello"),
        )
        // `@hashgraph/hedera-wallet-connect` counts JavaScript string length (UTF-16 units): 6, not 12 bytes.
        assertArrayEquals(
            "\u0019Hedera Signed Message:\n6Привет".toByteArray(),
            WcHederaTransactionParser.messageBytes("Привет"),
        )
        assertArrayEquals("\u0019Hedera Signed Message:\n0".toByteArray(), WcHederaTransactionParser.messageBytes(""))
    }

    @Test
    fun `signed chunks are single-node lists the SDK accepts with our signature attached`() {
        val key = PrivateKey.generateED25519()
        val decoded = WcHederaTransactionParser.decodeTransactionList(transferListBytes())
        val publicKey = WcHederaTransactionParser.hederaPublicKey(key.publicKey.toBytesRaw())

        decoded.chunks.forEachIndexed { index, chunk ->
            val signature = key.sign(WcHederaTransactionParser.hashToSign(chunk.bodyBytes, publicKey))
            val sdkTransaction = Transaction.fromBytes(
                WcHederaTransactionParser.signedChunkBytes(chunk, publicKey, signature),
            )

            val expectedNode = AccountId.fromString(decoded.summary.nodeAccountIds[index])
            assertEquals(setOf(expectedNode), sdkTransaction.signatures.keys)
            assertArrayEquals(signature, sdkTransaction.signatures.getValue(expectedNode).getValue(key.publicKey))
            assertArrayEquals(chunk.bodyBytes, sdkTransaction.innerSignedTransactions.single().bodyBytes.toByteArray())
        }
    }

    @Test
    fun `consensus nodes are known for both networks`() {
        val testnet = requireNotNull(WcHederaNetwork.consensusNodeAccountIds("testnet"))
        assertTrue(testnet.isNotEmpty())
        assertTrue(testnet.all { it.startsWith("0.0.") })
        assertTrue(requireNotNull(WcHederaNetwork.consensusNodeAccountIds("mainnet")).contains("0.0.3"))
        assertNull(WcHederaNetwork.consensusNodeAccountIds("previewnet"))
    }

    // endregion

    private fun transferTransaction(): Transaction<*> = TransferTransaction()
        .addHbarTransfer(AccountId.fromString(PAYER), Hbar.fromTinybars(-720_000_000))
        .addHbarTransfer(AccountId.fromString(RECIPIENT), Hbar.fromTinybars(720_000_000))
        .addTokenTransfer(TokenId.fromString(TOKEN), AccountId.fromString(PAYER), -5)
        .addTokenTransfer(TokenId.fromString(TOKEN), AccountId.fromString(RECIPIENT), 5)
        .setTransactionMemo("wc test")
        .setMaxTransactionFee(Hbar.fromTinybars(100_000_000))
        .setTransactionId(
            TransactionId.withValidStart(
                AccountId.fromString(PAYER),
                Instant.ofEpochSecond(1_708_443_033, 758_181_256),
            ),
        )
        .setNodeAccountIds(listOf(AccountId.fromString("0.0.3"), AccountId.fromString("0.0.6")))
        .freeze()

    private fun transferListBytes(): ByteArray = transferTransaction().toBytes()

    private companion object {
        const val PAYER = "0.0.3573746"
        const val RECIPIENT = "0.0.1654"
        const val TOKEN = "0.0.456858"
    }
}
