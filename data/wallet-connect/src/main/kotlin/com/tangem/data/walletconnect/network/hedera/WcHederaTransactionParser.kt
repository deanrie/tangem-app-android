package com.tangem.data.walletconnect.network.hedera

import com.google.protobuf.ByteString
import com.hedera.hashgraph.sdk.PublicKey
import com.hedera.hashgraph.sdk.proto.AccountID
import com.hedera.hashgraph.sdk.proto.SignatureMap
import com.hedera.hashgraph.sdk.proto.SignedTransaction
import com.hedera.hashgraph.sdk.proto.TokenID
import com.hedera.hashgraph.sdk.proto.TransactionBody
import com.hedera.hashgraph.sdk.proto.TransactionList
import com.tangem.blockchain.blockchains.ethereum.EthereumUtils.toKeccak
import com.tangem.crypto.CryptoUtils
import com.tangem.domain.walletconnect.model.WcHederaTransactionSummary
import okio.ByteString.Companion.decodeBase64
import com.hedera.hashgraph.sdk.proto.Transaction as ProtoTransaction

/**
 * Pure, testable parts of the Hedera WalletConnect handlers (HIP-820): request field validation, decoding of what
 * gets signed into a [WcHederaTransactionSummary], the per-curve signing digest and the `SignatureMap` reply.
 */
internal object WcHederaTransactionParser {

    /** Upper bound on a `TransactionList` / `TransactionBody` accepted from a dApp (HAPI caps a chunk at 6 KiB). */
    const val MAX_TRANSACTION_BYTE_COUNT: Int = 256 * 1024

    private const val NAMESPACE = "hedera"
    private const val MESSAGE_PREFIX = "\u0019Hedera Signed Message:\n"
    private const val ED25519_KEY_SIZE = 32
    private const val SECP256K1_COMPRESSED_KEY_SIZE = 33
    private const val SECP256K1_UNCOMPRESSED_KEY_SIZE = 65

    sealed class ParseError(message: String) : Exception(message) {
        class InvalidSignerAccountId : ParseError("signerAccountId must be hedera:<network>:<shard>.<realm>.<num>")
        class NetworkMismatch(network: String) :
            ParseError("signerAccountId network $network does not match the request chain")
        class InvalidBase64(field: String) : ParseError("$field is not valid base64")
        class TooLarge(field: String) : ParseError("$field exceeds $MAX_TRANSACTION_BYTE_COUNT bytes")
        class InvalidTransaction(reason: String) : ParseError("Invalid Hedera transaction: $reason")
        class UnsupportedKey : ParseError("Unsupported Hedera account key")
    }

    /** `hedera:<network>:<shard>.<realm>.<num>[-checksum]` → its parts. */
    data class SignerAccountId(val network: String, val accountId: String)

    /**
     * A decoded `TransactionList`: one [Chunk] per consensus node, all carrying the same body except for the node
     * account id, plus the summary of that body.
     */
    class DecodedTransactionList(
        val chunks: List<Chunk>,
        val summary: WcHederaTransactionSummary,
    ) {
        /** One `proto.Transaction` of the list; [bodyBytes] is what the signature covers. */
        class Chunk(
            val bodyBytes: ByteArray,
            /** Signatures the dApp already attached (multi-sig flows); ours is appended. */
            val existingSignatures: SignatureMap,
        )
    }

    fun parseSignerAccountId(raw: String): SignerAccountId {
        val parts = raw.split(':')
        if (parts.size != 3 || !parts[0].equals(NAMESPACE, ignoreCase = true)) throw ParseError.InvalidSignerAccountId()
        val network = parts[1].lowercase()
        val accountId = parts[2].substringBefore('-')
        val idParts = accountId.split('.')
        val isValidId = idParts.size == 3 && idParts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        if (network.isEmpty() || !isValidId) throw ParseError.InvalidSignerAccountId()
        return SignerAccountId(network = network, accountId = accountId)
    }

    /**
     * The signer's network must be the chain the request came in on (`hedera:mainnet` / `hedera:testnet` — the
     * CAIP-2 reference is the network name). Whether the account itself is the wallet's is checked against the
     * session's accounts by the request converter.
     */
    fun validateNetwork(signer: SignerAccountId, requestChainReference: String?) {
        if (requestChainReference == null || !signer.network.equals(requestChainReference, ignoreCase = true)) {
            throw ParseError.NetworkMismatch(signer.network)
        }
    }

    fun decodeTransactionBytes(base64: String, field: String): ByteArray {
        val bytes = base64.decodeBase64()?.toByteArray()?.takeIf { it.isNotEmpty() }
            ?: throw ParseError.InvalidBase64(field)
        if (bytes.size > MAX_TRANSACTION_BYTE_COUNT) throw ParseError.TooLarge(field)
        return bytes
    }

    /**
     * Decodes a `TransactionList` (or a single `Transaction`) chunk by chunk and enforces HIP-820's rule that the
     * chunks differ only in `nodeAccountID`, so the first chunk's body describes all of them.
     *
     * The protobufs are walked by hand rather than through `Transaction.fromBytes`: the bundled SDK's version of that
     * check compares bodies by reflection and overflows the stack on any list with more than one node.
     */
    fun decodeTransactionList(bytes: ByteArray): DecodedTransactionList {
        val transactions = runCatching { TransactionList.parseFrom(bytes).transactionListList }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { listOf(ProtoTransaction.parseFrom(bytes)) }
                .getOrElse { throw ParseError.InvalidTransaction("not a TransactionList") }

        val signed = transactions.map { transaction ->
            if (transaction.signedTransactionBytes.isEmpty) {
                throw ParseError.InvalidTransaction("a chunk has no signedTransactionBytes")
            }
            runCatching { SignedTransaction.parseFrom(transaction.signedTransactionBytes) }
                .getOrElse { throw ParseError.InvalidTransaction("a chunk is not a SignedTransaction") }
        }
        val bodies = signed.map { decodeTransactionBody(it.bodyBytes.toByteArray()) }

        val distinctBodies = bodies.map { it.toBuilder().clearNodeAccountID().build() }.distinct()
        if (distinctBodies.size != 1) {
            throw ParseError.InvalidTransaction("chunks differ in more than the node account id")
        }
        val nodeAccountIds = bodies.map { body ->
            body.takeIf { it.hasNodeAccountID() }?.nodeAccountID?.format()
                ?: throw ParseError.InvalidTransaction("a chunk has no node account id")
        }
        if (nodeAccountIds.distinct().size != nodeAccountIds.size) {
            throw ParseError.InvalidTransaction("chunks repeat a node account id")
        }

        return DecodedTransactionList(
            chunks = signed.map { DecodedTransactionList.Chunk(it.bodyBytes.toByteArray(), it.sigMap) },
            summary = summarize(bodies.first()).copy(nodeAccountIds = nodeAccountIds),
        )
    }

    /**
     * A single-entry `TransactionList` for one chunk with [signature] appended to the dApp's signatures — the shape
     * the SDK submits. (Single-chunk lists are unaffected by the `fromBytes` issue above.)
     */
    fun signedChunkBytes(chunk: DecodedTransactionList.Chunk, publicKey: PublicKey, signature: ByteArray): ByteArray {
        val signedTransaction = SignedTransaction.newBuilder()
            .setBodyBytes(ByteString.copyFrom(chunk.bodyBytes))
            .setSigMap(chunk.existingSignatures.toBuilder().addSigPair(publicKey.toSignaturePairProtobuf(signature)))
            .build()
        val transaction = ProtoTransaction.newBuilder()
            .setSignedTransactionBytes(signedTransaction.toByteString())
            .build()
        return TransactionList.newBuilder().addTransactionList(transaction).build().toByteArray()
    }

    fun decodeTransactionBody(bytes: ByteArray): TransactionBody = runCatching { TransactionBody.parseFrom(bytes) }
        .getOrElse { throw ParseError.InvalidTransaction("not a TransactionBody") }

    fun summarize(body: TransactionBody): WcHederaTransactionSummary {
        val payer = body.takeIf { it.hasTransactionID() && it.transactionID.hasAccountID() }
            ?.transactionID?.accountID?.format()
        val transactionId = body.takeIf { it.hasTransactionID() }?.transactionID?.let { id ->
            val start = id.transactionValidStart
            "${payer.orEmpty()}@${start.seconds}.${start.nanos.toString().padStart(9, '0')}"
        }
        val transfer = body.takeIf { it.hasCryptoTransfer() }?.cryptoTransfer
        return WcHederaTransactionSummary(
            transactionType = body.dataCase.name,
            payerAccountId = payer,
            transactionId = transactionId,
            nodeAccountIds = listOfNotNull(body.takeIf { it.hasNodeAccountID() }?.nodeAccountID?.format()),
            memo = body.memo,
            maxFeeTinybars = body.transactionFee.takeIf { it > 0 },
            hbarTransfers = transfer?.transfers?.accountAmountsList.orEmpty().map { leg ->
                WcHederaTransactionSummary.HbarTransfer(accountId = leg.accountID.format(), tinybars = leg.amount)
            },
            tokenTransfers = transfer?.tokenTransfersList.orEmpty().flatMap { tokenLegs ->
                val tokenId = tokenLegs.token.format()
                tokenLegs.transfersList.map { leg ->
                    WcHederaTransactionSummary.TokenTransfer(
                        tokenId = tokenId,
                        accountId = leg.accountID.format(),
                        amount = leg.amount,
                    )
                }
            },
        )
    }

    /** The account key as the Hedera SDK sees it: 32 bytes → Ed25519, 33/65 bytes → ECDSA secp256k1. */
    fun hederaPublicKey(blockchainKey: ByteArray): PublicKey = runCatching {
        when (blockchainKey.size) {
            ED25519_KEY_SIZE -> PublicKey.fromBytesED25519(blockchainKey)
            SECP256K1_COMPRESSED_KEY_SIZE, SECP256K1_UNCOMPRESSED_KEY_SIZE -> PublicKey.fromBytesECDSA(blockchainKey)
            else -> throw ParseError.UnsupportedKey()
        }
    }.getOrElse { throw ParseError.UnsupportedKey() }

    /** Hedera signs body bytes directly with Ed25519 and over keccak256 with ECDSA (same as the SDK signer). */
    fun hashToSign(bodyBytes: ByteArray, publicKey: PublicKey): ByteArray =
        if (publicKey.isED25519) bodyBytes else bodyBytes.toKeccak()

    /** Card signatures for secp256k1 are not guaranteed low-S; Hedera nodes require canonical ones. */
    fun normalizeSignature(signature: ByteArray, publicKey: PublicKey): ByteArray =
        if (publicKey.isED25519) signature else CryptoUtils.normalize(signature)

    /** `proto.SignatureMap` with a single pair for [publicKey], as `hedera_signTransaction` returns it. */
    fun signatureMapBytes(signature: ByteArray, publicKey: PublicKey): ByteArray =
        SignatureMap.newBuilder().addSigPair(publicKey.toSignaturePairProtobuf(signature)).build().toByteArray()

    /**
     * `hedera_signMessage` preimage, as `@hashgraph/hedera-wallet-connect` builds it: prefix, then `message.length`
     * — a JavaScript string length, i.e. UTF-16 code units, not bytes — then the UTF-8 message.
     */
    fun messageBytes(message: String): ByteArray =
        (MESSAGE_PREFIX + message.length + message).toByteArray(Charsets.UTF_8)

    private fun AccountID.format(): String = "$shardNum.$realmNum.$accountNum"

    private fun TokenID.format(): String = "$shardNum.$realmNum.$tokenNum"
}
