package com.tangem.features.onboarding.v2.multiwallet.impl.child.seedphrase.model.builder

import androidx.compose.ui.text.input.TextFieldValue
import com.tangem.core.ui.components.bottomsheets.TangemBottomSheetConfig
import com.tangem.core.ui.components.passphrase.PassphraseSetupUM
import com.tangem.features.onboarding.v2.multiwallet.impl.child.seedphrase.model.GeneratedWordsType
import com.tangem.features.onboarding.v2.multiwallet.impl.child.seedphrase.model.SeedPhraseState
import com.tangem.features.onboarding.v2.multiwallet.impl.child.seedphrase.ui.state.MultiWalletSeedPhraseUM
import kotlinx.collections.immutable.toImmutableList

internal class SeedPhraseCheckUiStateBuilder(
    private val currentState: () -> SeedPhraseState,
    private val currentUiState: () -> MultiWalletSeedPhraseUM,
    private val updateUiState: (
        (MultiWalletSeedPhraseUM.GeneratedWordsCheck) -> MultiWalletSeedPhraseUM.GeneratedWordsCheck,
    ) -> Unit,
    /** Receives the opt-in passphrase, or `null` when the user did not enable one. */
    private val importWallet: (passphrase: String?) -> Unit,
    private val readyToImport: (Boolean) -> Unit,
) {

    private val passphraseValidator = hotSdkPassphraseSetupValidator()

    @Suppress("MagicNumber")
    fun getState(): MultiWalletSeedPhraseUM.GeneratedWordsCheck {
        val wordFields = List(3) { index ->
            val shownIndex = when (index) {
                0 -> 2
                1 -> 7
                2 -> 11
                else -> error("")
            }

            MultiWalletSeedPhraseUM.GeneratedWordsCheck.WordField(
                index = shownIndex,
                word = TextFieldValue(""),
                error = false,
                onChange = { changeField ->
                    updateUiState { st ->
                        val newState = st.copy(
                            wordFields = st.wordFields.map { wordField ->
                                if (wordField.index == shownIndex) {
                                    wordField.updateField(changeField, shownIndex)
                                } else {
                                    wordField
                                }
                            }.toImmutableList(),
                        )

                        newState.withCreateButtonState()
                    }
                },
            )
        }.toImmutableList()

        return MultiWalletSeedPhraseUM.GeneratedWordsCheck(
            wordFields = wordFields,
            passphraseSetup = PassphraseSetupUM(
                onEnabledChange = ::onPassphraseEnabledChange,
                onPassphraseChange = { value -> updatePassphrase { it.copy(passphrase = value) } },
                onConfirmationChange = { value -> updatePassphrase { it.copy(confirmation = value) } },
                onInfoClick = ::showPassphraseInfo,
            ),
            createWalletButtonEnabled = false,
            createWalletButtonProgress = false,
            onCreateWalletButtonClick = {
                val currentState = currentUiState() as? MultiWalletSeedPhraseUM.GeneratedWordsCheck
                    ?: return@GeneratedWordsCheck
                if (currentState.createWalletButtonEnabled) {
                    importWallet(currentState.passphraseSetup.resolvedPassphrase)
                }
            },
        )
    }

    /** Turning the option off discards whatever was typed so nothing stale is derived with later. */
    private fun onPassphraseEnabledChange(enabled: Boolean) {
        updatePassphrase {
            if (enabled) {
                it.copy(enabled = true)
            } else {
                it.copy(
                    enabled = false,
                    passphrase = TextFieldValue(""),
                    confirmation = TextFieldValue(""),
                )
            }
        }
    }

    private fun updatePassphrase(block: (PassphraseSetupUM) -> PassphraseSetupUM) {
        updateUiState { st ->
            val setup = block(st.passphraseSetup)
            val validation = passphraseValidator.validate(
                enabled = setup.enabled,
                passphrase = setup.passphrase.text,
                confirmation = setup.confirmation.text,
            )

            st.copy(
                passphraseSetup = setup.copy(
                    isValid = validation.isValid,
                    errorText = validation.errorText,
                ),
            ).withCreateButtonState()
        }
    }

    private fun showPassphraseInfo() {
        updateUiState { st ->
            st.copy(
                passphraseSetup = st.passphraseSetup.copy(
                    infoBottomSheetConfig = TangemBottomSheetConfig.Empty.copy(
                        isShown = true,
                        onDismissRequest = {
                            updateUiState { inner ->
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

    /** The words must match AND the passphrase state must be valid (it is valid when the option is off). */
    private fun MultiWalletSeedPhraseUM.GeneratedWordsCheck.withCreateButtonState(): MultiWalletSeedPhraseUM.GeneratedWordsCheck {
        val ready = allFieldsCorrect() && passphraseSetup.isValid
        readyToImport(ready)
        return copy(createWalletButtonEnabled = ready)
    }

    private fun MultiWalletSeedPhraseUM.GeneratedWordsCheck.WordField.updateField(
        newText: TextFieldValue,
        shownIndex: Int,
    ): MultiWalletSeedPhraseUM.GeneratedWordsCheck.WordField {
        val correct = checkWordField(word = newText.text, shownIndex = shownIndex)

        return copy(
            word = newText,
            error = correct.not(),
        )
    }

    private fun MultiWalletSeedPhraseUM.GeneratedWordsCheck.allFieldsCorrect(): Boolean {
        return wordFields.all {
            checkWordField(
                word = it.word.text,
                shownIndex = it.index,
            )
        }
    }

    private fun checkWordField(word: String, shownIndex: Int): Boolean {
        val currentState = currentState()

        currentState.generatedWords12 ?: return false
        currentState.generatedWords24 ?: return false

        val wordList = when (currentState.generatedWordsType) {
            GeneratedWordsType.Words12 -> currentState.generatedWords12
            GeneratedWordsType.Words24 -> currentState.generatedWords24
        }.mnemonicComponents

        return wordList[shownIndex - 1] == word
    }
}