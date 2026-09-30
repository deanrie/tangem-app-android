package com.tangem.data.walletconnect.network.tron

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.squareup.moshi.Moshi
import com.tangem.blockchain.common.Blockchain
import com.tangem.blockchainsdk.utils.ExcludedBlockchains
import com.tangem.data.walletconnect.model.CAIP2
import com.tangem.data.walletconnect.model.NamespaceKey
import com.tangem.data.walletconnect.request.WcRequestToUseCaseConverter
import com.tangem.data.walletconnect.request.WcRequestToUseCaseConverter.Companion.fromJson
import com.tangem.data.walletconnect.sign.WcMethodUseCaseContext
import com.tangem.data.walletconnect.utils.WcNamespaceConverter
import com.tangem.data.walletconnect.utils.WcNetworksConverter
import com.tangem.domain.walletconnect.model.HandleMethodError
import com.tangem.domain.walletconnect.model.WcTronMethod
import com.tangem.domain.walletconnect.model.WcTronMethodName
import com.tangem.domain.walletconnect.model.sdkcopy.WcSdkSessionRequest
import com.tangem.domain.walletconnect.repository.WcSessionsManager
import com.tangem.domain.walletconnect.usecase.method.WcMethodUseCase
import jakarta.inject.Inject

/**
 * WalletConnect request handler for the Tron network.
 *
 * Handles `tron_signTransaction` and `tron_signMessage`.
 *
 * @see <a href="https://docs.reown.com/advanced/multichain/rpc-reference/tron-rpc">Tron RPC Reference</a>
 */
internal class WcTronNetwork(
    private val moshi: Moshi,
    private val sessionsManager: WcSessionsManager,
    private val factories: Factories,
    private val networksConverter: WcNetworksConverter,
) : WcRequestToUseCaseConverter {

    override fun toWcMethodName(request: WcSdkSessionRequest): WcTronMethodName? {
        val methodKey = request.request.method
        return WcTronMethodName.entries.find { it.raw == methodKey }
    }

    override suspend fun toUseCase(request: WcSdkSessionRequest): Either<HandleMethodError, WcMethodUseCase> {
        fun error(message: String) = HandleMethodError.UnknownError(message).left()

        val name = toWcMethodName(request) ?: return error("Unknown method name")
        val method: WcTronMethod = name.toMethod(request)
            .getOrElse { return error(it.message.orEmpty()) }
            ?: return error("Failed to parse $name")

        val session = sessionsManager.findSessionByTopic(request.topic)
            ?: return HandleMethodError.UnknownSession.left()

        val accountAddress = when (method) {
            is WcTronMethod.SignTransaction -> method.address
            is WcTronMethod.SignMessage -> method.address
        }

        val walletNetwork = networksConverter.findWalletNetworkForRequest(request, session, accountAddress)
            ?: return error("Failed to find walletNetwork for accountAddress $accountAddress")

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
            is WcTronMethod.SignTransaction -> factories.signTransaction.create(context, method)
            is WcTronMethod.SignMessage -> factories.signMessage.create(context, method)
        }
        return useCase.right()
    }

    private fun WcTronMethodName.toMethod(request: WcSdkSessionRequest): Either<Throwable, WcTronMethod?> {
        val rawParams = request.request.params
        return when (this) {
            WcTronMethodName.SignTransaction -> WcTronSignTransactionParams.parse(rawParams)?.let { parsed ->
                // Decode `raw_data_hex` up front: a request the wallet can't fully interpret is rejected here,
                // before anything is shown to the user. The decoded details are what the confirmation screen shows.
                val signable = runCatching {
                    WcTronTransactionParser.makeSignableTransaction(
                        rawDataHex = parsed.rawDataHex,
                        txId = parsed.txId,
                        expectedOwnerAddress = parsed.address,
                    )
                }.getOrElse { return it.left() }
                WcTronMethod.SignTransaction(
                    address = parsed.address,
                    rawDataHex = parsed.rawDataHex,
                    txId = parsed.txId,
                    isVisible = parsed.isVisible,
                    rawDataJson = parsed.rawDataJson,
                    details = signable.details,
                )
            }
            WcTronMethodName.SignMessage -> moshi.fromJson<WcTronSignMessageRequest>(rawParams)
                .getOrElse { return it.left() }
                ?.let { req -> WcTronMethod.SignMessage(address = req.address, message = req.message) }
        }.right()
    }

    /**
     * Tron namespace converter for CAIP-2 chain IDs: `tron:<first 4 bytes of the genesis block id>`.
     * Mainnet `0x2b6653dc`, Nile testnet `0xcd8690dc` (the one the SDK talks to), Shasta testnet `0x94a9059e`.
     */
    internal class NamespaceConverter @Inject constructor(
        override val excludedBlockchains: ExcludedBlockchains,
    ) : WcNamespaceConverter {

        override val namespaceKey: NamespaceKey = NamespaceKey(NAMESPACE)

        override fun toBlockchain(chainId: CAIP2): Blockchain? {
            if (chainId.namespace != namespaceKey.key) return null
            return when (chainId.reference.lowercase()) {
                MAINNET_REFERENCE -> Blockchain.Tron
                in TESTNET_REFERENCES -> Blockchain.TronTestnet
                else -> null
            }
        }
    }

    internal class Factories @Inject constructor(
        val signTransaction: WcTronSignTransactionUseCase.Factory,
        val signMessage: WcTronSignMessageUseCase.Factory,
    )

    companion object {
        private const val NAMESPACE = "tron"
        private const val MAINNET_REFERENCE = "0x2b6653dc"
        private val TESTNET_REFERENCES = setOf("0xcd8690dc", "0x94a9059e")
    }
}
