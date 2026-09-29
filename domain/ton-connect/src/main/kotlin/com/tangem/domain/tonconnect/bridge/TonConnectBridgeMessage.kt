package com.tangem.domain.tonconnect.bridge

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectJson
import com.tangem.domain.tonconnect.model.TonConnectJson.string
import java.util.Base64

/** `BridgeMessage` envelope written by the bridge to the wallet's SSE channel (`spec/bridge.md`). */
class TonConnectBridgeMessage(
    /** SSE `id:` of the event; pass it back as `last_event_id` on reconnect. */
    val eventId: String?,
    /** Sender `client_id` (the dApp). */
    val from: TonConnectClientId,
    /** `nonce ‖ ciphertext`, already base64-decoded. */
    val encryptedMessage: ByteArray,
    val traceId: String?,
) {

    companion object {
        /** Decodes the `data:` field of an SSE event. Heartbeats are not `BridgeMessage`s and must be filtered before. */
        fun decode(sseData: String, eventId: String?): TonConnectBridgeMessage {
            val json = TonConnectJson.parseObjectOrNull(sseData)
                ?: throw TonConnectException.MalformedEnvelope("BridgeMessage is not valid JSON")
            val from = json.string("from")
                ?: throw TonConnectException.MalformedEnvelope("BridgeMessage.from is missing")
            val message = json.string("message")
                ?: throw TonConnectException.MalformedEnvelope("BridgeMessage.message is missing")
            val encrypted = runCatching { Base64.getDecoder().decode(message) }.getOrNull()
                ?: throw TonConnectException.MalformedEnvelope("BridgeMessage.message is not valid base64")

            return TonConnectBridgeMessage(
                eventId = eventId,
                from = TonConnectClientId.fromHex(from),
                encryptedMessage = encrypted,
                traceId = json.string("trace_id"),
            )
        }
    }
}
