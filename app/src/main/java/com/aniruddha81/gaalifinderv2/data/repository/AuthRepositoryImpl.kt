package com.aniruddha81.gaalifinderv2.data.repository

import androidx.activity.ComponentActivity
import com.aniruddha81.gaalifinderv2.core.dispatcher.ApplicationScope
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.map
import com.aniruddha81.gaalifinderv2.data.remote.AuthDataSource
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import com.aniruddha81.gaalifinderv2.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of "who is signed in".
 *
 * Session state lives here rather than in a ViewModel so the home screen, the upload flow and
 * the quota checks all read the same value — a per-ViewModel copy could disagree about whether
 * the user is signed in mid-upload.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val remote: AuthDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Serialises session transitions. Without it, a refresh racing a sign-in could write the
     * stale "guest" result after the new session had already landed.
     */
    private val mutex = Mutex()

    init {
        // Probing at construction rather than on first collect means the plus button already
        // knows which flow to run by the time the user can reach it.
        scope.launch { refresh() }
    }

    override suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser> =
        mutex.withLock {
            when (val result = remote.signInWithGoogle(activity)) {
                is DataResult.Failure -> {
                    // A failed sign-in leaves the user exactly where they were: a guest.
                    _authState.value = AuthState.Guest
                    result
                }

                is DataResult.Success -> {
                    _authState.value = AuthState.SignedIn(result.data, loadProfile(result.data.id))
                    result
                }
            }
        }

    override suspend fun signOut(): DataResult<Unit> = mutex.withLock {
        remote.signOut().also { _authState.value = AuthState.Guest }
    }

    override suspend fun refresh(): AuthState = mutex.withLock {
        val state = when (val result = remote.currentUser()) {
            is DataResult.Failure -> AuthState.Guest
            is DataResult.Success -> AuthState.SignedIn(result.data, loadProfile(result.data.id))
        }
        _authState.value = state
        state
    }

    override suspend fun refreshProfile(): DataResult<UserProfile> {
        val user = _authState.value.userOrNull
            ?: return DataResult.Success(UserProfile.free(""))

        return remote.loadProfile(user.id).map { profile ->
            mutex.withLock {
                // Only apply it if the same user is still signed in — a sign-out during the
                // read must not resurrect their plan.
                val current = _authState.value
                if (current is AuthState.SignedIn && current.user.id == user.id) {
                    _authState.value = current.copy(profile = profile)
                }
            }
            profile
        }
    }

    /** A profile read that cannot fail: an unreadable or absent record means free tier. */
    private suspend fun loadProfile(userId: String): UserProfile =
        when (val result = remote.loadProfile(userId)) {
            is DataResult.Failure -> UserProfile.free(userId)
            is DataResult.Success -> result.data
        }
}
