package com.tangem.domain.tonconnect.deeplink

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectConnectRequest
import com.tangem.domain.tonconnect.model.TonConnectDeviceInfo
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectJson
import java.net.URI
import java.net.URLDecoder

/**
 * A parsed TON Connect link (`spec/deeplinks.md`): unified `tc://`, the wallet's universal link or a wallet-specific
 * custom scheme all carry the same query parameters.
 */
data class TonConnectDeepLink(
    val protocolVersion: Int,
    /** The dApp's session public key. */
    val dAppClientId: TonConnectClientId,
    /** `null` for the "empty" form `?id=…&ret=…` that only carries a return target. */
    val connectRequest: TonConnectConnectRequest?,
    val returnStrategy: ReturnStrategy,
    /** Analytics correlation id; reuse it when posting the connect event. */
    val traceId: String?,
) {

    /** Where to send the user after the request is approved or declined. */
    sealed class ReturnStrategy {
        /** Return to the app that opened the link (default). */
        data object Back : ReturnStrategy()

        /** Stay in the wallet. */
        data object None : ReturnStrategy()

        /** Open the given URL. */
        data class Url(val url: String) : ReturnStrategy()
    }
}

/**
 * Parses `tc://…`, universal-link and custom-scheme TON Connect URLs.
 *
 * The parser only looks at the query string, so the caller decides which URLs are routed here (the `tc` scheme,
 * the wallet's own scheme, the `/ton-connect` universal-link path). Embedded requests (`e`) are ignored: this
 * wallet does not advertise the `EmbeddedRequest` feature.
 */
class TonConnectDeepLinkParser {

    fun isUnifiedLink(url: String): Boolean = schemeOf(url)?.lowercase() == UNIFIED_SCHEME

    @Suppress("ThrowsCount")
    fun parse(url: String): TonConnectDeepLink {
        val query = parseQuery(rawQueryOf(url))

        val clientIdHex = query[PARAM_CLIENT_ID] ?: throw TonConnectException.MalformedConnectRequest("missing id")
        val dAppClientId = TonConnectClientId.fromHex(clientIdHex)

        val connectRequest = query[PARAM_REQUEST]?.let(::decodeConnectRequest)

        val rawVersion = query[PARAM_VERSION]
        val protocolVersion = if (connectRequest != null) {
            // `v` is mandatory on a connect link; the "empty" return-only link may omit it.
            rawVersion?.toIntOrNull()?.takeIf { it == SUPPORTED_PROTOCOL_VERSION }
                ?: throw TonConnectException.UnsupportedProtocolVersion(rawVersion)
        } else {
            rawVersion?.toIntOrNull() ?: SUPPORTED_PROTOCOL_VERSION
        }

        return TonConnectDeepLink(
            protocolVersion = protocolVersion,
            dAppClientId = dAppClientId,
            connectRequest = connectRequest,
            returnStrategy = parseReturnStrategy(query[PARAM_RETURN]),
            traceId = query[PARAM_TRACE_ID],
        )
    }

    private fun decodeConnectRequest(json: String): TonConnectConnectRequest {
        val request = TonConnectJson.parseObjectOrNull(json)?.let(TonConnectConnectRequest::fromJson)
            ?: throw TonConnectException.MalformedConnectRequest("r is not a valid ConnectRequest")

        val manifestScheme = runCatching { URI(request.manifestUrl).scheme }.getOrNull()
        if (!manifestScheme.equals("https", ignoreCase = true)) {
            throw TonConnectException.MalformedConnectRequest("manifestUrl must use https")
        }

        if (!request.requestsAddress) throw TonConnectException.MalformedConnectRequest("ton_addr item is required")

        return request
    }

    private fun parseReturnStrategy(raw: String?): TonConnectDeepLink.ReturnStrategy = when (raw) {
        null, "back" -> TonConnectDeepLink.ReturnStrategy.Back
        "none" -> TonConnectDeepLink.ReturnStrategy.None
        else -> {
            val scheme = runCatching { URI(raw).scheme }.getOrNull()
            if (scheme != null) TonConnectDeepLink.ReturnStrategy.Url(raw) else TonConnectDeepLink.ReturnStrategy.Back
        }
    }

    /** Scheme without relying on `java.net.URI`, which rejects links whose `r` was not percent-encoded. */
    private fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        return scheme.takeIf { it.all { c -> c.isLetterOrDigit() || c == '+' || c == '-' || c == '.' } }
    }

    private fun rawQueryOf(url: String): String? {
        val start = url.indexOf('?')
        if (start < 0) return null
        val end = url.indexOf('#', startIndex = start).let { if (it < 0) url.length else it }
        return url.substring(start + 1, end)
    }

    /** RFC 3986 query parsing: percent-decoding only, `+` is a literal (dApps use `encodeURIComponent`). */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val key = decode(if (separator < 0) pair else pair.substring(0, separator))
            val value = if (separator < 0) "" else decode(pair.substring(separator + 1))
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decode(component: String): String =
        URLDecoder.decode(component.replace("+", "%2B"), Charsets.UTF_8.name())

    companion object {
        const val UNIFIED_SCHEME = "tc"
        const val SUPPORTED_PROTOCOL_VERSION = TonConnectDeviceInfo.SUPPORTED_PROTOCOL_VERSION

        private const val PARAM_VERSION = "v"
        private const val PARAM_CLIENT_ID = "id"
        private const val PARAM_REQUEST = "r"
        private const val PARAM_RETURN = "ret"
        private const val PARAM_TRACE_ID = "trace_id"
    }
}
