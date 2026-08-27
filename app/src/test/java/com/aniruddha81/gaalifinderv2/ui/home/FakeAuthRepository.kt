package com.aniruddha81.gaalifinderv2.ui.home

import androidx.activity.ComponentActivity
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import com.aniruddha81.gaalifinderv2.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A session that tests can move between guest and signed-in without touching Appwrite. */
class FakeAuthRepository(initial: AuthState = AuthState.Guest) : AuthRepository {

    private val _authState = MutableStateFlow(initial)
    override val authState: StateFlow<AuthState> = _authState

    override suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser> {
        val user = TEST_USER
        _authState.value = AuthState.SignedIn(user, UserProfile.free(user.id))
        return DataResult.Success(user)
    }

    override suspend fun signOut(): DataResult<Unit> {
        _authState.value = AuthState.Guest
        return DataResult.Success(Unit)
    }

    override suspend fun refresh(): AuthState = _authState.value

    override suspend fun refreshProfile(): DataResult<UserProfile> =
        DataResult.Success(UserProfile.free(_authState.value.userOrNull?.id.orEmpty()))

    /** Drives the state directly, for tests that need a particular session up front. */
    fun setSignedIn(user: AuthUser = TEST_USER, profile: UserProfile = UserProfile.free(user.id)) {
        _authState.value = AuthState.SignedIn(user, profile)
    }

    fun setGuest() {
        _authState.value = AuthState.Guest
    }

    companion object {
        val TEST_USER = AuthUser(id = "u1", name = "Ada", email = "ada@example.com")
    }
}
