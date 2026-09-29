package com.tangem.domain.tonconnect.model

import com.tangem.domain.tonconnect.model.TonConnectJson.string
import com.tangem.domain.tonconnect.model.TonConnectJson.stringOrNumber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** `params[0]` of `signData` (`spec/rpc.md`). Echoed back verbatim inside the response. */
data class TonConnectSignDataPayload(
    val content: Content,
    val network: TonConnectNetworkId?,
    val from: String?,
) {

    sealed class Content {
        abstract val type: TonConnectSignDataType

        data class Text(val text: String) : Content() {
            override val type: TonConnectSignDataType get() = TonConnectSignDataType.TEXT
        }

        /** Raw bytes, transported as standard base64. */
        class Binary(val bytes: ByteArray) : Content() {
            override val type: TonConnectSignDataType get() = TonConnectSignDataType.BINARY
            override fun equals(other: Any?): Boolean = other is Binary && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** [cellBoc] is a base64 single-root BoC; [schema] is its TL-B description. */
        data class Cell(val schema: String, val cellBoc: String) : Content() {
            override val type: TonConnectSignDataType get() = TonConnectSignDataType.CELL
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("type", content.type.wireName)
        when (val c = content) {
            is Content.Text -> put("text", c.text)
            is Content.Binary -> put("bytes", Base64.getEncoder().encodeToString(c.bytes))
            is Content.Cell -> {
                put("schema", c.schema)
                put("cell", c.cellBoc)
            }
        }
        network?.let { put("network", it.value) }
        from?.let { put("from", it) }
    }

    companion object {
        fun decode(jsonText: String): TonConnectSignDataPayload {
            val json = TonConnectJson.parseObjectOrNull(jsonText) ?: throw malformed()
            val type = json.string("type")?.let(TonConnectSignDataType::fromWireName) ?: throw malformed()

            val content = when (type) {
                TonConnectSignDataType.TEXT -> Content.Text(json.string("text") ?: throw malformed())
                TonConnectSignDataType.BINARY -> {
                    val base64 = json.string("bytes") ?: throw malformed()
                    val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: throw malformed()
                    Content.Binary(bytes)
                }
                TonConnectSignDataType.CELL -> Content.Cell(
                    schema = json.string("schema") ?: throw malformed(),
                    cellBoc = json.string("cell") ?: throw malformed(),
                )
            }

            return TonConnectSignDataPayload(
                content = content,
                network = json.stringOrNumber("network")?.let(::TonConnectNetworkId),
                from = json.string("from"),
            )
        }

        private fun malformed() = TonConnectException.BadRequest("signData payload is malformed")
    }
}
