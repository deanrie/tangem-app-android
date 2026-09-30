package com.tangem.data.walletconnect.network.hedera

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.hedera.hashgraph.sdk.Client
import com.squareup.moshi.Moshi
import com.tangem.blockchain.common.Blockchain
import com.tangem.blockchainsdk.utils.ExcludedBlockchains
import com.tangem.data.walletconnect.model.CAIP2
import com.tangem.data.walletconnect.model.NamespaceKey
import com.tangem.data.walletconnect.request.WcRequestToUseCaseConverter
import com.tangem.data.walletconnect.request.WcRequestToUseCaseConverter.Companion.fromJson
import com.tangem.data.walletconnect.respond.WcRespondService
import com.tangem.data.walletconnect.sign.WcMethodUseCaseContext
import com.tangem.data.walletconnect.utils.WcNamespaceConverter
import com.tangem.data.walletconnect.utils.WcNetworksConverter
import com.tangem.domain.walletconnect.model.HandleMethodError
import com.tangem.domain.walletconnect.model.WcHederaMethod
import com.tangem.domain.walletconnect.model.WcHederaMethodName
import com.tangem.domain.walletconnect.model.sdkcopy.WcSdkSessionRequest
import com.tangem.domain.walletconnect.repository.WcSessionsManager
import com.tangem.domain.walletconnect.usecase.method.WcMethodUseCase
import jakarta.inject.Inject

/**
 * WalletConnect request handler for the Hedera network (HIP-820).
 *
 * `hedera_signAndExecuteTransaction`, `hedera_signTransaction` and `hedera_signMessage` go through the usual
 * confirmation flow; `hedera_getNodeAddresses` is answered immediately — it discloses only the public consensus node
 * list of the requested network.
 */
internal class WcHederaNetwork(
    private val moshi: Moshi,
    private val sessionsManager: WcSessionsManager,
    private val factories: Factories,
    private val networksConverter: WcNetworksConverter,
    private val respondService: WcRespondService,
) : WcRequestToUseCaseConverter {

    override fun toWcMethodName(request: WcSdkSessionRequest): WcHederaMethodName? {
        val methodKey = request.request.method
        return WcHederaMethodName.entries.find { it.raw == methodKey }
    }

    override fun respondWithoutUserInteraction(request: WcSdkSessionRequest): Boolean {
        if (toWcMethodName(request) != WcHederaMethodName.GetNodeAddresses) return false
        val chainReference = request.chainId?.let(CAIP2::fromRaw)?.reference ?: return false
        val nodes = consensusNodeAccountIds(chainReference) ?: return false
        val response = moshi.adapter(WcHederaNodeAddressesResponse::class.java)
            .toJson(WcHederaNodeAddressesResponse(nodes = nodes))
        respondService.respondNonBlock(request, response)
        return true
    }

    override suspend fun toUseCase(request: WcSdkSessionRequest): Either<HandleMethodError, WcMethodUseCase> {
        fun error(message: String) = HandleMethodError.UnknownError(message).left()

        val name = toWcMethodName(request) ?: return error("Unknown method name")
        val chainReference = request.chainId?.let(CAIP2::fromRaw)?.reference
        val method: WcHederaMethod = name.toMethod(request, chainReference)
            .getOrElse { return error(it.message.orEmpty()) }
            ?: return error("Failed to parse $name")

        val session = sessionsManager.findSessionByTopic(request.topic)
            ?: return HandleMethodError.UnknownSession.left()

        val accountAddress = when (method) {
            is WcHederaMethod.SignAndExecuteTransaction -> method.signerAccountId
            is WcHederaMethod.SignTransaction -> method.signerAccountId
            is WcHederaMethod.SignMessage -> method.signerAccountId
            WcHederaMethod.GetNodeAddresses -> return error("$name is answered without user interaction")
        }

        // The signer the dApp names must be one of the wallet's own accounts for this session; a foreign account id
        // resolves to no network and the request is refused here.
        val walletNetwork = networksConverter.findWalletNetworkForRequest(request, session, accountAddress)
            ?: return error("signerAccountId $accountAddress is not a connected account")

        val context = WcMethodUseCaseContext(
            session = session,
            rawSdkRequest = request,
            network = walletNetwork,
            accountAddress = accountAddress,
            networkDerivationsCount = networksConverter.filterWalletNetworkForRequest(
                rawChainId = request.chainId.orEmpty(),
                account = session.account,
            ).size,
        )

        val useCase = when (method) {
            is WcHederaMethod.SignAndExecuteTransaction -> factories.signAndExecuteTransaction.create(context, method)
            is WcHederaMethod.SignTransaction -> factories.signTransaction.create(context, method)
            is WcHederaMethod.SignMessage -> factories.signMessage.create(context, method)
            WcHederaMethod.GetNodeAddresses -> return error("$name is answered without user interaction")
        }
        return useCase.right()
    }

    @Suppress("ReturnCount")
    private fun WcHederaMethodName.toMethod(
        request: WcSdkSessionRequest,
        chainReference: String?,
    ): Either<Throwable, WcHederaMethod?> = Either.catch {
        val rawParams = request.request.params
        when (this) {
            WcHederaMethodName.SignAndExecuteTransaction -> {
                val req = moshi.fromJson<WcHederaSignAndExecuteTransactionRequest>(rawParams).bind()
                    ?: return@catch null
                val signer = WcHederaTransactionParser.parseSignerAccountId(req.signerAccountId)
                val bytes = WcHederaTransactionParser.decodeTransactionBytes(req.transactionList, "transactionList")
                // Decoded up front: a list the wallet can't fully interpret is refused before anything is shown.
                val decoded = WcHederaTransactionParser.decodeTransactionList(bytes)
                WcHederaTransactionParser.validateNetwork(signer, chainReference)
                WcHederaMethod.SignAndExecuteTransaction(
                    signerAccountId = signer.accountId,
                    transactionListBase64 = req.transactionList,
                    summary = decoded.summary,
                )
            }
            WcHederaMethodName.SignTransaction -> {
                val req = moshi.fromJson<WcHederaSignTransactionRequest>(rawParams).bind() ?: return@catch null
                val signer = WcHederaTransactionParser.parseSignerAccountId(req.signerAccountId)
                val bytes = WcHederaTransactionParser.decodeTransactionBytes(req.transactionBody, "transactionBody")
                val body = WcHederaTransactionParser.decodeTransactionBody(bytes)
                WcHederaTransactionParser.validateNetwork(signer, chainReference)
                WcHederaMethod.SignTransaction(
                    signerAccountId = signer.accountId,
                    transactionBodyBase64 = req.transactionBody,
                    summary = WcHederaTransactionParser.summarize(body),
                )
            }
            WcHederaMethodName.SignMessage -> {
                val req = moshi.fromJson<WcHederaSignMessageRequest>(rawParams).bind() ?: return@catch null
                val signer = WcHederaTransactionParser.parseSignerAccountId(req.signerAccountId)
                WcHederaTransactionParser.validateNetwork(signer, chainReference)
                WcHederaMethod.SignMessage(signerAccountId = signer.accountId, message = req.message)
            }
            WcHederaMethodName.GetNodeAddresses -> WcHederaMethod.GetNodeAddresses
        }
    }

    private fun <T> Either<Throwable, T>.bind(): T = getOrElse { throw it }

    /**
     * Hedera namespace converter for CAIP-2 chain IDs: `hedera:mainnet`, `hedera:testnet`
     * (previewnet / devnet are not networks the wallet has).
     */
    internal class NamespaceConverter @Inject constructor(
        override val excludedBlockchains: ExcludedBlockchains,
    ) : WcNamespaceConverter {

        override val namespaceKey: NamespaceKey = NamespaceKey(NAMESPACE)

        override fun toBlockchain(chainId: CAIP2): Blockchain? {
            if (chainId.namespace != namespaceKey.key) return null
            return when (chainId.reference.lowercase()) {
                MAINNET_REFERENCE -> Blockchain.Hedera
                TESTNET_REFERENCE -> Blockchain.HederaTestnet
                else -> null
            }
        }
    }

    internal class Factories @Inject constructor(
        val signAndExecuteTransaction: WcHederaSignAndExecuteTransactionUseCase.Factory,
        val signTransaction: WcHederaSignTransactionUseCase.Factory,
        val signMessage: WcHederaSignMessageUseCase.Factory,
    )

    companion object {
        private const val NAMESPACE = "hedera"
        private const val MAINNET_REFERENCE = "mainnet"
        private const val TESTNET_REFERENCE = "testnet"

        /** Consensus node account ids of the SDK's built-in address book for the requested network. */
        fun consensusNodeAccountIds(chainReference: String): List<String>? {
            val client = when (chainReference.lowercase()) {
                MAINNET_REFERENCE -> Client.forMainnet()
                TESTNET_REFERENCE -> Client.forTestnet()
                else -> return null
            }
            return try {
                client.network.values.map { it.toString() }.distinct().sorted()
            } finally {
                runCatching { client.close() }
            }
        }
    }
}
