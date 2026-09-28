package com.tangem.core.ui.components.passphrase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tangem.core.ui.R
import com.tangem.core.ui.components.OutlineTextField
import com.tangem.core.ui.components.TangemSwitch
import com.tangem.core.ui.components.fields.contextmenu.DisableContextMenu
import com.tangem.core.ui.components.notifications.Notification
import com.tangem.core.ui.components.notifications.NotificationConfig
import com.tangem.core.ui.extensions.combinedReference
import com.tangem.core.ui.extensions.resolveReference
import com.tangem.core.ui.extensions.resourceReference
import com.tangem.core.ui.extensions.stringReference
import com.tangem.core.ui.extensions.stringResourceSafe
import com.tangem.core.ui.res.TangemTheme
import com.tangem.core.ui.res.TangemThemePreview

/**
 * Opt-in passphrase for a wallet being created: a switch (off by default) that reveals a warning, the passphrase
 * field and a confirmation field. See [PassphraseSetupUM].
 */
@Composable
fun PassphraseSetupBlock(state: PassphraseSetupUM, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SwitchRow(state)

        AnimatedVisibility(visible = state.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WarningNotification(state)
                Fields(state)
                if (state.errorText != null) {
                    Text(
                        text = state.errorText.resolveReference(),
                        style = TangemTheme.typography.caption2,
                        color = TangemTheme.colors.text.warning,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }

    PassphraseInfoBottomSheet(state.infoBottomSheetConfig)
}

@Composable
private fun SwitchRow(state: PassphraseSetupUM) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = TangemTheme.colors.field.primary,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResourceSafe(R.string.onboarding_seed_passphrase_setup_toggle),
            style = TangemTheme.typography.body2,
            color = TangemTheme.colors.text.primary1,
        )

        Icon(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(20.dp)
                .clickable(onClick = state.onInfoClick),
            painter = painterResource(id = R.drawable.ic_information_24),
            tint = TangemTheme.colors.icon.informative,
            contentDescription = null,
        )

        Spacer(modifier = Modifier.weight(1f))

        TangemSwitch(
            checked = state.enabled,
            onCheckedChange = state.onEnabledChange,
        )
    }
}

@Composable
private fun WarningNotification(state: PassphraseSetupUM) {
    val message = resourceReference(R.string.onboarding_seed_passphrase_setup_warning_message)
    val subtitle = if (state.showsMobileUpgradeNote) {
        combinedReference(
            message,
            stringReference("\n\n"),
            resourceReference(R.string.onboarding_seed_passphrase_setup_mobile_upgrade_note),
        )
    } else {
        message
    }

    Notification(
        config = NotificationConfig(
            title = resourceReference(R.string.onboarding_seed_passphrase_setup_warning_title),
            subtitle = subtitle,
            iconResId = R.drawable.ic_alert_24,
            iconTint = NotificationConfig.IconTint.Warning,
        ),
    )
}

@Composable
private fun Fields(state: PassphraseSetupUM) {
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DisableContextMenu {
            OutlineTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.passphrase,
                onValueChange = state.onPassphraseChange,
                label = stringResourceSafe(R.string.common_passphrase),
                isError = state.errorText != null && state.confirmation.text.isEmpty(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(focusDirection = FocusDirection.Down) },
                ),
            )

            OutlineTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.confirmation,
                onValueChange = state.onConfirmationChange,
                label = stringResourceSafe(R.string.onboarding_seed_passphrase_setup_confirm_placeholder),
                isError = state.errorText != null && state.confirmation.text.isNotEmpty(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    TangemThemePreview {
        PassphraseSetupBlock(
            state = PassphraseSetupUM(
                enabled = true,
                passphrase = TextFieldValue("correct horse"),
                confirmation = TextFieldValue("correct horsE"),
                errorText = resourceReference(R.string.onboarding_seed_passphrase_setup_mismatch),
                showsMobileUpgradeNote = true,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
