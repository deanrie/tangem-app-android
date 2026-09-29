package com.tangem.domain.tonconnect

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.bridge.TonConnectSseEvent
import com.tangem.domain.tonconnect.bridge.TonConnectSseParser
import com.tangem.domain.tonconnect.deeplink.TonConnectDeepLink
import com.tangem.domain.tonconnect.deeplink.TonConnectDeepLinkParser
import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectConnectItem
import com.tangem.domain.tonconnect.model.TonConnectConnectRequest
import com.tangem.domain.tonconnect.model.TonConnectDeviceInfo
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectManifest
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.TonConnectSendTransactionPayload
import com.tangem.domain.tonconnect.model.TonConnectSignDataPayload
import com.tangem.domain.tonconnect.model.TonConnectSignDataType
import com.tangem.domain.tonconnect.session.TonConnectConnectEventFactory
import com.tangem.domain.tonconnect.transaction.TonCells
import com.tangem.domain.tonconnect.transaction.TonConnectBoc
import com.tangem.domain.tonconnect.transaction.TonConnectSendTransactionValidator
import com.tangem.domain.tonconnect.transaction.TonConnectSignDataValidator
import com.tangem.domain.tonconnect.transaction.TonConnectTransferBuilder
import com.tangem.domain.tonconnect.transaction.TonConnectWalletAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.ton.cell.Cell
import org.ton.cell.CellBuilder

/**
 * Rules added after the security pass over the module: every dApp- or bridge-controlled input that could steer a
 * signature to the wrong network/account, launch an arbitrary URL, or grow memory without bound.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectHardeningTest {

    private val wallet = TonAddress.parseRaw(Vectors.WALLET_ADDRESS_RAW)
    private val other = TonAddress.parseRaw(Vectors.DESTINATION_RAW)
    private val mainnetAccount = TonConnectWalletAccount(wallet, TonConnectNetworkId.MAINNET)
    private val allTypes = TonConnectSignDataType.entries.toSet()

    private fun badRequest(block: () -> Unit): String = assertThrows<TonConnectException.BadRequest>(block).protocolMessage

    // region signData validator

    @Test
    fun `GIVEN matching network and account WHEN validating signData THEN it passes`() {
        val validator = TonConnectSignDataValidator()

        validator.validate(
            TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Text("Sign in"), TonConnectNetworkId.MAINNET, wallet.toFriendly(false)),
            mainnetAccount,
            allTypes,
        )
        validator.validate(TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Binary(byteArrayOf(1)), null, null), mainnetAccount, setOf(TonConnectSignDataType.BINARY))
    }

    @Test
    fun `GIVEN foreign network account type or oversized payload WHEN validating signData THEN BadRequest is thrown`() {
        val validator = TonConnectSignDataValidator()
        fun text(network: TonConnectNetworkId?, from: String?, text: String = "x") =
            TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Text(text), network, from)

        assertThat(badRequest { validator.validate(text(TonConnectNetworkId.TESTNET, null), mainnetAccount, allTypes) })
            .isEqualTo("network -3 does not match the connected account network -239")
        assertThat(badRequest { validator.validate(text(null, other.toRaw()), mainnetAccount, allTypes) })
            .isEqualTo("from does not match the connected account")
        assertThat(badRequest { validator.validate(text(null, "nope"), mainnetAccount, allTypes) })
            .isEqualTo("from is not a valid TON address")
        assertThat(badRequest { validator.validate(TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Cell("x", "te6ccgEBAQEAAgAAAA=="), null, null), mainnetAccount, setOf(TonConnectSignDataType.TEXT)) })
            .isEqualTo("signData type cell is not supported by this wallet")
        assertThat(badRequest { validator.validate(text(null, null, "a".repeat(TonConnectSignDataValidator.MAX_PAYLOAD_BYTE_COUNT + 1)), mainnetAccount, allTypes) })
            .isEqualTo("text payload exceeds ${TonConnectSignDataValidator.MAX_PAYLOAD_BYTE_COUNT} bytes")
        assertThrows<TonConnectException.BadRequest> {
            validator.validate(TonConnectSignDataPayload(TonConnectSignDataPayload.Content.Cell("x", "not-a-boc"), null, null), mainnetAccount, allTypes)
        }
    }

    // endregion

    // region connect: requested network

    @Test
    fun `GIVEN ton_addr item pinned to another network WHEN making approval THEN BadRequest and nothing is signed`() = runTest {
        val signer = FakeTonConnectSigner()
        val factory = TonConnectConnectEventFactory(TonConnectDeviceInfo(TonConnectDeviceInfo.Platform.ANDROID, "tangem", "1", emptyList()))
        val request = TonConnectConnectRequest("https://a.b/m.json", listOf(TonConnectConnectItem.TonAddress(TonConnectNetworkId.TESTNET)))

        val error = assertThrows<TonConnectException.BadRequest> {
            factory.makeApproval(request, signer.publicKey, TonConnectNetworkId.MAINNET, "a.b", eventId = 0, signer)
        }

        assertThat(error.protocolMessage).isEqualTo("requested network -3 does not match the wallet network -239")
        assertThat(signer.signedDigests).isEmpty()
    }

    // endregion

    // region sendTransaction: test-only destination, hostile StateInit

    @Test
    fun `GIVEN test-only destination WHEN validating on mainnet THEN BadRequest but testnet accepts`() {
        val validator = TonConnectSendTransactionValidator(nowEpochSeconds = { Vectors.NOW })
        val payload = TonConnectSendTransactionPayload(null, null, null, listOf(TonConnectSendTransactionPayload.Message(other.toFriendly(bounceable = true, testOnly = true), "1")))

        assertThat(badRequest { validator.validate(payload, mainnetAccount) })
            .isEqualTo("messages[0].address is a test-only address, the connected account is on mainnet")
        validator.validate(payload, TonConnectWalletAccount(wallet, TonConnectNetworkId.TESTNET))
    }

    @Test
    fun `GIVEN StateInit with a library dictionary WHEN validating THEN it is refused without being parsed`() {
        val validator = TonConnectSendTransactionValidator(nowEpochSeconds = { Vectors.NOW })
        // library:(HashmapE 256 SimpleLib) present, with a label longer than the key — the input that makes
        // TonSwift's StateInit parser trap on iOS; here it must simply be rejected.
        val hostileLabel = CellBuilder.beginCell().storeBit(true).storeBit(false).storeUInt(511, 9).also { b -> repeat(511) { b.storeBit(true) } }.endCell()
        val stateInit = CellBuilder.beginCell().storeBits(false, false, false, false).storeBit(true).storeRef(hostileLabel).endCell()
        val payload = TonConnectSendTransactionPayload(null, null, null, listOf(TonConnectSendTransactionPayload.Message(Vectors.DESTINATION_BOUNCEABLE, "1", stateInit = TonCells.toBocBase64(stateInit))))

        assertThat(badRequest { validator.validate(payload, mainnetAccount) }).isEqualTo("messages[0].stateInit is not a valid StateInit cell")
        assertThat(TonConnectTransferBuilder.isForwardableStateInit(stateInit)).isFalse()
    }

    // endregion

    // region deep link: return targets and bad encoding

    @Test
    fun `GIVEN ret values WHEN parsing THEN only web and Telegram URLs are opened`() {
        val parser = TonConnectDeepLinkParser()
        val id = "ab".repeat(32)
        fun ret(value: String) = parser.parse("tc://?id=$id&ret=" + java.net.URLEncoder.encode(value, "UTF-8")).returnStrategy

        assertThat(ret("https://dapp.example/done")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Url("https://dapp.example/done"))
        assertThat(ret("tg://resolve?domain=dapp")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Url("tg://resolve?domain=dapp"))
        assertThat(ret("tel:+123456")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
        assertThat(ret("sms:123")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
        assertThat(ret("tonkeeper://transfer/x")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
        assertThat(ret("file:///etc/passwd")).isEqualTo(TonConnectDeepLink.ReturnStrategy.Back)
    }

    @Test
    fun `GIVEN malformed percent-encoding WHEN parsing THEN MalformedConnectRequest instead of a crash`() {
        assertThrows<TonConnectException.MalformedConnectRequest> { TonConnectDeepLinkParser().parse("tc://?id=%zz&ret=back") }
    }

    // endregion

    // region SSE memory bound

    @Test
    fun `GIVEN event larger than the cap WHEN feeding parser THEN it is dropped and parser recovers`() {
        val parser = TonConnectSseParser()
        val chunk = "x".repeat(256 * 1024)

        repeat(5) { assertThat(parser.feed("data: $chunk")).isNull() } // 5 × 256 KiB > 1 MiB
        assertThat(parser.feed("")).isNull()

        assertThat(parser.feed("data: ok")).isNull()
        assertThat(parser.feed("")).isEqualTo(TonConnectSseEvent(null, null, "ok"))
    }

    // endregion

    // region manifest: size, serving host, IP literals

    @Test
    fun `GIVEN oversized manifest WHEN decoding THEN ManifestContentError is thrown`() {
        val body = """{"url":"https://a.b","name":"x","iconUrl":"https://a.b/i.png"}""" + " ".repeat(TonConnectManifest.MAX_BYTE_COUNT)

        val error = assertThrows<TonConnectException.ManifestContentError> { TonConnectManifest.decode(body) }

        assertThat(error.protocolMessage).endsWith("manifest exceeds ${TonConnectManifest.MAX_BYTE_COUNT} bytes")
    }

    @Test
    fun `GIVEN manifest WHEN checking serving host THEN only the claimed domain and its subdomains match`() {
        val manifest = TonConnectManifest("https://app.example.com", "x", "https://a.b/i.png")

        assertThat(manifest.isServedFromAppDomain("https://app.example.com/tonconnect-manifest.json")).isTrue()
        assertThat(manifest.isServedFromAppDomain("https://cdn.app.example.com/m.json")).isTrue()
        assertThat(manifest.isServedFromAppDomain("https://raw.githubusercontent.com/x/m.json")).isFalse()
        assertThat(manifest.isServedFromAppDomain("https://evilapp.example.com/m.json")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.2.3.4", "127.0.0.1"])
    fun `GIVEN IPv4 literal WHEN validating app domain THEN it is rejected`(host: String) {
        assertThat(TonConnectManifest.isValidAppDomain(host)).isFalse()
    }

    // endregion

    // region BoC depth

    @Test
    fun `GIVEN cell chain deeper than the cap WHEN preflighting THEN TOO_DEEP is reported`() {
        fun chain(depth: Int): ByteArray {
            var cell: Cell = CellBuilder.beginCell().storeUInt(1, 8).endCell()
            repeat(depth) { cell = CellBuilder.beginCell().storeRef(cell).endCell() }
            return TonCells.toBoc(cell)
        }

        TonConnectBoc.preflight(chain(TonConnectBoc.MAX_DEPTH))
        val error = assertThrows<TonConnectBoc.PreflightException> { TonConnectBoc.preflight(chain(TonConnectBoc.MAX_DEPTH + 1)) }
        assertThat(error.reason).isEqualTo(TonConnectBoc.PreflightReason.TOO_DEEP)
    }

    // endregion
}
