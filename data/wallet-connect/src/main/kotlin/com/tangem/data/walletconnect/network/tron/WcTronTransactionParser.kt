package com.tangem.data.walletconnect.network.tron

import com.tangem.blockchain.extensions.encodeBase58
import com.tangem.common.extensions.calculateSha256
import com.tangem.common.extensions.hexToBytes
import com.tangem.common.extensions.toHexString
import com.tangem.domain.walletconnect.model.WcTronTransactionDetails
import org.tron.protos.Transaction
import org.tron.protos.contract.TransferContract
import org.tron.protos.contract.TriggerSmartContract

/**
 * Pure, testable parts of the Tron WalletConnect handlers: `raw_data_hex` → bytes to sign + what the user sees,
 * and signature formatting.
 */
internal object WcTronTransactionParser {

    /** Upper bound on a serialised `Transaction.raw` accepted from a dApp (the node caps at 500 KiB). */
    const val MAX_RAW_DATA_BYTE_COUNT: Int = 64 * 1024

    private const val EVM_LEGACY_REC_ID_OFFSET = 27
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    private const val SIGNATURE_BYTE_COUNT = 65

    /** What the wallet actually signs, decoded from `raw_data_hex`. */
    data class SignableTransaction(
        /** Serialised `protocol.Transaction.raw`. */
        val rawData: ByteArray,
        /** `sha256(rawData)` — both the signing digest and the txID. */
        val hash: ByteArray,
        val details: WcTronTransactionDetails,
    ) {
        val txId: String get() = hash.toHexString().lowercase()
    }

    sealed class ParseError(message: String) : Exception(message) {
        class InvalidRawData : ParseError("raw_data_hex is not a valid Tron transaction")
        class TxIdMismatch : ParseError("txID does not match raw_data_hex")
        class UnsupportedTransaction(reason: String) : ParseError("Unsupported Tron transaction: $reason")
        class OwnerMismatch : ParseError("The transaction owner is not the connected account")
    }

    /**
     * Decodes `raw_data_hex`, checks it against the dApp's `txID`, parses the single contract it carries and verifies
     * the connected account is its owner.
     */
    @Suppress("ThrowsCount")
    fun makeSignableTransaction(rawDataHex: String, txId: String?, expectedOwnerAddress: String): SignableTransaction {
        val hex = rawDataHex.removePrefix("0x")
        val rawData = hex
            .takeIf { it.isNotEmpty() && it.length % 2 == 0 && HEX_REGEX.matches(it) }
            ?.hexToBytes()
            ?.takeIf { it.size <= MAX_RAW_DATA_BYTE_COUNT }
            ?: throw ParseError.InvalidRawData()

        val hash = rawData.calculateSha256()

        // A txID that does not match the bytes means the dApp is showing the user one transaction and asking the
        // wallet to sign another; refuse rather than pick a side.
        if (txId != null && !txId.removePrefix("0x").equals(hash.toHexString(), ignoreCase = true)) {
            throw ParseError.TxIdMismatch()
        }

        val raw = runCatching { Transaction.raw.ADAPTER.decode(rawData) }.getOrNull()
            ?: throw ParseError.InvalidRawData()

        val contract = raw.contract.singleOrNull()
            ?: throw ParseError.UnsupportedTransaction("the raw transaction doesn't contain exactly one contract")
        val memo = raw.data_.toByteArray().takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

        val details = when (contract.type) {
            Transaction.Contract.ContractType.TransferContract -> {
                val transfer = runCatching { contract.parameter?.unpack(TransferContract.ADAPTER) }.getOrNull()
                    ?: throw ParseError.InvalidRawData()
                WcTronTransactionDetails.Transfer(
                    ownerAddress = transfer.owner_address.toByteArray().toTronBase58(),
                    destinationAddress = transfer.to_address.toByteArray().toTronBase58(),
                    amountSun = transfer.amount,
                    memo = memo,
                )
            }
            Transaction.Contract.ContractType.TriggerSmartContract -> {
                val call = runCatching { contract.parameter?.unpack(TriggerSmartContract.ADAPTER) }.getOrNull()
                    ?: throw ParseError.InvalidRawData()
                if (call.data_.size == 0) {
                    throw ParseError.UnsupportedTransaction("the raw transaction carries no calldata")
                }
                WcTronTransactionDetails.ContractCall(
                    ownerAddress = call.owner_address.toByteArray().toTronBase58(),
                    contractAddress = call.contract_address.toByteArray().toTronBase58(),
                    callValueSun = call.call_value,
                    callDataHex = call.data_.toByteArray().toHexString().lowercase(),
                    feeLimitSun = raw.fee_limit.takeIf { it > 0 },
                    memo = memo,
                )
            }
            else -> throw ParseError.UnsupportedTransaction(
                "${contract.type} is not a transfer or a smart-contract call",
            )
        }

        // The signature authorises whatever `owner_address` says, not whatever `params.address` says.
        if (details.ownerAddress != expectedOwnerAddress) throw ParseError.OwnerMismatch()

        return SignableTransaction(rawData = rawData, hash = hash, details = details)
    }

    /** TIP-191 style digest used by `tronWeb.trx.signMessageV2`. */
    fun messageBytesToHash(message: String): ByteArray {
        val body = message.toByteArray(Charsets.UTF_8)
        return "\u0019TRON Signed Message:\n".toByteArray(Charsets.UTF_8) + body.size.toString().toByteArray() + body
    }

    /**
     * Transaction signatures travel as 65 hex bytes `r ‖ s ‖ recid` with `recid` 0/1, as TronWeb emits them.
     * [rsvLegacyEvm] is `r ‖ s ‖ (27 + recid)`.
     */
    fun transactionSignatureHex(rsvLegacyEvm: ByteArray): String {
        val bytes = normalized(rsvLegacyEvm)
        bytes[SIGNATURE_BYTE_COUNT - 1] = (bytes[SIGNATURE_BYTE_COUNT - 1] - EVM_LEGACY_REC_ID_OFFSET).toByte()
        return bytes.toHexString().lowercase()
    }

    /** Message signatures follow `signMessageV2`: `0x` ‖ 65 bytes with `v = 27 + recid`. */
    fun messageSignatureHex(rsvLegacyEvm: ByteArray): String = "0x" + normalized(rsvLegacyEvm).toHexString().lowercase()

    private fun normalized(signature: ByteArray): ByteArray {
        require(signature.size == SIGNATURE_BYTE_COUNT) { "signature must be 65 bytes" }
        val v = signature[SIGNATURE_BYTE_COUNT - 1].toInt()
        require(v == EVM_LEGACY_REC_ID_OFFSET || v == EVM_LEGACY_REC_ID_OFFSET + 1) { "unexpected recovery byte $v" }
        return signature.copyOf()
    }

    /** 21-byte `0x41…` address → base58check, as shown everywhere in the Tron ecosystem. */
    private fun ByteArray.toTronBase58(): String = encodeBase58(checked = true)
}
