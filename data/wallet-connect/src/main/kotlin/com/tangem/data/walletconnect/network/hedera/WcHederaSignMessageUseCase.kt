package com.tangem.data.walletconnect.network.hedera

import arrow.core.left
import com.domain.blockaid.models.transaction.CheckTransactionResult
import com.domain.blockaid.models.transaction.SimulationResult
import com.domain.blockaid.models.transaction.ValidationResult
import com.squareup.moshi.Moshi
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
import com.tangem.domain.walletconnect.model.WcHederaMethod
import com.tangem.domain.walletconnect.usecase.method.WcMessageSignUseCase
import com.tangem.domain.walletconnect.usecase.method.WcSignState
import com.tangem.domain.walletmanager.WalletManagersFacade
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `hedera_signMessage`: sign `"\x19Hedera Signed Message:\n" + length + message` the way the account key signs
 * transaction bodies (Ed25519 over the bytes, ECDSA over keccak256). Response: `{ signatureMap: base64 }`.
 *
 * The prefix is what keeps a signed message from ever being a valid `TransactionBody`: a protobuf body can't start
 * with byte 0x19 followed by that text.
 */
@Suppress("LongParameterList")
internal class WcHederaSignMessageUseCase @AssistedInject constructor(
    override val respondService: WcRespondService,
    override val analytics: AnalyticsEventHandler,
    private val signUseCase: SignUseCase,
    private val walletManagersFacade: WalletManagersFacade,
    @SdkMoshi private val moshi: Moshi,
    @Assisted override val context: WcMethodUseCaseContext,
    @Assisted override val method: WcHederaMethod.SignMessage,
) : BaseWcSignUseCase<Nothing, WcMessageSignUseCase.SignModel>(),
    WcMessageSignUseCase {

    // BlockAid doesn't support Hedera
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
            ?.wallet?.publicKey?.blockchainKey
            ?.let { key -> runCatching { WcHederaTransactionParser.hederaPublicKey(key) }.getOrNull() }
            ?: run {
                emit(state.toResult(HandleMethodError.UnknownError("Failed to resolve the account key").left()))
                return
            }

        val messageBytes = WcHederaTransactionParser.messageBytes(method.message)
        val signature = signUseCase(WcHederaTransactionParser.hashToSign(messageBytes, publicKey), wallet, network)
            .onLeft { emit(state.toResult(parseTangemSdkError(it).left())) }
            .getOrNull() ?: return

        val signatureMap = WcHederaTransactionParser.signatureMapBytes(
            signature = WcHederaTransactionParser.normalizeSignature(signature, publicKey),
            publicKey = publicKey,
        )
        val respond = moshi.adapter(WcHederaSignatureMapResponse::class.java)
            .toJson(WcHederaSignatureMapResponse(signatureMap = signatureMap.toByteString().base64()))
        emit(state.toResult(respondService.respond(rawSdkRequest, respond)))
    }

    override fun invoke(): Flow<WcSignState<WcMessageSignUseCase.SignModel>> {
        return delegate.invoke(initModel = WcMessageSignUseCase.SignModel(method.message))
    }

    @AssistedFactory
    interface Factory {
        fun create(context: WcMethodUseCaseContext, method: WcHederaMethod.SignMessage): WcHederaSignMessageUseCase
    }
}
