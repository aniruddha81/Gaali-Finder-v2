package com.aniruddha81.gaalifinderv2.auth

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Session state for the whole app, and the one place sign-in is started from.
 *
 * The state itself lives in [AuthRepository] — this only adapts it for Compose and serialises
 * the in-flight flags, so two screens observing auth can never disagree.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val clipRepository: AudioClipRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /**
     * Starts Google sign-in.
     *
     * [onSignedIn] runs only on success, which is what lets a caller say "and then open the
     * upload picker" without having to watch the state flow itself.
     */
    fun signIn(activity: ComponentActivity, onSignedIn: () -> Unit = {}) {
        // Guarded so a double tap on the FAB cannot launch two browser tabs.
        if (_isSigningIn.value) return
        _isSigningIn.value = true

        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(activity)) {
                is DataResult.Failure -> _events.tryEmit(AuthEvent.Failed(result.error))
                is DataResult.Success -> {
                    _events.tryEmit(AuthEvent.SignedIn(result.data.displayName))
                    onSignedIn()
                }
            }
            _isSigningIn.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            when (val result = authRepository.signOut()) {
                is DataResult.Failure -> _events.tryEmit(AuthEvent.Failed(result.error))
                is DataResult.Success -> {
                    // Reactions are that account's, so they must not linger on the cards for
                    // whoever signs in next.
                    clipRepository.clearLocalReactions()
                    _events.tryEmit(AuthEvent.SignedOut)
                }
            }
        }
    }

    /** Re-checks the session, e.g. when the app comes back to the foreground. */
    fun refresh() {
        viewModelScope.launch { authRepository.refresh() }
    }
}

/** One-shot auth outcomes, kept out of the state so they cannot re-fire on recomposition. */
sealed interface AuthEvent {
    data class SignedIn(val displayName: String) : AuthEvent
    data object SignedOut : AuthEvent
    data class Failed(val error: AppError) : AuthEvent
}
