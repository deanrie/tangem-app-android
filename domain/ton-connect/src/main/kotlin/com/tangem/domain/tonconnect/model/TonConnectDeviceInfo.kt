package com.tangem.domain.tonconnect.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `DeviceInfo` returned inside every successful `ConnectEvent` (`spec/connect.md`). */
data class TonConnectDeviceInfo(
    val platform: Platform,
    /** Must equal the wallet's `app_name` entry in the public wallets list. */
    val appName: String,
    val appVersion: String,
    val features: List<TonConnectFeature>,
    val maxProtocolVersion: Int = SUPPORTED_PROTOCOL_VERSION,
) {

    enum class Platform(val wireName: String) {
        IPHONE("iphone"),
        IPAD("ipad"),
        ANDROID("android"),
        WINDOWS("windows"),
        MAC("mac"),
        LINUX("linux"),
        BROWSER("browser"),
    }

    val sendTransactionFeature: TonConnectFeature.SendTransaction?
        get() = features.filterIsInstance<TonConnectFeature.SendTransaction>().firstOrNull()

    val signDataTypes: Set<TonConnectSignDataType>
        get() = features.filterIsInstance<TonConnectFeature.SignData>().firstOrNull()?.types?.toSet().orEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("platform", platform.wireName)
        put("appName", appName)
        put("appVersion", appVersion)
        put("maxProtocolVersion", maxProtocolVersion)
        put("features", buildJsonArray { features.forEach { add(it.toJson()) } })
    }

    companion object {
        const val SUPPORTED_PROTOCOL_VERSION = 2
    }
}

/** Capabilities the wallet advertises. Only advertise what is enforced at runtime. */
sealed class TonConnectFeature {

    abstract fun toJson(): JsonObject

    data class SendTransaction(
        val maxMessages: Int,
        val extraCurrencySupported: Boolean = false,
        /** Structured `items` support; `null` means only raw `messages` are accepted. */
        val itemTypes: List<String>? = null,
    ) : TonConnectFeature() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("name", "SendTransaction")
            put("maxMessages", maxMessages)
            put("extraCurrencySupported", extraCurrencySupported)
            itemTypes?.let { types -> put("itemTypes", buildJsonArray { types.forEach { add(it) } }) }
        }
    }

    data class SignData(val types: List<TonConnectSignDataType>) : TonConnectFeature() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("name", "SignData")
            put("types", buildJsonArray { types.forEach { add(it.wireName) } })
        }
    }
}

enum class TonConnectSignDataType(val wireName: String) {
    TEXT("text"),
    BINARY("binary"),
    CELL("cell"),
    ;

    companion object {
        fun fromWireName(name: String): TonConnectSignDataType? = entries.firstOrNull { it.wireName == name }
    }
}
