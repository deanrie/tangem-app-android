package com.tangem.domain.tonconnect.model

import com.tangem.domain.tonconnect.model.TonConnectJson.string
import com.tangem.domain.tonconnect.model.TonConnectJson.stringOrNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** `ConnectRequest` — the JSON carried in the `r` parameter of a TON Connect link (`spec/connect.md`). */
data class TonConnectConnectRequest(
    val manifestUrl: String,
    val items: List<TonConnectConnectItem>,
) {

    val requestsProof: Boolean get() = items.any { it is TonConnectConnectItem.TonProof }

    val proofPayload: String? get() = items.filterIsInstance<TonConnectConnectItem.TonProof>().firstOrNull()?.payload

    val requestsAddress: Boolean get() = items.any { it is TonConnectConnectItem.TonAddress }

    companion object {
        fun fromJson(json: JsonObject): TonConnectConnectRequest? {
            val manifestUrl = json.string("manifestUrl") ?: return null
            val items = (json["items"] as? JsonArray) ?: return null
            return TonConnectConnectRequest(
                manifestUrl = manifestUrl,
                items = items.map { element ->
                    val item = element as? JsonObject ?: return null
                    TonConnectConnectItem.fromJson(item) ?: return null
                },
            )
        }
    }
}

/**
 * A data item the dApp asks the wallet to share. Unknown item names are preserved so the wallet can answer them
 * with the per-item `METHOD_NOT_SUPPORTED` (400) error the spec requires.
 */
sealed class TonConnectConnectItem {

    abstract val name: String

    data class TonAddress(val network: TonConnectNetworkId?) : TonConnectConnectItem() {
        override val name: String get() = TON_ADDRESS_NAME
    }

    data class TonProof(val payload: String) : TonConnectConnectItem() {
        override val name: String get() = TON_PROOF_NAME
    }

    data class Unsupported(override val name: String) : TonConnectConnectItem()

    companion object {
        const val TON_ADDRESS_NAME = "ton_addr"
        const val TON_PROOF_NAME = "ton_proof"

        fun fromJson(json: JsonObject): TonConnectConnectItem? {
            return when (val name = json.string("name") ?: return null) {
                TON_ADDRESS_NAME -> TonAddress(network = json.stringOrNumber("network")?.let(::TonConnectNetworkId))
                TON_PROOF_NAME -> TonProof(payload = json.string("payload") ?: return null)
                else -> Unsupported(name)
            }
        }
    }
}
