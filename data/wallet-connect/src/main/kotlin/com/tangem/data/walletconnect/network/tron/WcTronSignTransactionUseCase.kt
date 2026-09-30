package com.tangem.data.walletconnect.network.tron

import arrow.core.left
import com.domain.blockaid.models.transaction.CheckTransactionResult
import com.domain.blockaid.models.transaction.SimulationResult
import com.domain.blockaid.models.transaction.ValidationResult
import com.tangem.blockchain.common.TransactionData
import com.tangem.blockchain.common.UnmarshalHelper
import com.tangem.common.extensions.calculateSha256
import com.tangem.common.extensions.hexToBytes
import com.tangem.common.extensions.toHexString
import com.tangem.core.analytics.api.AnalyticsEventHandler
import com.tangem.data.walletconnect.respond.WcRespondService
import com.tangem.data.walletconnect.sign.BaseWcSignUseCase
import com.tangem.data.walletconnect.sign.SignCollector
import com.tangem.data.walletconnect.sign.SignStateConverter.toResult
import com.tangem.data.walletconnect.sign.WcMethodUseCaseContext
import com.tangem.domain.core.lce.Lce
import com.tangem.domain.core.lce.LceFlow
import com.tangem.domain.transaction.usecase.SignUseCase
import com.tangem.domain.walletconnect.error.parseTangemSdkError
import com.tangem.domain.walletconnect.model.HandleMethodError
import com.tangem.domain.walletconnect.model.WcTronMethod
import com.tangem.domain.walletconnect.usecase.method.BlockAidTransactionCheck
import com.tangem.domain.walletconnect.usecase.method.WcSignState
import com.tangem.domain.walletconnect.usecase.method.WcTransactionUseCase
import com.tangem.domain.walletmanager.WalletManagersFacade
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `tron_signTransaction`: sign a dApp-built transaction without broadcasting it.
 *
 * The bytes shown and signed come from `raw_data_hex` (decoded in [WcTronNetwork]), never from the dApp's JSON
 * `raw_data`.
 * The response is the TronWeb signed-transaction shape: `{ txID, raw_data, raw_data_hex, visible, signature: [..] }`.
 */
@Suppress("LongParameterList")
internal class WcTronSignTransactionUseCase @AssistedInject constructor(
    override val respondService: WcRespondService,
    override val analytics: AnalyticsEventHandler,
    private val signUseCase: SignUseCase,
    private val walletManagersFacade: WalletManagersFacade,
    @Assisted override val context: WcMethodUseCaseContext,
    @Assisted override val method: WcTronMethod.SignTransaction,
) : BaseWcSignUseCase<Nothing, TransactionData>(),
    WcTransactionUseCase {

    /** `raw_data_hex` was validated in [WcTronNetwork]; these are the exact bytes and the digest that gets signed. */
    private val rawData: ByteArray = method.rawDataHex.removePrefix("0x").hexToBytes()
    private val hash: ByteArray = rawData.calculateSha256()

    // BlockAid doesn't support Tron
    override val securityStatus: LceFlow<Throwable, BlockAidTransactionCheck.Result> = flowOf(
        Lce.Content(
            BlockAidTransactionCheck.Result.Plain(
                CheckTransactionResult(
                    validation = ValidationResult.FAILED_TO_VALIDATE,
                    simulation = SimulationResult.FailedToSimulate,
                ),
            ),
        ),
    )

    override suspend fun SignCollector<TransactionData>.onSign(state: WcSignState<TransactionData>) {
        val publicKey = walletManagersFacade.getOrCreateWalletManager(wallet.walletId, network)
            ?.wallet?.publicKey
            ?: run {
                emit(state.toResult(HandleMethodError.UnknownError("Failed to create wallet manager").left()))
                return
            }

        // Tron signs sha256(raw_data) directly — the same digest that is the txID.
        val signature = signUseCase(hash, wallet, network)
            .onLeft { emit(state.toResult(parseTangemSdkError(it).left())) }
            .getOrNull() ?: return

        val rsv = UnmarshalHelper.unmarshalSignatureExtended(
            signature = signature,
            hash = hash,
            publicKey = publicKey,
        ).asRSVLegacyEVM()

        val respond = buildResponse(WcTronTransactionParser.transactionSignatureHex(rsv))
        emit(state.toResult(respondService.respond(rawSdkRequest, respond)))
    }

    override fun invoke(): Flow<WcSignState<TransactionData>> {
        val transactionData = TransactionData.Compiled(
            value = TransactionData.Compiled.Data.Bytes(rawData),
        )
        return delegate.invoke(transactionData)
    }

    private fun buildResponse(signatureHex: String): String = buildString {
        append("{\"txID\":\"").append(hash.toHexString().lowercase()).append('"')
        method.rawDataJson?.let { append(",\"raw_data\":").append(it) }
        append(",\"raw_data_hex\":\"").append(method.rawDataHex).append('"')
        append(",\"visible\":").append(method.isVisible)
        append(",\"signature\":[\"").append(signatureHex).append("\"]}")
    }

    @AssistedFactory
    interface Factory {
        fun create(
            context: WcMethodUseCaseContext,
            method: WcTronMethod.SignTransaction,
        ): WcTronSignTransactionUseCase
    }
}
