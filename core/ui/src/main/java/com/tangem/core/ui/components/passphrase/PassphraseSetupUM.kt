package com.tangem.core.ui.components.passphrase

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import com.tangem.core.ui.components.bottomsheets.TangemBottomSheetConfig
import com.tangem.core.ui.extensions.TextReference

/**
 * Optional BIP-39 passphrase for a wallet that is being *created* (as opposed to imported).
 *
 * Off by default: the passphrase is a second secret that is lost far more often than the seed phrase, so it is an
 * explicit opt-in behind a switch, has to be typed twice, and comes with a warning.
 */
@Immutable
data class PassphraseSetupUM(
    val enabled: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    val passphrase: TextFieldValue = TextFieldValue(""),
    val onPassphraseChange: (TextFieldValue) -> Unit = {},
    val confirmation: TextFieldValue = TextFieldValue(""),
    val onConfirmationChange: (TextFieldValue) -> Unit = {},
    /** Validation message for the current input, or `null` when there is nothing to report. */
    val errorText: TextReference? = null,
    /** `true` when the switch is off, or when both fields hold the same valid passphrase. */
    val isValid: Boolean = true,
    /** Mobile wallet only: a passphrase-protected mobile wallet cannot be upgraded to a card. */
    val showsMobileUpgradeNote: Boolean = false,
    val onInfoClick: () -> Unit = {},
    val infoBottomSheetConfig: TangemBottomSheetConfig = TangemBottomSheetConfig.Empty,
) {

    /** The passphrase to derive with, or `null` when the user did not opt in. */
    val resolvedPassphrase: String?
        get() = passphrase.text.takeIf { enabled && isValid && it.isNotEmpty() }
}
