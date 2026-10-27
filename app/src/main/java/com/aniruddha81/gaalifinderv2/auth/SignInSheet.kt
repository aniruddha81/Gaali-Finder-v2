package com.aniruddha81.gaalifinderv2.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R

/** What the sheet can ask the rest of the app to do. */
class AuthActions(
    val signInWithGoogle: (ComponentActivity, () -> Unit) -> Unit,
    val signInWithEmail: (email: String, password: String, onDone: () -> Unit) -> Unit,
    val register: (email: String, password: String, name: String, onDone: () -> Unit) -> Unit,
    val requestPasswordReset: (email: String, onDone: () -> Unit) -> Unit,
    val resendVerification: () -> Unit,
    val signOut: () -> Unit,
)

private enum class SheetMode { SignIn, SignUp, Forgot }

/**
 * Sign-in for guests: Google, or email and password with sign-up and a reset link.
 *
 * [onSignedIn] runs once a session exists, so the caller can carry on to wherever the guest was
 * headed (the upload picker, say) without watching auth state itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(
    activity: ComponentActivity?,
    isBusy: Boolean,
    actions: AuthActions,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(SheetMode.SignIn) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    val emailOk = email.contains('@') && email.length >= 5
    val canSubmit = !isBusy && emailOk && when (mode) {
        SheetMode.SignIn -> password.isNotEmpty()
        SheetMode.SignUp -> password.length >= MIN_PASSWORD
        SheetMode.Forgot -> true
    }

    fun submit() {
        if (!canSubmit) return
        when (mode) {
            SheetMode.SignIn -> actions.signInWithEmail(email, password, onSignedIn)
            SheetMode.SignUp -> actions.register(email, password, name, onSignedIn)
            SheetMode.Forgot -> actions.requestPasswordReset(email) { mode = SheetMode.SignIn }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (mode) {
                        SheetMode.SignIn -> R.string.sign_in_title
                        SheetMode.SignUp -> R.string.sign_up_title
                        SheetMode.Forgot -> R.string.forgot_title
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            if (mode == SheetMode.Forgot) {
                Text(
                    text = stringResource(R.string.forgot_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = { activity?.let { actions.signInWithGoogle(it, onSignedIn) } },
                    enabled = !isBusy && activity != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sign_in_with_google)) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.or_divider),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
            }

            if (mode == SheetMode.SignUp) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.field_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            if (mode != SheetMode.Forgot) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    supportingText = if (mode == SheetMode.SignUp) {
                        { Text(stringResource(R.string.password_hint, MIN_PASSWORD)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }

            Button(
                onClick = ::submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when (mode) {
                            SheetMode.SignIn -> R.string.action_sign_in
                            SheetMode.SignUp -> R.string.action_create_account
                            SheetMode.Forgot -> R.string.action_send_reset
                        }
                    )
                )
            }

            if (mode == SheetMode.SignIn) {
                TextButton(
                    onClick = { mode = SheetMode.Forgot },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_forgot_password)) }
            }

            TextButton(
                onClick = {
                    mode = if (mode == SheetMode.SignUp) SheetMode.SignIn else SheetMode.SignUp
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (mode == SheetMode.SignUp) R.string.mode_have_account
                        else R.string.mode_new_here
                    )
                )
            }
        }
    }
}

/** Matches the server's lower bound; it re-checks, this only spares a doomed round trip. */
private const val MIN_PASSWORD = 10
