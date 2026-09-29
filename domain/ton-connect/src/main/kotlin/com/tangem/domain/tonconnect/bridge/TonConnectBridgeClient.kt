package com.tangem.domain.tonconnect.bridge

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectMethod
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP bridge transport (`spec/bridge.md`) on top of OkHttp.
 *
 * The bridge is fully untrusted: it only ever sees `client_id`s and ciphertext. Encryption and decryption happen in
 * `TonConnectSessionCrypto`; this type moves bytes.
 */
class TonConnectBridgeClient(
    bridgeUrl: String,
    private val dispatchers: CoroutineDispatcherProvider,
    client: OkHttpClient = OkHttpClient(),
) {

    class HttpStatusException(val statusCode: Int) : IOException("bridge responded with HTTP $statusCode")

    private val requestFactory = TonConnectBridgeRequestFactory(bridgeUrl)

    private val postClient: OkHttpClient = client.newBuilder()
        .callTimeout(POST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // SSE streams stay open indefinitely; only the connect phase is bounded.
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Posts an encrypted message for [to]. The bridge buffers it up to `ttl` seconds. */
    suspend fun send(
        encryptedMessage: ByteArray,
        from: TonConnectClientId,
        to: TonConnectClientId,
        topic: TonConnectMethod?,
        traceId: String?,
    ) {
        val request = requestFactory.sendMessageRequest(
            from = from,
            to = to,
            encryptedMessage = encryptedMessage,
            topic = topic,
            traceId = traceId,
        )
        postClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
        }
    }

    /**
     * Subscribes to the queues of [clientIds] and emits every `BridgeMessage` until the connection drops or the
     * collector is cancelled. Heartbeats are filtered; malformed envelopes are skipped.
     *
     * Reconnection (with the last seen [TonConnectBridgeMessage.eventId] as `last_event_id`) is the caller's
     * responsibility so it can apply its own back-off and lifecycle rules.
     */
    fun events(clientIds: List<TonConnectClientId>, lastEventId: String?): Flow<TonConnectBridgeMessage> = flow {
        val request = requestFactory.eventsRequest(clientIds, lastEventId)
        streamClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val source = response.body?.source() ?: return@use
            val parser = TonConnectSseParser()

            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val event = parser.feed(line) ?: continue
                if (event.isHeartbeat) continue
                runCatching { TonConnectBridgeMessage.decode(event.data, event.id) }
                    .getOrNull()
                    ?.let { emit(it) }
            }

            // A stream that ends without a blank line still leaves one buffered event.
            parser.feed("")?.takeUnless { it.isHeartbeat }?.let { event ->
                runCatching { TonConnectBridgeMessage.decode(event.data, event.id) }.getOrNull()?.let { emit(it) }
            }
        }
    }.flowOn(dispatchers.io)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            },
        )
        continuation.invokeOnCancellation { cancel() }
    }

    private companion object {
        const val POST_TIMEOUT_SECONDS = 30L
    }
}
