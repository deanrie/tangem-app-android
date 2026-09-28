package com.tangem.features.hotwallet.createmobilewallet.entity

import com.tangem.core.ui.components.passphrase.PassphraseSetupUM
internal data class CreateMobileWalletUM(
    val createButtonLoading: Boolean,
    /** Opt-in BIP-39 passphrase for the new wallet; off by default. */
    val passphraseSetup: PassphraseSetupUM = PassphraseSetupUM(showsMobileUpgradeNote = true),
    /** Create is blocked while the passphrase option is on but not yet typed twice identically. */
    val createButtonEnabled: Boolean = true,
    val onBackClick: () -> Unit,
    val onImportClick: () -> Unit,
    val onCreateClick: () -> Unit,
    val onTermsClick: () -> Unit,
)