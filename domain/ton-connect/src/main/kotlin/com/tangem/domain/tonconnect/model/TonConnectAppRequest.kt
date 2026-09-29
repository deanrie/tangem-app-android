package com.tangem.domain.tonconnect.model

import com.tangem.domain.tonconnect.model.TonConnectJson.contentOrNullSafe
import com.tangem.domain.tonconnect.model.TonConnectJson.string
import com.tangem.domain.tonconnect.model.TonConnectJson.stringOrNumber
import kotlinx.serialization.json.JsonArray

/** RPC methods a dApp may call after connecting (`spec/rpc.md`). */
sealed class TonConnectMethod(val wireName: String) {
    data object SendTransaction : TonConnectMethod("sendTransaction")
    data object SignMessage : TonConnectMethod("signMessage")
    data object SignData : TonConnectMethod("signData")
    data object Disconnect : TonConnectMethod("disconnect")
    data class Unknown(val name: String) : TonConnectMethod(name)

    companion object {
        fun fromWireName(name: String): TonConnectMethod = when (name) {
            SendTransaction.wireName -> SendTransaction
            SignMessage.wireName -> SignMessage
            SignData.wireName -> SignData
            Disconnect.wireName -> Disconnect
            else -> Unknown(name)
        }
    }
}

/** `AppRequest` — the decrypted plaintext of a dApp → wallet bridge message. */
data class TonConnectAppRequest(
    val method: TonConnectMethod,
    /** Operation-specific parameters; for `sendTransaction` / `signData` a single JSON string. */
    val params: List<String>,
    /** Monotonically increasing per session; the wallet must reject non-increasing ids. */
    val id: String,
) {

    /** The single JSON-string parameter carried by `sendTransaction`, `signMessage` and `signData`. */
    fun singleJsonParameter(): String {
        if (params.size != 1) {
            throw TonConnectException.BadRequest("expected exactly one parameter, got ${params.size}")
        }
        return params[0]
    }

    companion object {
        /** Decodes the plaintext of an incoming bridge message. */
        fun decode(plaintext: String): TonConnectAppRequest {
            val json = TonConnectJson.parseObjectOrNull(plaintext)
                ?: throw TonConnectException.MalformedEnvelope("AppRequest is not valid JSON")
            val method = json.string("method")
                ?: throw TonConnectException.MalformedEnvelope("AppRequest.method is missing")
            val id = json.stringOrNumber("id")
                ?: throw TonConnectException.MalformedEnvelope("AppRequest.id is missing")
            val params = (json["params"] as? JsonArray)?.map { element ->
                element.contentOrNullSafe()
                    ?: throw TonConnectException.MalformedEnvelope("AppRequest.params must be strings")
            }.orEmpty()

            return TonConnectAppRequest(method = TonConnectMethod.fromWireName(method), params = params, id = id)
        }
    }
}
