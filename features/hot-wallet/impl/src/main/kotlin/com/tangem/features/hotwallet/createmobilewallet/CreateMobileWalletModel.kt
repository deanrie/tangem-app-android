package com.tangem.features.hotwallet.createmobilewallet

import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.dismiss
import com.tangem.common.routing.AppRoute
import com.tangem.core.analytics.api.AnalyticsEventHandler
import com.tangem.core.analytics.models.AnalyticsParam
import com.tangem.core.analytics.models.event.OnboardingAnalyticsEvent
import com.tangem.core.analytics.utils.TrackingContextProxy
import com.tangem.core.decompose.di.ModelScoped
import com.tangem.core.decompose.model.Model
import com.tangem.core.decompose.model.ParamsContainer
import com.tangem.core.decompose.navigation.Router
import com.tangem.core.decompose.ui.UiMessageSender
import com.tangem.core.ui.message.dialog.Dialogs.hotWalletCreationNotSupportedDialog
import com.tangem.datasource.local.appsflyer.AppsFlyerStore
import com.tangem.domain.hotwallet.IsHotWalletCreationSupported
import com.tangem.domain.wallets.builder.HotUserWalletBuilder
import com.tangem.domain.wallets.usecase.SaveWalletUseCase
import com.tangem.domain.wallets.usecase.SyncWalletWithRemoteUseCase
import com.tangem.features.hotwallet.CreateMobileWalletComponent
import com.tangem.features.hotwallet.HotWalletFeatureToggles
import com.tangem.features.hotwallet.createmobilewallet.entity.CreateMobileWalletUM
import com.tangem.features.hotwallet.createmobilewallet.importoptions.ImportOptionsBottomSheetConfig
import androidx.compose.ui.text.input.TextFieldValue
import com.tangem.core.ui.components.bottomsheets.TangemBottomSheetConfig
import com.tangem.core.ui.components.passphrase.PassphraseSetupUM
import com.tangem.features.hotwallet.MnemonicRepository
import com.tangem.hot.sdk.TangemHotSdk
import com.tangem.hot.sdk.model.HotAuth
import com.tangem.hot.sdk.model.MnemonicType
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.coroutines.runSuspendCatching
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.tangem.utils.logging.TangemLogger
import javax.inject.Inject

@Suppress("LongParameterList")
@ModelScoped
internal class CreateMobileWalletModel @Inject constructor(
    paramsContainer: ParamsContainer,
    override val dispatchers: CoroutineDispatcherProvider,
    private val hotUserWalletBuilderFactory: HotUserWalletBuilder.Factory,
    private val saveUserWalletUseCase: SaveWalletUseCase,
    private val syncWalletWithRemoteUseCase: SyncWalletWithRemoteUseCase,
    private val router: Router,
    private val tangemHotSdk: TangemHotSdk,
    private val trackingContextProxy: TrackingContextProxy,
    private val isHotWalletCreationSupported: IsHotWalletCreationSupported,
    private val uiMessageSender: UiMessageSender,
    private val analyticsEventHandler: AnalyticsEventHandler,
    private val appsFlyerStore: AppsFlyerStore,
    private val hotWalletFeatureToggles: HotWalletFeatureToggles,
    private val mnemonicRepository: MnemonicRepository,
) : Model() {

    private val passphraseValidator = hotSdkPassphraseSetupValidator()

    private val params: CreateMobileWalletComponent.Params = paramsContainer.require()

    val importOptionsBottomSheetNavigation = SlotNavigation<ImportOptionsBottomSheetConfig>()

    internal val uiState: StateFlow<CreateMobileWalletUM>
        field = MutableStateFlow(
            CreateMobileWalletUM(
                onBackClick = { router.pop() },
                onImportClick = ::onImportClick,
                onCreateClick = ::onCreateClick,
                createButtonLoading = false,
                passphraseSetup = PassphraseSetupUM(
                    showsMobileUpgradeNote = true,
                    onEnabledChange = ::onPassphraseEnabledChange,
                    onPassphraseChange = { value -> updatePassphrase { it.copy(passphrase = value) } },
                    onConfirmationChange = { value -> updatePassphrase { it.copy(confirmation = value) } },
                    onInfoClick = ::showPassphraseInfo,
                ),
                onTermsClick = { router.push(AppRoute.Disclaimer(isTosAccepted = true)) },
            ),
        )

    init {
        trackingContextProxy.addHotWalletContext()
        analyticsEventHandler.send(
            event = OnboardingAnalyticsEvent.Onboarding.Started(source = params.source),
        )
        analyticsEventHandler.send(
            event = OnboardingAnalyticsEvent.SeedPhrase.CreateMobileScreenOpened(source = params.source),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingContextProxy.removeContext()
    }

    private fun onImportClick() {
        analyticsEventHandler.send(OnboardingAnalyticsEvent.SeedPhrase.ButtonImportWallet())
        checkHotWalletCreationSupported(notSupported = { return })

        if (hotWalletFeatureToggles.isGoogleDriveBackupEnabled) {
            analyticsEventHandler.send(
                event = OnboardingAnalyticsEvent.Backup.ImportWalletRequest(
                    cloudBackup = AnalyticsParam.CloudBackupAvailability.Available,
                ),
            )
            importOptionsBottomSheetNavigation.activate(ImportOptionsBottomSheetConfig)
        } else {
            router.push(AppRoute.AddExistingWallet())
        }
    }

    fun onImportRecoveryPhrase() {
        importOptionsBottomSheetNavigation.dismiss()
        router.push(AppRoute.AddExistingWallet(mode = AppRoute.AddExistingWallet.Mode.RecoveryPhrase))
    }

    fun onImportCloudBackupsResolved() {
        importOptionsBottomSheetNavigation.dismiss()
        router.push(AppRoute.AddExistingWallet(mode = AppRoute.AddExistingWallet.Mode.CloudRestore))
    }

    fun onImportBottomSheetDismiss() {
        importOptionsBottomSheetNavigation.dismiss()
    }

    private fun onCreateClick() {
        val currentState = uiState.value
        if (!currentState.createButtonEnabled) return
        // Read once, up front: the fields are cleared if the user toggles the option off while we run.
        val passphrase = currentState.passphraseSetup.resolvedPassphrase

        analyticsEventHandler.send(OnboardingAnalyticsEvent.CreateWallet.ButtonCreateWallet())
        checkHotWalletCreationSupported(notSupported = { return })

        modelScope.launch {
            uiState.update {
                it.copy(createButtonLoading = true)
            }

            runSuspendCatching {
                val hotWalletId = if (passphrase == null) {
                    tangemHotSdk.generateWallet(HotAuth.NoAuth, mnemonicType = MnemonicType.Words12)
                } else {
                    // The SDK has no `generateWallet(passphrase)`: generate the phrase the same way the card
                    // onboarding does and import it with the passphrase.
                    tangemHotSdk.importWallet(
                        mnemonic = mnemonicRepository.generateMnemonic(MnemonicRepository.MnemonicType.Words12),
                        passphrase = passphrase.toCharArray(),
                        auth = HotAuth.NoAuth,
                    )
                }
                val hotUserWalletBuilder = hotUserWalletBuilderFactory.create(hotWalletId)
                val userWallet = hotUserWalletBuilder.build()

                saveUserWalletUseCase(userWallet)

                analyticsEventHandler.send(
                    OnboardingAnalyticsEvent.Onboarding.Finished(source = params.source),
                )
                analyticsEventHandler.send(
                    event = OnboardingAnalyticsEvent.CreateWallet.WalletCreatedSuccessfully(
                        source = params.source,
                        creationType = AnalyticsParam.WalletCreationType.NewSeed,
                        seedPhraseLength = SEED_PHRASE_LENGTH,
                        passPhraseState = if (passphrase == null) {
                            AnalyticsParam.EmptyFull.Empty
                        } else {
                            AnalyticsParam.EmptyFull.Full
                        },
                        referralId = appsFlyerStore.get()?.refcode,
                    ),
                )

                launch(dispatchers.main + NonCancellable) {
                    syncWalletWithRemoteUseCase(userWalletId = userWallet.walletId)
                }

                router.replaceAll(AppRoute.Wallet)
            }.onFailure { throwable ->
                TangemLogger.e("Error", throwable)

                uiState.update { it.copy(createButtonLoading = false) }
            }
        }
    }

    /** Turning the option off discards whatever was typed so nothing stale is derived with later. */
    private fun onPassphraseEnabledChange(enabled: Boolean) {
        updatePassphrase {
            if (enabled) {
                it.copy(enabled = true)
            } else {
                it.copy(enabled = false, passphrase = TextFieldValue(""), confirmation = TextFieldValue(""))
            }
        }
    }

    private fun updatePassphrase(block: (PassphraseSetupUM) -> PassphraseSetupUM) {
        uiState.update { state ->
            val setup = block(state.passphraseSetup)
            val validation = passphraseValidator.validate(
                enabled = setup.enabled,
                passphrase = setup.passphrase.text,
                confirmation = setup.confirmation.text,
            )

            state.copy(
                passphraseSetup = setup.copy(isValid = validation.isValid, errorText = validation.errorText),
                createButtonEnabled = validation.isValid,
            )
        }
    }

    private fun showPassphraseInfo() {
        uiState.update { state ->
            state.copy(
                passphraseSetup = state.passphraseSetup.copy(
                    infoBottomSheetConfig = TangemBottomSheetConfig.Empty.copy(
                        isShown = true,
                        onDismissRequest = {
                            uiState.update { inner ->
                                inner.copy(
                                    passphraseSetup = inner.passphraseSetup.copy(
                                        infoBottomSheetConfig = TangemBottomSheetConfig.Empty,
                                    ),
                                )
                            }
                        },
                    ),
                ),
            )
        }
    }

    private inline fun checkHotWalletCreationSupported(notSupported: () -> Unit) {
        if (!isHotWalletCreationSupported()) {
            uiMessageSender.send(
                hotWalletCreationNotSupportedDialog(isHotWalletCreationSupported.getLeastVersionName()),
            )
            notSupported()
        }
    }

    companion object {
        private const val SEED_PHRASE_LENGTH = 12
    }
}