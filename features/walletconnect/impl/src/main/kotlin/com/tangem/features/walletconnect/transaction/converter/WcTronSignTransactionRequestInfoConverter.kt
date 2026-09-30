package com.tangem.features.walletconnect.transaction.converter

import com.tangem.core.ui.extensions.resourceReference
import com.tangem.domain.walletconnect.model.WcTronMethod
import com.tangem.domain.walletconnect.model.WcTronTransactionDetails
import com.tangem.features.walletconnect.impl.R
import com.tangem.features.walletconnect.transaction.entity.common.WcTransactionRequestBlockUM
import com.tangem.features.walletconnect.transaction.entity.common.WcTransactionRequestInfoItemUM
import com.tangem.utils.converter.Converter
import kotlinx.collections.immutable.toImmutableList
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Builds the request details block for a Tron `tron_signTransaction` request from what the wallet decoded out of
 * `raw_data_hex` — the bytes that get signed — rather than from the dApp's JSON description of them.
 */
internal class WcTronSignTransactionRequestInfoConverter @Inject constructor() :
    Converter<WcTronSignTransactionRequestInfoConverter.Input, WcTransactionRequestBlockUM> {

    override fun convert(value: Input): WcTransactionRequestBlockUM {
        val details = value.method.details
        return WcTransactionRequestBlockUM(
            info = buildList {
                add(WcTransactionRequestInfoItemUM(resourceReference(R.string.common_from), details.ownerAddress))
                when (details) {
                    is WcTronTransactionDetails.Transfer -> {
                        add(
                            WcTransactionRequestInfoItemUM(
                                title = resourceReference(R.string.common_to),
                                description = details.destinationAddress,
                            ),
                        )
                        add(
                            WcTransactionRequestInfoItemUM(
                                title = resourceReference(R.string.common_amount),
                                description = formatSun(details.amountSun, value.decimals, value.symbol),
                            ),
                        )
                    }
                    is WcTronTransactionDetails.ContractCall -> {
                        add(
                            WcTransactionRequestInfoItemUM(
                                title = resourceReference(R.string.wc_tron_contract),
                                description = details.contractAddress,
                            ),
                        )
                        if (details.callValueSun > 0) {
                            add(
                                WcTransactionRequestInfoItemUM(
                                    title = resourceReference(R.string.common_amount),
                                    description = formatSun(details.callValueSun, value.decimals, value.symbol),
                                ),
                            )
                        }
                        details.feeLimitSun?.let { feeLimit ->
                            add(
                                WcTransactionRequestInfoItemUM(
                                    title = resourceReference(R.string.wc_tron_fee_limit),
                                    description = formatSun(feeLimit, value.decimals, value.symbol),
                                ),
                            )
                        }
                        add(
                            WcTransactionRequestInfoItemUM(
                                title = resourceReference(R.string.wc_tron_call_data),
                                description = "0x" + details.callDataHex,
                            ),
                        )
                    }
                }
                details.memo?.takeIf { it.isNotEmpty() }?.let { memo ->
                    add(WcTransactionRequestInfoItemUM(resourceReference(R.string.send_extras_hint_memo), memo))
                }
            }.toImmutableList(),
        )
    }

    private fun formatSun(sun: Long, decimals: Int, symbol: String): String {
        val value = BigDecimal.valueOf(sun).movePointLeft(decimals).stripTrailingZeros()
        return "${value.toPlainString()} $symbol"
    }

    data class Input(
        val method: WcTronMethod.SignTransaction,
        val decimals: Int,
        val symbol: String,
    )
}
