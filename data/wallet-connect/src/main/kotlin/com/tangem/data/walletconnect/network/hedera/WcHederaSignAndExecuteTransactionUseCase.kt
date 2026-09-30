package com.tangem.data.walletconnect.network.hedera

import arrow.core.left
import com.domain.blockaid.models.transaction.CheckTransactionResult
import com.domain.blockaid.models.transaction.SimulationResult
import com.domain.blockaid.models.transaction.ValidationResult
import com.hedera.hashgraph.sdk.Client
import com.hedera.hashgraph.sdk.PrecheckStatusException
import com.hedera.hashgraph.sdk.Status
import com.hedera.hashgraph.sdk.Transaction
import com.hedera.hashgraph.sdk.TransactionResponse
import com.squareup.moshi.Moshi
import com.tangem.blockchain.common.TransactionData
import com.tangem.common.CompletionResult
import com.tangem.core.analytics.api.AnalyticsEventHandler
import com.tangem.data.walletconnect.respond.WcRespondService
import com.tangem.data.walletconnect.sign.BaseWcSignUseCase
import com.tangem.data.walletconnect.sign.SignCollector
import com.tangem.data.walletconnect.sign.SignStateConverter.toResult
import com.tangem.data.walletconnect.sign.WcMethodUseCaseContext
import com.tangem.datasource.di.SdkMoshi
import com.tangem.domain.core.lce.Lce
import com.tangem.domain.core.lce.LceFlow
import com.tangem.domain.walletconnect.WcTransactionSignerProvider
import com.tangem.domain.walletconnect.error.parseTangemSdkError
import com.tangem.domain.walletconnect.model.HandleMethodError
import com.tangem.domain.walletconnect.model.WcHederaMethod
import com.tangem.domain.walletconnect.usecase.method.BlockAidTransactionCheck
import com.tangem.domain.walletconnect.usecase.method.WcSignState
import com.tangem.domain.walletconnect.usecase.method.WcTransactionUseCase
import com.tangem.domain.walletmanager.WalletManagersFacade
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.logging.TangemLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * `hedera_signAndExecuteTransaction`: sign every chunk of the dApp's frozen `TransactionList` with the account key
 * and submit it to a consensus node. Response: `{ nodeId, transactionHash, transactionId }`.
 *
 * Submission uses the Hedera SDK client for the account's network, the same way blockchain-sdk's
 * `HederaNetworkService` sends the wallet's own transactions; each chunk is submitted as its own single-node list.
 */
@Suppress("LongParameterList")
internal class WcHederaSignAndExecuteTransactionUseCase @AssistedInject constructor(
    override val respondService: WcRespondService,
    override val analytics: AnalyticsEventHandler,
    private val signerProvider: WcTransactionSignerProvider,
    private val walletManagersFacade: WalletManagersFacade,
    private val dispatchers: CoroutineDispatcherProvider,
    @SdkMoshi private val moshi: Moshi,
    @Assisted override val context: WcMethodUseCaseContext,
    @Assisted override val method: WcHederaMethod.SignAndExecuteTransaction,
) : BaseWcSignUseCase<Nothing, TransactionData>(),
    WcTransactionUseCase {

    /** Validated in [WcHederaNetwork]; decoded again here to get the SDK object the signatures are attached to. */
    private val transactionListBytes: ByteArray =
        WcHederaTransactionParser.decodeTransactionBytes(method.transactionListBase64, "transactionList")

    // BlockAid doesn't support Hedera
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
        val walletPublicKey = walletManagersFacade.getOrCreateWalletManager(wallet.walletId, network)
            ?.wallet?.publicKey
            ?: run {
                emit(state.toResult(HandleMethodError.UnknownError("Failed to create wallet manager").left()))
                return
            }
        val publicKey = runCatching { WcHederaTransactionParser.hederaPublicKey(walletPublicKey.blockchainKey) }
            .getOrElse {
                emit(state.toResult(HandleMethodError.UnknownError(it.message.orEmpty()).left()))
                return
            }

        val decoded = runCatching { WcHederaTransactionParser.decodeTransactionList(transactionListBytes) }
            .getOrElse {
                emit(state.toResult(HandleMethodError.UnknownError(it.message.orEmpty()).left()))
                return
            }

        // One signature per consensus-node chunk; the card signs them in a single session.
        val hashes = decoded.chunks.map { WcHederaTransactionParser.hashToSign(it.bodyBytes, publicKey) }
        val signatures = when (val result = signerProvider.createSigner(wallet).sign(hashes, walletPublicKey)) {
            is CompletionResult.Success -> result.data
            is CompletionResult.Failure -> {
                emit(state.toResult(parseTangemSdkError(result.error).left()))
                return
            }
        }

        val signedChunks = decoded.chunks.mapIndexed { index, chunk ->
            val normalized = WcHederaTransactionParser.normalizeSignature(signatures[index], publicKey)
            WcHederaTransactionParser.signedChunkBytes(chunk, publicKey, normalized)
        }
        val response = runCatching { withContext(dispatchers.io) { execute(signedChunks, network.isTestnet) } }
            .getOrElse {
                TangemLogger.e("Hedera WalletConnect: failed to execute transaction", it)
                emit(state.toResult(HandleMethodError.UnknownError(it.message ?: "Failed to execute").left()))
                return
            }

        val respond = moshi.adapter(WcHederaSignAndExecuteTransactionResponse::class.java).toJson(
            WcHederaSignAndExecuteTransactionResponse(
                nodeId = response.nodeId.toString(),
                transactionHash = response.transactionHash.toByteString().base64(),
                transactionId = response.transactionId.toString(),
            ),
        )
        emit(state.toResult(respondService.respond(rawSdkRequest, respond)))
    }

    override fun invoke(): Flow<WcSignState<TransactionData>> {
        val transactionData = TransactionData.Compiled(
            value = TransactionData.Compiled.Data.Bytes(transactionListBytes),
        )
        return delegate.invoke(transactionData)
    }

    /**
     * Submits the chunks in the dApp's order, moving to the next node only when the current one is unreachable or
     * refuses to take transactions — the same reason the dApp offered several nodes. Any other precheck failure
     * (bad signature, insufficient balance, expired…) is final and reported as is.
     */
    private fun execute(signedChunks: List<ByteArray>, isTestnet: Boolean): TransactionResponse {
        val client = if (isTestnet) Client.forTestnet() else Client.forMainnet()
        try {
            var lastError: Exception? = null
            for (chunkBytes in signedChunks) {
                try {
                    return Transaction.fromBytes(chunkBytes).execute(client)
                } catch (e: PrecheckStatusException) {
                    if (e.status !in NODE_UNAVAILABLE_STATUSES) throw e
                    lastError = e
                } catch (e: TimeoutException) {
                    lastError = e
                } catch (e: RuntimeException) {
                    // Transport failures surface as io.grpc.StatusRuntimeException (not on this module's compile
                    // classpath); nothing else the SDK throws here is worth giving up the remaining nodes for.
                    lastError = e
                }
                TangemLogger.w("Hedera WalletConnect: node did not accept the transaction, trying the next", lastError)
            }
            throw lastError ?: IllegalStateException("no transaction chunks to submit")
        } finally {
            runCatching { client.close() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            context: WcMethodUseCaseContext,
            method: WcHederaMethod.SignAndExecuteTransaction,
        ): WcHederaSignAndExecuteTransactionUseCase
    }

    private companion object {
        val NODE_UNAVAILABLE_STATUSES = setOf(
            Status.BUSY,
            Status.PLATFORM_NOT_ACTIVE,
            Status.PLATFORM_TRANSACTION_NOT_CREATED,
            Status.INVALID_NODE_ACCOUNT,
        )
    }
}
