package com.tangem.domain.tonconnect.session

import com.tangem.domain.tonconnect.crypto.TonConnectProofMessage
import com.tangem.domain.tonconnect.crypto.TonConnectSigner
import com.tangem.domain.tonconnect.model.TonConnectConnectEvent
import com.tangem.domain.tonconnect.model.TonConnectConnectItem
import com.tangem.domain.tonconnect.model.TonConnectConnectItemReply
import com.tangem.domain.tonconnect.model.TonConnectConnectRequest
import com.tangem.domain.tonconnect.model.TonConnectDeviceInfo
import com.tangem.domain.tonconnect.model.TonConnectErrorCode
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.toTonConnectHex
import com.tangem.domain.tonconnect.transaction.TonConnectTransferBuilder

/**
 * Produces the `ConnectEvent` the wallet sends after the user approved a connection.
 *
 * Answers every requested item: `ton_addr` with the account data, `ton_proof` with a signature made through
 * [TonConnectSigner], anything else with the per-item error 400 the spec mandates.
 */
class TonConnectConnectEventFactory(
    private val deviceInfo: TonConnectDeviceInfo,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {

    data class Approval(val event: TonConnectConnectEvent.Connect, val account: TonConnectSession.Account)

    /**
     * @param publicKey the account's Ed25519 public key (wallet v4R2 is derived from it).
     * @param appDomain validated host of the manifest `url`.
     * @param eventId value from [TonConnectSession.allocatingEventId] (0 for the connect event of a new session).
     */
    suspend fun makeApproval(
        request: TonConnectConnectRequest,
        publicKey: ByteArray,
        network: TonConnectNetworkId,
        appDomain: String,
        eventId: Int,
        signer: TonConnectSigner,
    ): Approval {
        val transferBuilder = TonConnectTransferBuilder(publicKey)
        val address = transferBuilder.address

        val account = TonConnectSession.Account(
            address = address.toRaw(),
            network = network,
            publicKey = publicKey.toTonConnectHex(),
        )

        val replies = request.items.map { item ->
            when (item) {
                is TonConnectConnectItem.TonAddress -> TonConnectConnectItemReply.TonAddress(
                    address = account.address,
                    network = network,
                    publicKey = account.publicKey,
                    walletStateInit = transferBuilder.stateInitBoc(),
                )
                is TonConnectConnectItem.TonProof -> TonConnectConnectItemReply.TonProof(
                    TonConnectProofMessage.makeProof(
                        address = address,
                        appDomain = appDomain,
                        payload = item.payload,
                        timestamp = nowEpochSeconds(),
                        signer = signer,
                    ),
                )
                is TonConnectConnectItem.Unsupported -> TonConnectConnectItemReply.Error(
                    name = item.name,
                    code = TonConnectErrorCode.METHOD_NOT_SUPPORTED,
                    message = "Unsupported connect item",
                )
            }
        }

        return Approval(
            event = TonConnectConnectEvent.Connect(id = eventId, items = replies, device = deviceInfo),
            account = account,
        )
    }

    /** The `connect_error` event for a declined or failed connection. */
    fun makeRejection(eventId: Int, error: TonConnectException): TonConnectConnectEvent.ConnectError =
        TonConnectConnectEvent.ConnectError(id = eventId, code = error.protocolCode, message = error.protocolMessage)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
