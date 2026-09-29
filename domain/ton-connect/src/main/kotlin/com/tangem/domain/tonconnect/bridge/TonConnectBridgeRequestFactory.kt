package com.tangem.domain.tonconnect.bridge

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectMethod
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Builds the two HTTP bridge requests (`spec/bridge.md`): `GET /events` and `POST /message`.
 *
 * Kept separate from the transport so the exact URLs and headers are unit-testable.
 */
class TonConnectBridgeRequestFactory(bridgeUrl: String) {

    private val bridgeUrl: HttpUrl = bridgeUrl.toHttpUrlOrNull()
        ?: throw TonConnectException.InternalFailure("invalid bridge URL")

    /** `GET <bridge>/events?client_id=a,b,c[&last_event_id=…]&heartbeat=message` with `Accept: text/event-stream`. */
    fun eventsRequest(clientIds: List<TonConnectClientId>, lastEventId: String?): Request {
        if (clientIds.isEmpty()) throw TonConnectException.InternalFailure("no client ids to subscribe")

        val url = endpoint("events")
            .addQueryParameter("client_id", clientIds.joinToString(separator = ",") { it.hex })
            .apply { lastEventId?.let { addQueryParameter("last_event_id", it) } }
            // `heartbeat=message` makes keep-alives visible to non-browser clients.
            .addQueryParameter("heartbeat", "message")
            .build()

        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
    }

    /** `POST <bridge>/message?client_id=&to=&ttl=[&topic=][&trace_id=]` with a base64 body. */
    fun sendMessageRequest(
        from: TonConnectClientId,
        to: TonConnectClientId,
        encryptedMessage: ByteArray,
        ttl: Int = DEFAULT_TTL_SECONDS,
        topic: TonConnectMethod?,
        traceId: String?,
    ): Request {
        val url = endpoint("message")
            .addQueryParameter("client_id", from.hex)
            .addQueryParameter("to", to.hex)
            .addQueryParameter("ttl", ttl.toString())
            .apply {
                topic?.let { addQueryParameter("topic", it.wireName) }
                traceId?.let { addQueryParameter("trace_id", it) }
            }
            .build()

        return Request.Builder()
            .url(url)
            .post(Base64.getEncoder().encodeToString(encryptedMessage).toRequestBody(TEXT_PLAIN))
            .build()
    }

    /** `https://host/bridge` and `https://host/bridge/` both become `https://host/bridge/<path>`. */
    private fun endpoint(path: String): HttpUrl.Builder {
        val builder = bridgeUrl.newBuilder()
        val trailingEmptySegment = bridgeUrl.pathSegments.lastOrNull() == ""
        if (trailingEmptySegment) builder.removePathSegment(bridgeUrl.pathSegments.lastIndex)
        return builder.addPathSegment(path)
    }

    companion object {
        /** Bridges must support at least 300 s; the wallet has no reason to ask for more. */
        const val DEFAULT_TTL_SECONDS: Int = 300

        private val TEXT_PLAIN = "text/plain".toMediaType()
    }
}
