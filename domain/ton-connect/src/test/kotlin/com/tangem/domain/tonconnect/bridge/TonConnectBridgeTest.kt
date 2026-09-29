package com.tangem.domain.tonconnect.bridge

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectMethod
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectBridgeTest {

    private val wallet = TonConnectClientId.fromHex("aa".repeat(32))
    private val dApp = TonConnectClientId.fromHex("bb".repeat(32))

    // region SSE parser

    @Test
    fun `GIVEN multi-line stream WHEN feeding parser THEN events are emitted with sticky ids`() {
        // Arrange
        val parser = TonConnectSseParser()
        val lines = listOf(
            ": comment",
            "id: 1700000000001",
            "event: message",
            "data: {\"from\":\"aa\",",
            "data: \"message\":\"bb\"}",
            "",
            "data: second",
            "",
            "retry: 5000",
            "id: 1700000000002",
            "data:third-no-space",
            "",
        )

        // Act
        val events = lines.mapNotNull(parser::feed)

        // Assert
        assertThat(events).containsExactly(
            TonConnectSseEvent("1700000000001", "message", "{\"from\":\"aa\",\n\"message\":\"bb\"}"),
            TonConnectSseEvent("1700000000001", null, "second"),
            TonConnectSseEvent("1700000000002", null, "third-no-space"),
        ).inOrder()
    }

    @Test
    fun `GIVEN blank lines without data and CR line endings WHEN feeding parser THEN they are handled`() {
        val parser = TonConnectSseParser()

        assertThat(parser.feed("")).isNull()
        assertThat(parser.feed("event: heartbeat\r")).isNull()
        assertThat(parser.feed("")).isNull()
        assertThat(parser.feed("data: x\r")).isNull()
        assertThat(parser.feed("\r")).isEqualTo(TonConnectSseEvent(null, null, "x"))
    }

    @Test
    fun `GIVEN both heartbeat formats WHEN checking THEN they are recognised`() {
        assertThat(TonConnectSseEvent(null, "heartbeat", "").isHeartbeat).isTrue()
        assertThat(TonConnectSseEvent(null, "message", "heartbeat").isHeartbeat).isTrue()
        assertThat(TonConnectSseEvent(null, "message", "{}").isHeartbeat).isFalse()
    }

    // endregion

    // region Bridge message

    @Test
    fun `GIVEN envelopes with and without trace id WHEN decoding THEN fields are read`() {
        val from = "cd".repeat(32)
        val payload = byteArrayOf(1, 2, 3)

        val withTrace = TonConnectBridgeMessage.decode("""{"from":"$from","message":"${Base64.getEncoder().encodeToString(payload)}","trace_id":"t-1"}""", "42")
        val withoutTrace = TonConnectBridgeMessage.decode("""{"from":"$from","message":"AQID"}""", null)

        assertThat(withTrace.from.hex).isEqualTo(from)
        assertThat(withTrace.encryptedMessage).isEqualTo(payload)
        assertThat(withTrace.traceId).isEqualTo("t-1")
        assertThat(withTrace.eventId).isEqualTo("42")
        assertThat(withoutTrace.traceId).isNull()
    }

    @Test
    fun `GIVEN malformed envelopes WHEN decoding THEN exceptions name the problem`() {
        assertThat(assertThrows<TonConnectException.MalformedEnvelope> { TonConnectBridgeMessage.decode("heartbeat", null) }.protocolMessage)
            .isEqualTo("BridgeMessage is not valid JSON")
        assertThat(assertThrows<TonConnectException.MalformedEnvelope> { TonConnectBridgeMessage.decode("""{"from":"${"cd".repeat(32)}","message":"***"}""", null) }.protocolMessage)
            .isEqualTo("BridgeMessage.message is not valid base64")
        assertThrows<TonConnectException.InvalidClientId> { TonConnectBridgeMessage.decode("""{"from":"nope","message":"AQID"}""", null) }
    }

    // endregion

    // region Request factory

    @ParameterizedTest
    @ValueSource(strings = ["https://connect.ton.org/bridge", "https://connect.ton.org/bridge/"])
    fun `GIVEN bridge URL WHEN building events request THEN path query and headers are right`(bridge: String) {
        val request = TonConnectBridgeRequestFactory(bridge).eventsRequest(listOf(wallet, dApp), lastEventId = "17")

        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/bridge/events")
        assertThat(request.url.queryParameter("client_id")).isEqualTo(wallet.hex + "," + dApp.hex)
        assertThat(request.url.queryParameter("last_event_id")).isEqualTo("17")
        assertThat(request.url.queryParameter("heartbeat")).isEqualTo("message")
        assertThat(request.header("Accept")).isEqualTo("text/event-stream")
    }

    @Test
    fun `GIVEN no last event id WHEN building events request THEN parameter is omitted and empty subscription rejected`() {
        val factory = TonConnectBridgeRequestFactory("https://bridge.tonapi.io/bridge")

        assertThat(factory.eventsRequest(listOf(wallet), null).url.queryParameter("last_event_id")).isNull()
        assertThrows<TonConnectException.InternalFailure> { factory.eventsRequest(emptyList(), null) }
        assertThrows<TonConnectException.InternalFailure> { TonConnectBridgeRequestFactory("not a url") }
    }

    @Test
    fun `GIVEN message WHEN building send request THEN query and base64 body are right`() {
        // Arrange
        val factory = TonConnectBridgeRequestFactory("https://connect.ton.org/bridge")
        val ciphertext = byteArrayOf(0xDE.toByte(), 0xAD.toByte())

        // Act
        val request = factory.sendMessageRequest(wallet, dApp, ciphertext, topic = TonConnectMethod.SendTransaction, traceId = "trace")
        val minimal = factory.sendMessageRequest(wallet, dApp, byteArrayOf(1), ttl = 60, topic = null, traceId = null)

        // Assert
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/bridge/message")
        assertThat(request.url.queryParameterNames).containsExactly("client_id", "to", "ttl", "topic", "trace_id")
        assertThat(request.url.queryParameter("client_id")).isEqualTo(wallet.hex)
        assertThat(request.url.queryParameter("to")).isEqualTo(dApp.hex)
        assertThat(request.url.queryParameter("ttl")).isEqualTo("300")
        assertThat(request.url.queryParameter("topic")).isEqualTo("sendTransaction")
        assertThat(request.url.queryParameter("trace_id")).isEqualTo("trace")
        assertThat(request.body?.contentType()?.toString()).startsWith("text/plain")
        assertThat(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).isEqualTo(Base64.getEncoder().encodeToString(ciphertext))

        assertThat(minimal.url.queryParameterNames).containsExactly("client_id", "to", "ttl")
        assertThat(minimal.url.queryParameter("ttl")).isEqualTo("60")
    }

    // endregion
}
