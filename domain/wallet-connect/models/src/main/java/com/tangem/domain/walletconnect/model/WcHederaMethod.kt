package com.tangem.domain.walletconnect.model

/**
 * Hedera WalletConnect method names (HIP-820).
 *
 * @see <a href="https://hips.hedera.com/hip/hip-820">HIP-820</a>
 */
enum class WcHederaMethodName(override val raw: String) : WcMethodName {
    SignAndExecuteTransaction("hedera_signAndExecuteTransaction"),
    SignTransaction("hedera_signTransaction"),
    SignMessage("hedera_signMessage"),
    GetNodeAddresses("hedera_getNodeAddresses"),
}

/**
 * Hedera WalletConnect methods.
 */
sealed interface WcHederaMethod : WcMethod {

    /**
     * Sign a frozen `TransactionList` and submit it to the network.
     *
     * @property signerAccountId Connected account, `shard.realm.num`
     * @property transactionListBase64 Base64 `proto.TransactionList` (or a single `proto.Transaction`)
     * @property summary What the transaction does, decoded by the wallet from the bytes that get signed
     */
    data class SignAndExecuteTransaction(
        val signerAccountId: String,
        val transactionListBase64: String,
        val summary: WcHederaTransactionSummary,
    ) : WcHederaMethod

    /**
     * Sign a single `TransactionBody` and return the `SignatureMap`; the dApp submits.
     *
     * @property signerAccountId Connected account, `shard.realm.num`
     * @property transactionBodyBase64 Base64 `proto.TransactionBody`
     * @property summary What the transaction does, decoded by the wallet from the bytes that get signed
     */
    data class SignTransaction(
        val signerAccountId: String,
        val transactionBodyBase64: String,
        val summary: WcHederaTransactionSummary,
    ) : WcHederaMethod

    /**
     * Sign a plain-text message (`\x19Hedera Signed Message:\n` prefix).
     */
    data class SignMessage(
        val signerAccountId: String,
        val message: String,
    ) : WcHederaMethod

    /** Answered without user interaction: the consensus node account ids the wallet talks to. */
    data object GetNodeAddresses : WcHederaMethod
}

/**
 * What a Hedera transaction does, read from its `TransactionBody`. All chunks of a `TransactionList` share one body
 * except for the node account id, so one summary describes the whole list.
 *
 * @property transactionType HAPI body case, e.g. `CRYPTOTRANSFER`, `CONTRACTCALL`, `TOKENASSOCIATE`
 * @property payerAccountId Account that pays the fee (`transactionID.accountID`), if set
 * @property transactionId `payer@seconds.nanos`, if set
 * @property nodeAccountIds Consensus nodes the chunks are addressed to
 * @property memo Transaction memo, possibly empty
 * @property maxFeeTinybars `transactionFee` cap, if set
 * @property hbarTransfers ℏ legs of a `CryptoTransfer`, in tinybars (negative = debit)
 * @property tokenTransfers Token legs of a `CryptoTransfer`, in the token's smallest unit
 */
data class WcHederaTransactionSummary(
    val transactionType: String,
    val payerAccountId: String?,
    val transactionId: String?,
    val nodeAccountIds: List<String>,
    val memo: String,
    val maxFeeTinybars: Long?,
    val hbarTransfers: List<HbarTransfer>,
    val tokenTransfers: List<TokenTransfer>,
) {
    data class HbarTransfer(val accountId: String, val tinybars: Long)

    data class TokenTransfer(val tokenId: String, val accountId: String, val amount: Long)
}
