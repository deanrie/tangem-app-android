package com.tangem.domain.tonconnect.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** `WalletResponse` — the wallet's reply to an `AppRequest` (`spec/rpc.md`). `id` mirrors the request id. */
sealed class TonConnectWalletResponse {

    abstract val id: String

    abstract fun toJson(): JsonObject

    data class Success(override val id: String, val result: TonConnectResponseResult) : TonConnectWalletResponse() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("result", result.toJson())
        }
    }

    data class Error(override val id: String, val code: TonConnectErrorCode, val message: String) : TonConnectWalletResponse() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code.code)
                    put("message", message)
                },
            )
        }
    }

    companion object {
        /** Builds the error reply for a failed request. */
        fun failure(id: String, error: TonConnectException): TonConnectWalletResponse =
            Error(id = id, code = error.protocolCode, message = error.protocolMessage)
    }
}

/** Method-specific `result` shapes. */
sealed class TonConnectResponseResult {

    abstract fun toJson(): JsonElement

    /** `sendTransaction`: base64 BoC of the broadcast external message. */
    data class SendTransaction(val externalMessageBoc: String) : TonConnectResponseResult() {
        override fun toJson(): JsonElement = JsonPrimitive(externalMessageBoc)
    }

    /** `signMessage`: base64 BoC of the signed message that the dApp will relay. */
    data class SignMessage(val internalBoc: String) : TonConnectResponseResult() {
        override fun toJson(): JsonElement = buildJsonObject { put("internalBoc", internalBoc) }
    }

    /** `signData`: signature plus the fields the dApp needs to verify it. */
    class SignData(
        /** 64-byte Ed25519 signature. */
        val signature: ByteArray,
        /** Raw wallet address `0:<hex>`. */
        val address: String,
        /** Unix seconds at signing time. */
        val timestamp: Long,
        /** dApp domain (URL host, not encoded). */
        val domain: String,
        /** The payload from the request, echoed verbatim. */
        val payload: TonConnectSignDataPayload,
    ) : TonConnectResponseResult() {
        override fun toJson(): JsonElement = buildJsonObject {
            put("signature", Base64.getEncoder().encodeToString(signature))
            put("address", address)
            put("timestamp", timestamp)
            put("domain", domain)
            put("payload", payload.toJson())
        }
    }

    /** `disconnect`: empty object. */
    data object Disconnect : TonConnectResponseResult() {
        override fun toJson(): JsonElement = buildJsonObject {}
    }
}

/** Wallet-initiated `disconnect` event (`spec/rpc.md`). */
data class TonConnectDisconnectEvent(
    /** Monotonically increasing per session, independent from request ids. */
    val id: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("event", "disconnect")
        put("id", id)
        put("payload", buildJsonObject {})
    }
}
