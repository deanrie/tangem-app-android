package com.tangem.domain.tonconnect.session

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectManifest
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import java.math.BigInteger

/**
 * Persistent record of one approved dApp connection.
 *
 * Holds everything except the wallet's session *secret* key, which the app keeps in encrypted storage under [id]
 * and hands to `TonConnectSessionCrypto` when a message needs to be opened or sealed. Immutable: the id-tracking
 * operations return an updated copy the caller persists.
 */
data class TonConnectSession(
    val id: String,
    val dAppClientId: TonConnectClientId,
    /** The wallet's session public key — what the dApp encrypts to. */
    val walletClientId: TonConnectClientId,
    val bridgeUrl: String,
    val manifest: TonConnectManifest,
    /** Host of `manifest.url`, bound into every `ton_proof` / `signData` signature. */
    val appDomain: String,
    val account: Account,
    val createdAtEpochSeconds: Long,
    /** Last processed `AppRequest.id`; subsequent ids must be strictly greater. */
    val lastRequestId: String? = null,
    /** Counter for wallet-emitted events (`connect`, `disconnect`). */
    val nextEventId: Int = 0,
    /** Last SSE event id seen on the bridge, for `last_event_id` on reconnect. */
    val lastBridgeEventId: String? = null,
) {

    /** The account exposed to the dApp; fixed for the lifetime of the session. */
    data class Account(
        /** Raw form `0:<hex>`. */
        val address: String,
        val network: TonConnectNetworkId,
        /** Hex without `0x`. */
        val publicKey: String,
    )

    /**
     * Enforces the per-session monotonic request id rule (`spec/rpc.md` § AppRequest).
     *
     * The first id is accepted as the baseline; every later id must be strictly greater. Ids are compared as
     * unsigned integers — the reference SDK uses millisecond timestamps.
     */
    fun acceptingRequest(requestId: String): TonConnectSession {
        val incoming = requestId.toUnsignedBigIntegerOrNull()
            ?: throw TonConnectException.BadRequest("request id must be a non-negative integer")

        val last = lastRequestId?.toUnsignedBigIntegerOrNull()
        if (last != null && incoming <= last) {
            throw TonConnectException.RequestIdNotIncreasing(received = requestId, last = lastRequestId)
        }

        return copy(lastRequestId = requestId)
    }

    /** Allocates the id for the next wallet event and returns it together with the advanced session. */
    fun allocatingEventId(): Pair<Int, TonConnectSession> = nextEventId to copy(nextEventId = nextEventId + 1)

    private fun String.toUnsignedBigIntegerOrNull(): BigInteger? =
        takeIf { it.isNotEmpty() && it.length <= MAX_REQUEST_ID_LENGTH && it.all(Char::isDigit) }?.toBigIntegerOrNull()

    companion object {
        /** Longest request id accepted (the reference SDK sends 13-digit millisecond timestamps). */
        const val MAX_REQUEST_ID_LENGTH: Int = 64
    }
}
