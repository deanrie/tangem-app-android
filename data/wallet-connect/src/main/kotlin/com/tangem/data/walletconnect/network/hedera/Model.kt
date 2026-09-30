package com.tangem.data.walletconnect.network.hedera

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Request params, HIP-820 / @hashgraph/hedera-wallet-connect

@JsonClass(generateAdapter = true)
internal data class WcHederaSignAndExecuteTransactionRequest(
    @Json(name = "signerAccountId") val signerAccountId: String,
    @Json(name = "transactionList") val transactionList: String,
)

@JsonClass(generateAdapter = true)
internal data class WcHederaSignTransactionRequest(
    @Json(name = "signerAccountId") val signerAccountId: String,
    @Json(name = "transactionBody") val transactionBody: String,
)

@JsonClass(generateAdapter = true)
internal data class WcHederaSignMessageRequest(
    @Json(name = "signerAccountId") val signerAccountId: String,
    @Json(name = "message") val message: String,
)

// Responses

@JsonClass(generateAdapter = true)
internal data class WcHederaSignAndExecuteTransactionResponse(
    @Json(name = "nodeId") val nodeId: String,
    /** Base64 SHA-384 transaction hash. */
    @Json(name = "transactionHash") val transactionHash: String,
    @Json(name = "transactionId") val transactionId: String,
)

@JsonClass(generateAdapter = true)
internal data class WcHederaSignatureMapResponse(
    /** Base64 `proto.SignatureMap`. */
    @Json(name = "signatureMap") val signatureMap: String,
)

@JsonClass(generateAdapter = true)
internal data class WcHederaNodeAddressesResponse(
    @Json(name = "nodes") val nodes: List<String>,
)
