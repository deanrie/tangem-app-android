package com.tangem.domain.tonconnect.model

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.FakeTonConnectSigner
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.crypto.TonConnectProofMessage
import com.tangem.domain.tonconnect.session.TonConnectConnectEventFactory
import com.tangem.domain.tonconnect.transaction.TonCells
import com.tangem.domain.tonconnect.transaction.TonConnectTransferBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.ton.boc.BagOfCells
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectModelsTest {

    private val deviceInfo = TonConnectDeviceInfo(
        platform = TonConnectDeviceInfo.Platform.ANDROID,
        appName = "tangem",
        appVersion = "5.30.0",
        features = listOf(
            TonConnectFeature.SendTransaction(maxMessages = 4),
            TonConnectFeature.SignData(listOf(TonConnectSignDataType.TEXT, TonConnectSignDataType.BINARY, TonConnectSignDataType.CELL)),
        ),
    )

    // region Manifest

    @Test
    fun `GIVEN full manifest with unknown fields WHEN decoding THEN fields are read and domain extracted`() {
        val body = """{"url":"https://App.Example.COM:8443/x?q=1","name":"Example App","iconUrl":"https://app.example.com/icon-180.png","termsOfUseUrl":"https://app.example.com/terms","somethingNew":{"nested":true}}"""

        val manifest = TonConnectManifest.decode(body)

        assertThat(manifest.name).isEqualTo("Example App")
        assertThat(manifest.termsOfUseUrl).isEqualTo("https://app.example.com/terms")
        assertThat(manifest.privacyPolicyUrl).isNull()
        assertThat(manifest.appDomain()).isEqualTo("app.example.com")
    }

    @ParameterizedTest
    @ValueSource(strings = ["tonkeeper", "localhost", ".example", "example.", "a..b", ""])
    fun `GIVEN host without proper dot separation WHEN validating domain THEN it is rejected`(host: String) {
        assertThat(TonConnectManifest.isValidAppDomain(host)).isFalse()
    }

    @Test
    fun `GIVEN invalid manifests WHEN decoding THEN ManifestContentError names the problem`() {
        fun reason(body: String) = assertThrows<TonConnectException.ManifestContentError> { TonConnectManifest.decode(body) }.protocolMessage

        assertThat(reason("{")).endsWith("not a valid manifest JSON")
        assertThat(reason("""{"url":"https://a.b","name":"x"}""")).endsWith("iconUrl is missing")
        assertThat(reason("""{"url":"http://a.b","name":"x","iconUrl":"https://a.b/i.png"}""")).endsWith("url must use https")
        assertThat(reason("""{"url":"https://tonkeeper","name":"x","iconUrl":"https://a.b/i.png"}""")).endsWith("url host is not a valid dApp domain")
        assertThat(reason("""{"url":"https://a.b","name":"x","iconUrl":"http://a.b/i.png"}""")).endsWith("iconUrl must use https")
        assertThat(reason("""{"url":"https://a.b","name":"  ","iconUrl":"https://a.b/i.png"}""")).endsWith("name is empty")
    }

    // endregion

    // region Incoming

    @Test
    fun `GIVEN app requests WHEN decoding THEN string and numeric ids and unknown methods are handled`() {
        val string = TonConnectAppRequest.decode("""{"method":"sendTransaction","params":["{}"],"id":"42"}""")
        val numeric = TonConnectAppRequest.decode("""{"method":"disconnect","params":[],"id":7}""")
        val unknown = TonConnectAppRequest.decode("""{"method":"signFuture","id":"1"}""")

        assertThat(string).isEqualTo(TonConnectAppRequest(TonConnectMethod.SendTransaction, listOf("{}"), "42"))
        assertThat(numeric.method).isEqualTo(TonConnectMethod.Disconnect)
        assertThat(numeric.id).isEqualTo("7")
        assertThat(unknown.method).isEqualTo(TonConnectMethod.Unknown("signFuture"))
        assertThat(unknown.params).isEmpty()
        assertThat(string.singleJsonParameter()).isEqualTo("{}")
        assertThrows<TonConnectException.BadRequest> { numeric.singleJsonParameter() }
        assertThrows<TonConnectException.MalformedEnvelope> { TonConnectAppRequest.decode("nope") }
    }

    @Test
    fun `GIVEN signData payloads WHEN decoding THEN all variants round-trip through JSON`() {
        val text = TonConnectSignDataPayload.decode("""{"type":"text","text":"Hi","network":"-239","from":"UQAAA"}""")
        val binary = TonConnectSignDataPayload.decode("""{"type":"binary","bytes":"3q2+7w=="}""")
        val cell = TonConnectSignDataPayload.decode("""{"type":"cell","schema":"x#00 = X;","cell":"te6cc"}""")

        assertThat(text).isEqualTo(TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Text("Hi"), TonConnectNetworkId.MAINNET, "UQAAA"))
        assertThat(binary.content).isEqualTo(TonConnectSignDataPayload.Content.Binary(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        assertThat(cell.content).isEqualTo(TonConnectSignDataPayload.Content.Cell("x#00 = X;", "te6cc"))
        assertThat(text.toJson().toString()).isEqualTo("""{"type":"text","text":"Hi","network":"-239","from":"UQAAA"}""")
        assertThrows<TonConnectException.BadRequest> { TonConnectSignDataPayload.decode("""{"type":"video","url":"x"}""") }
        assertThrows<TonConnectException.BadRequest> { TonConnectSignDataPayload.decode("""{"type":"binary","bytes":"***"}""") }
    }

    // endregion

    // region Outgoing

    @Test
    fun `GIVEN connect event WHEN encoding THEN items device and error shapes follow the spec`() {
        // Arrange
        val proof = TonConnectProof(timestamp = Vectors.NOW, domain = "app.example.com", signature = ByteArray(64) { 1 }, payload = "nonce")
        val event = TonConnectConnectEvent.Connect(
            id = 0,
            items = listOf(
                TonConnectConnectItemReply.TonAddress("0:abc", TonConnectNetworkId.MAINNET, "ff", "te6cc"),
                TonConnectConnectItemReply.TonProof(proof),
                TonConnectConnectItemReply.Error("ton_future", TonConnectErrorCode.METHOD_NOT_SUPPORTED, "Unsupported connect item"),
            ),
            device = deviceInfo,
        )

        // Act
        val json = event.toJson()

        // Assert
        assertThat(json["event"]!!.jsonPrimitive.content).isEqualTo("connect")
        assertThat(json["id"]!!.jsonPrimitive.content).isEqualTo("0")
        val items = json["payload"]!!.jsonObject["items"]!!.jsonArray
        assertThat(items[0].toString()).isEqualTo("""{"name":"ton_addr","address":"0:abc","network":"-239","publicKey":"ff","walletStateInit":"te6cc"}""")
        val proofJson = items[1].jsonObject["proof"]!!.jsonObject
        assertThat(proofJson["timestamp"]!!.jsonPrimitive.content).isEqualTo("1764424242")
        assertThat(proofJson["signature"]!!.jsonPrimitive.content).isEqualTo(Base64.getEncoder().encodeToString(ByteArray(64) { 1 }))
        assertThat(proofJson["domain"].toString()).isEqualTo("""{"lengthBytes":15,"value":"app.example.com"}""")
        assertThat(items[2].toString()).isEqualTo("""{"name":"ton_future","error":{"code":400,"message":"Unsupported connect item"}}""")
        val device = json["payload"]!!.jsonObject["device"]!!.jsonObject
        assertThat(device["platform"]!!.jsonPrimitive.content).isEqualTo("android")
        assertThat(device["maxProtocolVersion"]!!.jsonPrimitive.content).isEqualTo("2")
        val features = device["features"] as JsonArray
        assertThat(features[0].toString()).isEqualTo("""{"name":"SendTransaction","maxMessages":4,"extraCurrencySupported":false}""")
        assertThat(features[1].toString()).isEqualTo("""{"name":"SignData","types":["text","binary","cell"]}""")

        val error = TonConnectConnectEvent.ConnectError(3, TonConnectErrorCode.USER_DECLINED, "User declined the connection").toJson()
        assertThat(error.toString()).isEqualTo("""{"event":"connect_error","id":3,"payload":{"code":300,"message":"User declined the connection"}}""")
    }

    @Test
    fun `GIVEN wallet responses WHEN encoding THEN result and error shapes follow the spec`() {
        assertThat(TonConnectWalletResponse.Success("42", TonConnectResponseResult.SendTransaction("te6cc")).toJson().toString())
            .isEqualTo("""{"id":"42","result":"te6cc"}""")
        assertThat(TonConnectWalletResponse.Success("43", TonConnectResponseResult.SignMessage("te6cc")).toJson().toString())
            .isEqualTo("""{"id":"43","result":{"internalBoc":"te6cc"}}""")
        assertThat(TonConnectWalletResponse.Success("44", TonConnectResponseResult.Disconnect).toJson().toString())
            .isEqualTo("""{"id":"44","result":{}}""")
        assertThat(TonConnectWalletResponse.failure("46", TonConnectException.UserDeclined()).toJson().toString())
            .isEqualTo("""{"id":"46","error":{"code":300,"message":"User declined the request"}}""")
        assertThat(TonConnectDisconnectEvent(2).toJson().toString()).isEqualTo("""{"event":"disconnect","id":2,"payload":{}}""")
    }

    @Test
    fun `GIVEN exceptions WHEN mapping to protocol codes THEN the central catalogue is followed`() {
        assertThat(TonConnectException.BadRequest("x").protocolCode).isEqualTo(TonConnectErrorCode.BAD_REQUEST)
        assertThat(TonConnectException.ManifestNotFound().protocolCode.code).isEqualTo(2)
        assertThat(TonConnectException.ManifestContentError("x").protocolCode.code).isEqualTo(3)
        assertThat(TonConnectException.UnknownSession().protocolCode.code).isEqualTo(100)
        assertThat(TonConnectException.UserDeclined().protocolCode.code).isEqualTo(300)
        assertThat(TonConnectException.MethodNotSupported("x").protocolCode.code).isEqualTo(400)
        assertThat(TonConnectException.DecryptionFailed().protocolCode.code).isEqualTo(0)
        assertThat(TonConnectException.RequestIdNotIncreasing("1", "2").protocolCode.code).isEqualTo(1)
    }

    // endregion

    // region Connect event factory

    @Test
    fun `GIVEN request with all item kinds WHEN making approval THEN every item is answered`() = runTest {
        // Arrange
        val signer = FakeTonConnectSigner()
        val factory = TonConnectConnectEventFactory(deviceInfo, nowEpochSeconds = { Vectors.NOW })
        val request = TonConnectConnectRequest(
            manifestUrl = "https://app.example.com/tonconnect-manifest.json",
            items = listOf(
                TonConnectConnectItem.TonAddress(TonConnectNetworkId.MAINNET),
                TonConnectConnectItem.TonProof("nonce"),
                TonConnectConnectItem.Unsupported("ton_future"),
            ),
        )

        // Act
        val approval = factory.makeApproval(request, signer.publicKey, TonConnectNetworkId.MAINNET, "app.example.com", eventId = 0, signer)

        // Assert
        val expectedAddress = TonConnectTransferBuilder(signer.publicKey).address
        assertThat(approval.account.address).isEqualTo(expectedAddress.toRaw())
        assertThat(approval.account.publicKey).isEqualTo(signer.publicKey.toTonConnectHex())
        assertThat(approval.event.id).isEqualTo(0)
        assertThat(approval.event.device).isEqualTo(deviceInfo)
        assertThat(approval.event.items).hasSize(3)

        val addressReply = approval.event.items[0] as TonConnectConnectItemReply.TonAddress
        assertThat(addressReply.address).isEqualTo(expectedAddress.toRaw())
        val stateInit = BagOfCells(Base64.getDecoder().decode(addressReply.walletStateInit)).roots.single()
        assertThat(TonCells.hash(stateInit)).isEqualTo(expectedAddress.hash)

        val proofReply = approval.event.items[1] as TonConnectConnectItemReply.TonProof
        val digest = TonConnectProofMessage.digest(expectedAddress, "app.example.com", Vectors.NOW, "nonce")
        assertThat(signer.verify(proofReply.proof.signature, digest)).isTrue()
        assertThat(proofReply.proof.domain).isEqualTo("app.example.com")

        assertThat(approval.event.items[2])
            .isEqualTo(TonConnectConnectItemReply.Error("ton_future", TonConnectErrorCode.METHOD_NOT_SUPPORTED, "Unsupported connect item"))
    }

    @Test
    fun `GIVEN request without proof WHEN making approval THEN signer is not used and rejection is shaped`() = runTest {
        val signer = FakeTonConnectSigner()
        val factory = TonConnectConnectEventFactory(deviceInfo)
        val request = TonConnectConnectRequest("https://a.b/m.json", listOf(TonConnectConnectItem.TonAddress(null)))

        val approval = factory.makeApproval(request, signer.publicKey, TonConnectNetworkId.MAINNET, "a.b", eventId = 5, signer)

        assertThat(signer.signedDigests).isEmpty()
        assertThat(approval.event.id).isEqualTo(5)
        assertThat(approval.event.items).hasSize(1)
        assertThat(factory.makeRejection(6, TonConnectException.UserDeclined()))
            .isEqualTo(TonConnectConnectEvent.ConnectError(6, TonConnectErrorCode.USER_DECLINED, "User declined the request"))
    }

    // endregion
}
