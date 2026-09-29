package com.tangem.domain.tonconnect.model

import com.tangem.domain.tonconnect.model.TonConnectJson.string
import java.net.URI

/** `tonconnect-manifest.json` (`spec/manifest.md`). Unknown top-level fields are ignored by design. */
data class TonConnectManifest(
    val url: String,
    val name: String,
    val iconUrl: String,
    val termsOfUseUrl: String? = null,
    val privacyPolicyUrl: String? = null,
) {

    /**
     * `AppDomain` used in `ton_proof` and `signData` signatures: the host of [url].
     *
     * Per the domain-binding rules the host must contain at least one `.` with non-empty labels on both sides;
     * bare names (`tonkeeper`, `localhost`) are reserved for native integrations and rejected.
     */
    fun appDomain(): String {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw TonConnectException.ManifestContentError("url is not a valid URL")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw TonConnectException.ManifestContentError("url must use https")
        }
        val host = uri.host?.lowercase()
        if (host == null || !isValidAppDomain(host)) {
            throw TonConnectException.ManifestContentError("url host is not a valid dApp domain")
        }
        return host
    }

    /**
     * `true` when the manifest was fetched from the domain it claims (`manifestUrl` host equals `url` host or is a
     * subdomain of it).
     *
     * The spec lets a dApp host its manifest anywhere, and CDN / GitHub-hosted manifests are common, so a mismatch is
     * not an error. It is, however, the exact shape of a `ton_proof` phishing attempt: a manifest served from
     * `attacker.example` that claims `url: https://real-dapp.example` makes the wallet sign a login proof for the real
     * dApp's domain with the attacker's nonce. The UI should show the serving host prominently (and warn) when this
     * returns `false`.
     */
    fun isServedFromAppDomain(manifestUrl: String): Boolean {
        val appDomain = runCatching { appDomain() }.getOrNull() ?: return false
        val servingHost = runCatching { URI(manifestUrl).host }.getOrNull()?.lowercase() ?: return false
        return servingHost == appDomain || servingHost.endsWith(".$appDomain")
    }

    companion object {

        /** Upper bound on a manifest body; keeps a hostile `manifestUrl` from feeding the JSON parser megabytes. */
        const val MAX_BYTE_COUNT: Int = 64 * 1024

        /** Decodes a manifest body and applies the content rules of `spec/manifest.md`. */
        fun decode(body: String): TonConnectManifest {
            if (body.length > MAX_BYTE_COUNT) {
                throw TonConnectException.ManifestContentError("manifest exceeds $MAX_BYTE_COUNT bytes")
            }

            val json = TonConnectJson.parseObjectOrNull(body)
                ?: throw TonConnectException.ManifestContentError("not a valid manifest JSON")

            val manifest = TonConnectManifest(
                url = json.string("url") ?: throw TonConnectException.ManifestContentError("url is missing"),
                name = json.string("name") ?: throw TonConnectException.ManifestContentError("name is missing"),
                iconUrl = json.string("iconUrl") ?: throw TonConnectException.ManifestContentError("iconUrl is missing"),
                termsOfUseUrl = json.string("termsOfUseUrl"),
                privacyPolicyUrl = json.string("privacyPolicyUrl"),
            )

            manifest.appDomain()

            if (manifest.name.isBlank()) throw TonConnectException.ManifestContentError("name is empty")

            val iconScheme = runCatching { URI(manifest.iconUrl).scheme }.getOrNull()
            if (!iconScheme.equals("https", ignoreCase = true)) {
                throw TonConnectException.ManifestContentError("iconUrl must use https")
            }

            return manifest
        }

        internal fun isValidAppDomain(host: String): Boolean {
            val labels = host.split('.')
            if (labels.size < 2 || labels.any { it.isEmpty() }) return false
            // A dotted IPv4 literal is not a domain name.
            return !labels.all { label -> label.all(Char::isDigit) }
        }
    }
}
