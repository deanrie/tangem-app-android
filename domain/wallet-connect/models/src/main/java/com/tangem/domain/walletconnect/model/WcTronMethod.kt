package com.tangem.domain.walletconnect.model

/**
 * Tron WalletConnect method names.
 *
 * @see <a href="https://docs.reown.com/advanced/multichain/rpc-reference/tron-rpc">Tron RPC Reference</a>
 */
enum class WcTronMethodName(override val raw: String) : WcMethodName {
    SignTransaction("tron_signTransaction"),
    SignMessage("tron_signMessage"),
}

/**
 * Tron WalletConnect methods.
 */
sealed interface WcTronMethod : WcMethod {
    val methodName: String

    /**
     * Sign a Tron transaction without broadcasting it.
     *
     * Everything the wallet signs and shows is derived from [rawDataHex] — the serialised `protocol.Transaction.raw`.
     * The dApp's JSON [rawDataJson] is opaque and only echoed back verbatim in the response.
     *
     * @property address Sender address in base58 (the dApp's `params.address`)
     * @property rawDataHex Hex of the serialised `Transaction.raw`; `sha256(rawDataHex)` is signed and is the `txID`
     * @property txId `txID` as sent by the dApp, if any; must match `sha256(rawDataHex)`
     * @property isVisible `visible` flag from the request (address format of the JSON `raw_data`)
     * @property rawDataJson The dApp's JSON `raw_data` object as raw JSON text, returned unchanged
     * @property details What [rawDataHex] actually does, decoded by the wallet
     */
    data class SignTransaction(
        val address: String,
        val rawDataHex: String,
        val txId: String?,
        val isVisible: Boolean,
        val rawDataJson: String?,
        val details: WcTronTransactionDetails,
    ) : WcTronMethod {
        override val methodName: String = WcTronMethodName.SignTransaction.raw
    }

    /**
     * Sign a personal message (`tronWeb.trx.signMessageV2` layout).
     *
     * @property address Signer address in base58
     * @property message Plain-text message
     */
    data class SignMessage(
        val address: String,
        val message: String,
    ) : WcTronMethod {
        override val methodName: String = WcTronMethodName.SignMessage.raw
    }
}

/**
 * The single contract carried by a Tron transaction, decoded from the bytes that get signed.
 */
sealed interface WcTronTransactionDetails {
    /** Base58 address whose signature the transaction needs; must be the connected account. */
    val ownerAddress: String

    /** Optional `raw_data.data` memo, UTF-8. */
    val memo: String?

    /** `TransferContract`: [amountSun] sun from [ownerAddress] to [destinationAddress]. */
    data class Transfer(
        override val ownerAddress: String,
        val destinationAddress: String,
        val amountSun: Long,
        override val memo: String?,
    ) : WcTronTransactionDetails

    /** `TriggerSmartContract`: call [contractAddress] with [callDataHex], attaching [callValueSun] sun. */
    data class ContractCall(
        override val ownerAddress: String,
        val contractAddress: String,
        val callValueSun: Long,
        /** ABI calldata, lowercase hex without prefix. */
        val callDataHex: String,
        /** `fee_limit` in sun; `null` when the transaction sets none. */
        val feeLimitSun: Long?,
        override val memo: String?,
    ) : WcTronTransactionDetails
}
