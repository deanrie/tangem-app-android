package com.tangem.domain.tonconnect.model

import com.tangem.domain.tonconnect.model.TonConnectJson.string
import com.tangem.domain.tonconnect.model.TonConnectJson.stringOrNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire shape of `params[0]` for `sendTransaction` and `signMessage` (`spec/rpc.md`), before validation.
 *
 * Only presence of `items` is recorded: structured items are not supported by this wallet, and a payload
 * carrying them is rejected with `BAD_REQUEST` by `TonConnectSendTransactionValidator`.
 */
data class TonConnectSendTransactionPayload(
    val validUntil: Long?,
    val network: TonConnectNetworkId?,
    val from: String?,
    val messages: List<Message>?,
    val hasItems: Boolean = false,
) {

    data class Message(
        /** Destination in user-friendly form (raw `wc:hex` form must be rejected). */
        val address: String,
        /** Nanocoins as a decimal string. */
        val amount: String,
        /** Base64 single-root BoC. */
        val payload: String? = null,
        /** Base64 single-root BoC. */
        val stateInit: String? = null,
        val extraCurrency: Map<String, String>? = null,
    ) {
        companion object {
            internal fun fromJson(json: JsonObject): Message? {
                val address = json.string("address") ?: return null
                val amount = json.stringOrNumber("amount") ?: return null
                val extraCurrency = (json["extra_currency"] as? JsonObject)?.mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.content ?: return null
                }
                return Message(
                    address = address,
                    amount = amount,
                    payload = json.string("payload"),
                    stateInit = json.string("stateInit"),
                    extraCurrency = extraCurrency,
                )
            }
        }
    }

    companion object {
        fun decode(jsonText: String): TonConnectSendTransactionPayload {
            val json = TonConnectJson.parseObjectOrNull(jsonText)
                ?: throw TonConnectException.BadRequest("transaction payload is not valid JSON")

            val validUntil = json.stringOrNumber("valid_until")?.let { raw ->
                raw.toLongOrNull() ?: throw TonConnectException.BadRequest("valid_until is not an integer")
            }

            val messages = json["messages"]?.let { element ->
                val array = element as? JsonArray
                    ?: throw TonConnectException.BadRequest("messages must be an array")
                array.map { item ->
                    (item as? JsonObject)?.let(Message::fromJson)
                        ?: throw TonConnectException.BadRequest("messages contain a malformed entry")
                }
            }

            val hasItems = when (val items = json["items"]) {
                null -> false
                is JsonArray -> true
                else -> throw TonConnectException.BadRequest("items must be an array")
            }

            return TonConnectSendTransactionPayload(
                validUntil = validUntil,
                network = json.stringOrNumber("network")?.let(::TonConnectNetworkId),
                from = json.string("from"),
                messages = messages,
                hasItems = hasItems,
            )
        }
    }
}
