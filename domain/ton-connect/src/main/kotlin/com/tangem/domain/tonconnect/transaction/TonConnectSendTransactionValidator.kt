package com.tangem.domain.tonconnect.transaction

import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import com.tangem.domain.tonconnect.model.TonConnectSendTransactionPayload
import org.ton.cell.Cell
import java.math.BigInteger

/** The account a session is bound to, as seen by the validator. */
data class TonConnectWalletAccount(
    val address: TonAddress,
    val network: TonConnectNetworkId,
    /** Maximum outgoing messages the wallet contract accepts in one transfer (4 for wallet v4). */
    val maxMessages: Int = TonConnectSendTransactionValidator.WALLET_V4_MAX_MESSAGES,
)

/** A `sendTransaction` / `signMessage` payload that passed every rule of `guides/send-transaction.md`. */
data class TonConnectValidatedTransaction(
    /** Unix seconds after which the request must not be signed; `null` when the dApp did not set it. */
    val validUntil: Long?,
    val messages: List<Message>,
) {

    data class Message(
        val destination: TonAddress,
        /** Extracted from the user-friendly address flag, as the spec requires. */
        val bounce: Boolean,
        /** Nanocoins. */
        val amount: BigInteger,
        val payload: Cell?,
        val stateInit: Cell?,
    )

    val totalAmount: BigInteger get() = messages.fold(BigInteger.ZERO) { acc, message -> acc + message.amount }
}

/**
 * Applies the wallet-side validation rules for `sendTransaction` and `signMessage` (`spec/rpc.md`,
 * `guides/send-transaction.md`). Every rejection is a [TonConnectException.BadRequest] (code 1) with a message
 * the dApp developer can act on.
 */
class TonConnectSendTransactionValidator(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {

    @Suppress("ThrowsCount")
    fun validate(
        payload: TonConnectSendTransactionPayload,
        account: TonConnectWalletAccount,
    ): TonConnectValidatedTransaction {
        if (payload.hasItems) {
            if (payload.messages != null) {
                throw TonConnectException.BadRequest("payload must contain either messages or items, not both")
            }
            throw TonConnectException.BadRequest("structured items are not supported by this wallet; send raw messages")
        }

        val messages = payload.messages ?: throw TonConnectException.BadRequest("payload must contain messages")

        val validUntil = validateValidUntil(payload.validUntil)

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

        if (messages.isEmpty()) throw TonConnectException.BadRequest("messages must not be empty")

        if (messages.size > account.maxMessages) {
            throw TonConnectException.BadRequest(
                "too many messages: ${messages.size}, the wallet supports at most ${account.maxMessages}",
            )
        }

        return TonConnectValidatedTransaction(
            validUntil = validUntil,
            messages = messages.mapIndexed { index, message -> validate(message, index) },
        )
    }

    private fun validateValidUntil(raw: Long?): Long? {
        if (raw == null) return null
        if (raw <= 0) throw TonConnectException.BadRequest("valid_until must be a positive unix timestamp")
        if (raw <= nowEpochSeconds()) {
            throw TonConnectException.BadRequest("request expired: valid_until $raw is in the past")
        }
        return raw
    }

    @Suppress("ThrowsCount")
    private fun validate(message: TonConnectSendTransactionPayload.Message, index: Int): TonConnectValidatedTransaction.Message {
        val field = "messages[$index]"

        // The spec requires the user-friendly form: it carries the bounce flag the wallet must honour.
        if (message.address.contains(':')) {
            throw TonConnectException.BadRequest("$field.address must be in user-friendly format, raw addresses are not allowed")
        }

        val friendly = runCatching { TonAddress.parseFriendly(message.address) }.getOrNull()
            ?: throw TonConnectException.BadRequest("$field.address is not a valid TON address")

        val amount = message.amount.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigIntegerOrNull()
            ?: throw TonConnectException.BadRequest("$field.amount must be a non-negative decimal string of nanocoins")

        // `Coins` is `VarUInteger 16`, i.e. at most 120 bits.
        if (amount.bitLength() > COINS_MAX_BITS) {
            throw TonConnectException.BadRequest("$field.amount exceeds the maximum representable value")
        }

        if (!message.extraCurrency.isNullOrEmpty()) {
            throw TonConnectException.BadRequest("$field.extra_currency is not supported by this wallet")
        }

        val payload = message.payload?.let { TonConnectBoc.singleRootCell(it, "$field.payload") }
        val stateInit = message.stateInit?.let { TonConnectBoc.singleRootCell(it, "$field.stateInit") }

        if (stateInit != null && !TonConnectTransferBuilder.isStateInitShaped(stateInit)) {
            throw TonConnectException.BadRequest("$field.stateInit is not a valid StateInit cell")
        }

        return TonConnectValidatedTransaction.Message(
            destination = friendly.address,
            bounce = friendly.isBounceable,
            amount = amount,
            payload = payload,
            stateInit = stateInit,
        )
    }

    companion object {
        const val WALLET_V4_MAX_MESSAGES = 4
        private const val COINS_MAX_BITS = 120
        private const val MILLIS_PER_SECOND = 1000L
    }
}
