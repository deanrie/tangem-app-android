package com.tangem.features.walletconnect.transaction.converter

import com.tangem.core.ui.extensions.resourceReference
import com.tangem.domain.walletconnect.model.WcHederaTransactionSummary
import com.tangem.features.walletconnect.impl.R
import com.tangem.features.walletconnect.transaction.entity.common.WcTransactionRequestBlockUM
import com.tangem.features.walletconnect.transaction.entity.common.WcTransactionRequestInfoItemUM
import com.tangem.utils.converter.Converter
import kotlinx.collections.immutable.toImmutableList
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Builds the request details block for a Hedera `hedera_signAndExecuteTransaction` / `hedera_signTransaction`
 * request from the `TransactionBody` the wallet decoded out of the dApp's bytes — the bytes that get signed.
 */
internal class WcHederaTransactionRequestInfoConverter @Inject constructor() :
    Converter<WcHederaTransactionRequestInfoConverter.Input, WcTransactionRequestBlockUM> {

    override fun convert(value: Input): WcTransactionRequestBlockUM {
        val summary = value.summary
        return WcTransactionRequestBlockUM(
            info = buildList {
                add(
                    WcTransactionRequestInfoItemUM(
                        title = resourceReference(R.string.wc_hedera_transaction_type),
                        description = formatType(summary.transactionType),
                    ),
                )
                summary.payerAccountId?.let { payer ->
                    add(WcTransactionRequestInfoItemUM(resourceReference(R.string.wc_hedera_payer), payer))
                }
                // Debits first: what leaves the user's accounts is the line that matters.
                summary.hbarTransfers.sortedBy { it.tinybars }.forEach { leg ->
                    val amount = formatTinybars(leg.tinybars, value.decimals, value.symbol)
                    add(WcTransactionRequestInfoItemUM(legTitle(leg.tinybars), "${leg.accountId}: $amount"))
                }
                summary.tokenTransfers.sortedBy { it.amount }.forEach { leg ->
                    val amount = "${leg.amount} (${leg.tokenId})"
                    add(WcTransactionRequestInfoItemUM(legTitle(leg.amount), "${leg.accountId}: $amount"))
                }
                summary.maxFeeTinybars?.let { maxFee ->
                    add(
                        WcTransactionRequestInfoItemUM(
                            title = resourceReference(R.string.wc_hedera_max_fee),
                            description = formatTinybars(maxFee, value.decimals, value.symbol),
                        ),
                    )
                }
                summary.memo.takeIf { it.isNotEmpty() }?.let { memo ->
                    add(WcTransactionRequestInfoItemUM(resourceReference(R.string.send_extras_hint_memo), memo))
                }
            }.toImmutableList(),
        )
    }

    private fun legTitle(amount: Long) = resourceReference(if (amount < 0) R.string.common_from else R.string.common_to)

    /** `CRYPTOTRANSFER` → `Cryptotransfer`: HAPI body case names are upper-case without separators. */
    private fun formatType(dataCase: String): String = dataCase.lowercase().replaceFirstChar(Char::uppercase)

    private fun formatTinybars(tinybars: Long, decimals: Int, symbol: String): String {
        val value = BigDecimal.valueOf(tinybars).movePointLeft(decimals).stripTrailingZeros()
        return "${value.toPlainString()} $symbol"
    }

    data class Input(
        val summary: WcHederaTransactionSummary,
        val decimals: Int,
        val symbol: String,
    )
}
