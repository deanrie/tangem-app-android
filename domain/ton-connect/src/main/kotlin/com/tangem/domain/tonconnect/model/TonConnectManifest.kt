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

    companion object {

        /** Decodes a manifest body and applies the content rules of `spec/manifest.md`. */
        fun decode(body: String): TonConnectManifest {
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
            return labels.size >= 2 && labels.all { it.isNotEmpty() }
        }
    }
}
