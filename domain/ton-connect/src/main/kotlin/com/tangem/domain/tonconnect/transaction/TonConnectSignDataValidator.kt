package com.tangem.domain.tonconnect.transaction

import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectSignDataPayload
import com.tangem.domain.tonconnect.model.TonConnectSignDataType

/**
 * Wallet-side rules for `signData` (`spec/rpc.md` § signData): the request must target the connected account and
 * its network, and the wallet must have advertised the payload type.
 *
 * Without this check a dApp could obtain a signature bound to another network (`-3` vs `-239`) or ask the wallet to
 * sign "for" an address it did not connect — the digest only binds what the wallet puts into it.
 */
class TonConnectSignDataValidator {

    @Suppress("ThrowsCount")
    fun validate(
        payload: TonConnectSignDataPayload,
        account: TonConnectWalletAccount,
        supportedTypes: Set<TonConnectSignDataType>,
    ) {
        if (payload.content.type !in supportedTypes) {
            throw TonConnectException.BadRequest("signData type ${payload.content.type.wireName} is not supported by this wallet")
        }

        payload.network?.let { network ->
            if (network != account.network) {
                throw TonConnectException.BadRequest(
                    "network $network does not match the connected account network ${account.network}",
                )
            }
        }

        payload.from?.let { from ->
            val fromAddress = runCatching { TonAddress.parse(from) }.getOrNull()
                ?: throw TonConnectException.BadRequest("from is not a valid TON address")
            if (fromAddress != account.address) {
                throw TonConnectException.BadRequest("from does not match the connected account")
            }
        }

        when (val content = payload.content) {
            is TonConnectSignDataPayload.Content.Text -> {
                if (content.text.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTE_COUNT) {
                    throw TonConnectException.BadRequest("text payload exceeds $MAX_PAYLOAD_BYTE_COUNT bytes")
                }
            }
            is TonConnectSignDataPayload.Content.Binary -> {
                if (content.bytes.size > MAX_PAYLOAD_BYTE_COUNT) {
                    throw TonConnectException.BadRequest("binary payload exceeds $MAX_PAYLOAD_BYTE_COUNT bytes")
                }
            }
            is TonConnectSignDataPayload.Content.Cell -> {
                // Parsed (and structurally preflighted) once here so a malformed cell is a clean BAD_REQUEST before
                // any UI is shown; the digest builder parses it again.
                TonConnectBoc.singleRootCell(content.cellBoc, field = "cell")
            }
        }
    }

    companion object {
        /** Upper bound on `text` / `binary` payload size; long content cannot be reviewed on a phone screen. */
        const val MAX_PAYLOAD_BYTE_COUNT: Int = 64 * 1024
    }
}
