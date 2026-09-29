package com.tangem.domain.tonconnect.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Wallet → dApp reply to a `ConnectRequest` (`spec/connect.md`). */
sealed class TonConnectConnectEvent {

    abstract val id: Int

    abstract fun toJson(): JsonObject

    data class Connect(
        override val id: Int,
        val items: List<TonConnectConnectItemReply>,
        val device: TonConnectDeviceInfo,
    ) : TonConnectConnectEvent() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("event", "connect")
            put("id", id)
            put(
                "payload",
                buildJsonObject {
                    put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
                    put("device", device.toJson())
                },
            )
        }
    }

    data class ConnectError(
        override val id: Int,
        val code: TonConnectErrorCode,
        val message: String,
    ) : TonConnectConnectEvent() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("event", "connect_error")
            put("id", id)
            put(
                "payload",
                buildJsonObject {
                    put("code", code.code)
                    put("message", message)
                },
            )
        }
    }
}

/** One entry of `ConnectEventSuccess.payload.items`. */
sealed class TonConnectConnectItemReply {

    abstract fun toJson(): JsonObject

    data class TonAddress(
        /** Raw form `0:<hex>`. */
        val address: String,
        val network: TonConnectNetworkId,
        /** Hex without `0x`. */
        val publicKey: String,
        /** Standard (not url-safe) base64 BoC of the wallet contract's `StateInit`. */
        val walletStateInit: String,
    ) : TonConnectConnectItemReply() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("name", TonConnectConnectItem.TON_ADDRESS_NAME)
            put("address", address)
            put("network", network.value)
            put("publicKey", publicKey)
            put("walletStateInit", walletStateInit)
        }
    }

    data class TonProof(val proof: TonConnectProof) : TonConnectConnectItemReply() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("name", TonConnectConnectItem.TON_PROOF_NAME)
            put("proof", proof.toJson())
        }
    }

    /** Per-item error, e.g. code 400 for an item the wallet does not support. */
    data class Error(val name: String, val code: TonConnectErrorCode, val message: String?) : TonConnectConnectItemReply() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put(
                "error",
                buildJsonObject {
                    put("code", code.code)
                    message?.let { put("message", it) }
                },
            )
        }
    }
}

/** `TonProofItemReplySuccess.proof`. */
class TonConnectProof(
    /** Unix seconds. */
    val timestamp: Long,
    val domain: String,
    /** 64-byte Ed25519 signature. */
    val signature: ByteArray,
    val payload: String,
) {

    val signatureBase64: String get() = Base64.getEncoder().encodeToString(signature)

    fun toJson(): JsonObject = buildJsonObject {
        put("timestamp", timestamp.toString()) // serialised as a string per spec
        put(
            "domain",
            buildJsonObject {
                put("lengthBytes", domain.toByteArray(Charsets.UTF_8).size)
                put("value", domain)
            },
        )
        put("signature", signatureBase64)
        put("payload", payload)
    }
}
