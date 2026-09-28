package com.tangem.features.hotwallet.createmobilewallet

import com.tangem.core.ui.R
import com.tangem.core.ui.components.passphrase.PassphraseSetupValidator
import com.tangem.core.ui.extensions.TextReference
import com.tangem.core.ui.extensions.resourceReference
import com.tangem.core.ui.extensions.wrappedList
import com.tangem.hot.sdk.android.PassphraseValidator
import com.tangem.hot.sdk.exception.PassphraseNulCharacterException
import com.tangem.hot.sdk.exception.PassphraseTooLongException

/**
 * Runs the SDK [PassphraseValidator] (the same rules `TangemHotSdk.importWallet` enforces) and maps its exceptions
 * to the messages the import flow already shows.
 */
internal fun hotSdkPassphraseErrorText(passphrase: String): TextReference? {
    val error = runCatching { PassphraseValidator.validate(passphrase.toCharArray()) }.exceptionOrNull()
        ?: return null

    return when (error) {
        is PassphraseTooLongException -> resourceReference(
            R.string.hw_import_seed_phrase_passphrase_too_long,
            wrappedList(error.maxByteCount),
        )
        is PassphraseNulCharacterException -> resourceReference(R.string.common_unknown_error)
        else -> resourceReference(R.string.common_unknown_error)
    }
}

internal fun hotSdkPassphraseSetupValidator(): PassphraseSetupValidator =
    PassphraseSetupValidator(sdkErrorText = ::hotSdkPassphraseErrorText)
