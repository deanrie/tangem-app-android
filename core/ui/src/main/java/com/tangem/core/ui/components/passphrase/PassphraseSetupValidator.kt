package com.tangem.core.ui.components.passphrase

import com.tangem.core.ui.R
import com.tangem.core.ui.extensions.TextReference
import com.tangem.core.ui.extensions.resourceReference

/**
 * Validation for the opt-in passphrase entered while creating a wallet.
 *
 * Adds the confirmation check on top of the SDK's own rules (which the caller supplies through [sdkErrorText], so
 * this stays free of any SDK dependency), so a typo cannot silently become the wallet's second secret.
 *
 * @param sdkErrorText runs the SDK validator on the passphrase and returns its error message, or `null` when the
 * passphrase is acceptable.
 */
class PassphraseSetupValidator(
    private val sdkErrorText: (passphrase: String) -> TextReference?,
) {

    data class Result(
        val isValid: Boolean,
        val errorText: TextReference?,
    )

    fun validate(enabled: Boolean, passphrase: String, confirmation: String): Result {
        if (!enabled) return Result(isValid = true, errorText = null)

        val sdkError = sdkErrorText(passphrase)
        if (sdkError != null) return Result(isValid = false, errorText = sdkError)

        if (passphrase.isEmpty()) return Result(isValid = false, errorText = null)

        if (confirmation != passphrase) {
            // Only complain once the user has typed at least as much as the passphrase.
            val showsMismatch = confirmation.length >= passphrase.length
            return Result(
                isValid = false,
                errorText = if (showsMismatch) {
                    resourceReference(R.string.onboarding_seed_passphrase_setup_mismatch)
                } else {
                    null
                },
            )
        }

        return Result(isValid = true, errorText = null)
    }
}
