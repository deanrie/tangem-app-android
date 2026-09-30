package com.tangem.data.walletconnect.network.tron

import arrow.core.left
import com.domain.blockaid.models.transaction.CheckTransactionResult
import com.domain.blockaid.models.transaction.SimulationResult
import com.domain.blockaid.models.transaction.ValidationResult
import com.squareup.moshi.Moshi
import com.tangem.blockchain.common.UnmarshalHelper
import com.tangem.blockchain.blockchains.ethereum.EthereumUtils.toKeccak
import com.tangem.core.analytics.api.AnalyticsEventHandler
import com.tangem.data.walletconnect.respond.WcRespondService
import com.tangem.data.walletconnect.sign.BaseWcSignUseCase
import com.tangem.data.walletconnect.sign.SignCollector
import com.tangem.data.walletconnect.sign.SignStateConverter.toResult
import com.tangem.data.walletconnect.sign.WcMethodUseCaseContext
import com.tangem.datasource.di.SdkMoshi
import com.tangem.domain.core.lce.Lce
import com.tangem.domain.core.lce.LceFlow
import com.tangem.domain.transaction.usecase.SignUseCase
import com.tangem.domain.walletconnect.error.parseTangemSdkError
import com.tangem.domain.walletconnect.model.HandleMethodError
import com.tangem.domain.walletconnect.model.WcTronMethod
import com.tangem.domain.walletconnect.usecase.method.WcMessageSignUseCase
import com.tangem.domain.walletconnect.usecase.method.WcSignState
import com.tangem.domain.walletmanager.WalletManagersFacade
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `tron_signMessage`: `tronWeb.trx.signMessageV2` — keccak256 of the `\x19TRON Signed Message:\n` prefixed text,
 * response `0x` ‖ r ‖ s ‖ v (v = 27/28).
 *
 * The prefix is what keeps a signed message from ever being a valid transaction signature: a Tron transaction is
 * signed over a bare sha256 digest, which can't collide with a prefixed keccak preimage.
 */
@Suppress("LongParameterList")
internal class WcTronSignMessageUseCase @AssistedInject constructor(
    override val respondService: WcRespondService,
    override val analytics: AnalyticsEventHandler,
    private val signUseCase: SignUseCase,
    private val walletManagersFacade: WalletManagersFacade,
    @SdkMoshi private val moshi: Moshi,
    @Assisted override val context: WcMethodUseCaseContext,
    @Assisted override val method: WcTronMethod.SignMessage,
) : BaseWcSignUseCase<Nothing, WcMessageSignUseCase.SignModel>(),
    WcMessageSignUseCase {

    // BlockAid doesn't support Tron
    override val securityStatus: LceFlow<Throwable, CheckTransactionResult> = flowOf(
        Lce.Content(
            CheckTransactionResult(
                validation = ValidationResult.FAILED_TO_VALIDATE,
                simulation = SimulationResult.FailedToSimulate,
            ),
        ),
    )

    override suspend fun SignCollector<WcMessageSignUseCase.SignModel>.onSign(
        state: WcSignState<WcMessageSignUseCase.SignModel>,
    ) {
        val publicKey = walletManagersFacade.getOrCreateWalletManager(wallet.walletId, network)
            ?.wallet?.publicKey
            ?: run {
                emit(state.toResult(HandleMethodError.UnknownError("Failed to create wallet manager").left()))
                return
            }

        val hash = WcTronTransactionParser.messageBytesToHash(method.message).toKeccak()

        val signature = signUseCase(hash, wallet, network)
            .onLeft { emit(state.toResult(parseTangemSdkError(it).left())) }
            .getOrNull() ?: return

        val rsv = UnmarshalHelper.unmarshalSignatureExtended(
            signature = signature,
            hash = hash,
            publicKey = publicKey,
        ).asRSVLegacyEVM()

        val response = WcTronSignMessageResponse(signature = WcTronTransactionParser.messageSignatureHex(rsv))
        val respond = moshi.adapter(WcTronSignMessageResponse::class.java).toJson(response)
        emit(state.toResult(respondService.respond(rawSdkRequest, respond)))
    }

    override fun invoke(): Flow<WcSignState<WcMessageSignUseCase.SignModel>> {
        return delegate.invoke(initModel = WcMessageSignUseCase.SignModel(method.message))
    }

    @AssistedFactory
    interface Factory {
        fun create(context: WcMethodUseCaseContext, method: WcTronMethod.SignMessage): WcTronSignMessageUseCase
    }
}
