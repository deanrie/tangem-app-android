package com.tangem.domain.tonconnect.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin helpers over kotlinx-serialization's tree API. The protocol has several untagged unions
 * (`ConnectItem`, `WalletResponse`, `signData` payloads), so messages are (de)serialised by hand.
 */
internal object TonConnectJson {

    val json: Json = Json { ignoreUnknownKeys = true }

    fun parseObjectOrNull(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    /** String value, or the decimal form of a number (several SDKs emit ids and network ids as numbers). */
    fun JsonObject.stringOrNumber(key: String): String? {
        val primitive = (this[key] as? JsonPrimitive) ?: return null
        return primitive.contentOrNullSafe()
    }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonElement.contentOrNullSafe(): String? = runCatching { jsonPrimitive.content }.getOrNull()
}
