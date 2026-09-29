package com.tangem.domain.tonconnect.deeplink

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.model.TonConnectConnectItem
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLEncoder

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectDeepLinkParserTest {

    private val parser = TonConnectDeepLinkParser()
    private val clientIdHex = "ab".repeat(32)
    private val connectRequestJson =
        """{"manifestUrl":"https://app.example.com/tonconnect-manifest.json","items":[{"name":"ton_addr"},{"name":"ton_proof","payload":"nonce-123"}]}"""

    private fun link(base: String = "tc://", vararg params: Pair<String, String>): String =
        base + "?" + params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

    @Test
    fun `GIVEN unified link with all parameters WHEN parsing THEN every field is read`() {
        // Arrange
        val url = link(
            "tc://",
            "v" to "2",
            "id" to clientIdHex,
            "r" to connectRequestJson,
            "ret" to "back",
            "trace_id" to "019a2a92-a884-7cfc-b1bc-caab18644b6f",
        )

        // Act
        val result = parser.parse(url)

        // Assert
        assertThat(parser.isUnifiedLink(url)).isTrue()
        assertThat(result.protocolVersion).isEqualTo(2)
        assertThat(result.dAppClientId.hex).isEqualTo(clientIdHex)
        assertThat(result.returnStrategy).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
        assertThat(result.traceId).isEqualTo("019a2a92-a884-7cfc-b1bc-caab18644b6f")
        val request = result.connectRequest!!
        assertThat(request.manifestUrl).isEqualTo("https://app.example.com/tonconnect-manifest.json")
        assertThat(request.items).containsExactly(
            TonConnectConnectItem.TonAddress(network = null),
            TonConnectConnectItem.TonProof(payload = "nonce-123"),
        ).inOrder()
        assertThat(request.proofPayload).isEqualTo("nonce-123")
    }

    @Test
    fun `GIVEN universal link WHEN parsing THEN same parameters are accepted and ret defaults to back`() {
        val url = link("https://app.tangem.com/ton-connect", "v" to "2", "id" to clientIdHex, "r" to connectRequestJson)

        val result = parser.parse(url)

        assertThat(parser.isUnifiedLink(url)).isFalse()
        assertThat(result.connectRequest).isNotNull()
        assertThat(result.returnStrategy).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
    }

    @Test
    fun `GIVEN ret values WHEN parsing THEN strategies are mapped`() {
        fun ret(value: String) = parser.parse(link("tc://", "v" to "2", "id" to clientIdHex, "r" to connectRequestJson, "ret" to value)).returnStrategy

        assertThat(ret("none")).isEqualTo(TonConnectDeepLink.ReturnStrategy.None)
        assertThat(ret("https://back.example.com/done")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Url("https://back.example.com/done"))
        assertThat(ret("not a url")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
    }

    @Test
    fun `GIVEN empty return-only link WHEN parsing THEN connect request is absent`() {
        val result = parser.parse("tc://?id=$clientIdHex&ret=none")

        assertThat(result.connectRequest).isNull()
        assertThat(result.returnStrategy).isEqualTo(TonConnectDeepLink.ReturnStrategy.None)
        assertThat(result.dAppClientId.hex).isEqualTo(clientIdHex)
    }

    @Test
    fun `GIVEN unencoded JSON in r WHEN parsing THEN link still parses`() {
        val result = parser.parse("tc://?v=2&id=$clientIdHex&r=$connectRequestJson")

        assertThat(result.connectRequest?.items).hasSize(2)
    }

    @Test
    fun `GIVEN ton_addr network and unknown items WHEN parsing THEN they are preserved`() {
        val json = """{"manifestUrl":"https://d.app","items":[{"name":"ton_addr","network":"-3"},{"name":"ton_future","extra":1}]}"""

        val result = parser.parse(link("tc://", "v" to "2", "id" to clientIdHex, "r" to json))

        assertThat(result.connectRequest?.items).containsExactly(
            TonConnectConnectItem.TonAddress(TonConnectNetworkId.TESTNET),
            TonConnectConnectItem.Unsupported("ton_future"),
        ).inOrder()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1", "3", "two"])
    fun `GIVEN unsupported version WHEN parsing THEN UnsupportedProtocolVersion is thrown`(version: String) {
        val params = mutableListOf("id" to clientIdHex, "r" to connectRequestJson)
        if (version.isNotEmpty()) params += "v" to version

        assertThrows<TonConnectException.UnsupportedProtocolVersion> { parser.parse(link("tc://", *params.toTypedArray())) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abcd", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"])
    fun `GIVEN invalid client id WHEN parsing THEN InvalidClientId is thrown`(id: String) {
        assertThrows<TonConnectException.InvalidClientId> { parser.parse(link("tc://", "v" to "2", "id" to id, "r" to connectRequestJson)) }
    }

    @Test
    fun `GIVEN malformed connect requests WHEN parsing THEN MalformedConnectRequest names the problem`() {
        fun reason(vararg params: Pair<String, String>) =
            assertThrows<TonConnectException.MalformedConnectRequest> { parser.parse(link("tc://", *params)) }.protocolMessage

        assertThat(reason("v" to "2", "r" to connectRequestJson)).isEqualTo("missing id")
        assertThat(reason("v" to "2", "id" to clientIdHex, "r" to "{not json")).isEqualTo("r is not a valid ConnectRequest")
        assertThat(reason("v" to "2", "id" to clientIdHex, "r" to """{"manifestUrl":"https://d.app/m.json","items":[{"name":"ton_proof","payload":"x"}]}"""))
            .isEqualTo("ton_addr item is required")
        assertThat(reason("v" to "2", "id" to clientIdHex, "r" to """{"manifestUrl":"http://d.app/m.json","items":[{"name":"ton_addr"}]}"""))
            .isEqualTo("manifestUrl must use https")
    }
}
