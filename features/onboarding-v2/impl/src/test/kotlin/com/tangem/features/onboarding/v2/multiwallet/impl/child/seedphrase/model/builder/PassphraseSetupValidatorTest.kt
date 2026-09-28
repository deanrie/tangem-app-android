package com.tangem.features.onboarding.v2.multiwallet.impl.child.seedphrase.model.builder

import com.google.common.truth.Truth.assertThat
import com.tangem.core.ui.R
import com.tangem.core.ui.extensions.TextReference
import com.tangem.core.ui.components.passphrase.PassphraseSetupValidator
import com.tangem.core.ui.extensions.resourceReference
import com.tangem.core.ui.extensions.wrappedList
import org.junit.jupiter.api.Test

class PassphraseSetupValidatorTest {

    /** Stands in for the SDK validator (`hotSdkPassphraseErrorText`), whose NFKD step needs `android.icu`. */
    private val validator = PassphraseSetupValidator(
        sdkErrorText = { passphrase ->
            if (passphrase.length > MAX_BYTES) {
                resourceReference(R.string.hw_import_seed_phrase_passphrase_too_long, wrappedList(MAX_BYTES))
            } else {
                null
            }
        },
    )

    @Test
    fun `disabled is valid regardless of the fields`() {
        val result = validator.validate(enabled = false, passphrase = "x", confirmation = "y")

        assertThat(result.isValid).isTrue()
        assertThat(result.errorText).isNull()
    }

    @Test
    fun `enabled but empty is not valid and shows no error yet`() {
        val result = validator.validate(enabled = true, passphrase = "", confirmation = "")

        assertThat(result.isValid).isFalse()
        assertThat(result.errorText).isNull()
    }

    @Test
    fun `mismatch is silent while the confirmation is still shorter than the passphrase`() {
        val result = validator.validate(
            enabled = true,
            passphrase = "correct horse",
            confirmation = "corr",
        )

        assertThat(result.isValid).isFalse()
        assertThat(result.errorText).isNull()
    }

    @Test
    fun `mismatch is reported once the confirmation is at least as long as the passphrase`() {
        val result = validator.validate(
            enabled = true,
            passphrase = "correct horse",
            confirmation = "correct horsE",
        )

        assertThat(result.isValid).isFalse()
        assertThat(result.errorText)
            .isEqualTo(TextReference.Res(R.string.onboarding_seed_passphrase_setup_mismatch))
    }

    @Test
    fun `matching confirmation is valid and keeps case and spaces`() {
        val result = validator.validate(
            enabled = true,
            passphrase = " Correct Horse ",
            confirmation = " Correct Horse ",
        )

        assertThat(result.isValid).isTrue()
        assertThat(result.errorText).isNull()
    }

    @Test
    fun `passphrase over the SDK byte limit is rejected with the limit in the message`() {
        val tooLong = "a".repeat(257)

        val result = validator.validate(enabled = true, passphrase = tooLong, confirmation = tooLong)

        assertThat(result.isValid).isFalse()
        val error = result.errorText as TextReference.Res
        assertThat(error.id).isEqualTo(R.string.hw_import_seed_phrase_passphrase_too_long)
    }

    private companion object {
        const val MAX_BYTES = 256
    }
}
