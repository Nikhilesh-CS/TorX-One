package com.torxone.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.contacts.ContactsUiState
import com.torxone.app.identity.ContactInviteV1
import com.torxone.app.identity.InviteValidationResult
import com.torxone.app.ui.components.ContactInviteConfirmation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated UI fixtures: no production database, pairing, invites or ratchets are changed. */
@RunWith(AndroidJUnit4::class)
class ContactInviteInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun failedConfirmationKeepsFingerprintAndOffersAnotherAttempt() {
        val state = mutableStateOf(confirmation().copy(addingContact = true))
        var confirmations = 0
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.padding(24.dp)) {
                    ContactInviteConfirmation(state.value, onCancel = {}, onConfirm = {
                        confirmations++
                        state.value = state.value.copy(addingContact = true, error = null)
                    })
                }
            }
        }
        compose.onNodeWithText("Connecting securely…").assertIsDisplayed()
        compose.onNodeWithText("Accept & Connect").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(addingContact = false,
                error = "Tor address became unavailable. Recreate the contact invite.")
        }
        compose.onNodeWithText("Tor address became unavailable. Recreate the contact invite.").assertIsDisplayed()
        compose.onNodeWithText("Add \"Owned test phone\"?").assertIsDisplayed()
        compose.onNodeWithText("TEST SAFETY FINGERPRINT").assertIsDisplayed()
        compose.onNodeWithText("Connecting securely…").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.onNodeWithText("Accept & Connect").assertIsEnabled().performClick()
        compose.onNodeWithText("Connecting securely…").assertIsDisplayed()
        compose.onNodeWithText("Tor address became unavailable. Recreate the contact invite.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun pendingCommitBlocksDuplicateConfirmationAndCancellation() {
        val state = mutableStateOf(confirmation())
        var confirmations = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.padding(24.dp)) {
                    ContactInviteConfirmation(state.value,
                        onCancel = { cancellations++ },
                        onConfirm = {
                            confirmations++
                            state.value = state.value.copy(addingContact = true, error = null)
                        })
                }
            }
        }
        compose.onNodeWithText("Accept & Connect").performClick()
        compose.onNodeWithText("Accept & Connect").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Cancel").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(0, cancellations)
        }
        compose.runOnIdle { state.value = state.value.copy(addingContact = false, error = "Connection failed. Try again.") }
        compose.onNodeWithText("Connection failed. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    private fun confirmation(): ContactsUiState = ContactsUiState(pendingInviteValidation =
        InviteValidationResult.Valid(ContactInviteV1(
            inviteId = "isolated-ui-invite",
            displayName = "Owned test phone",
            identitySigningPublicKey = ByteArray(32),
            identityEncryptionPublicKey = ByteArray(32),
            bootstrapEphemeralPublicKey = ByteArray(32),
            signature = ByteArray(64)
        ), "TEST SAFETY FINGERPRINT"))
}
