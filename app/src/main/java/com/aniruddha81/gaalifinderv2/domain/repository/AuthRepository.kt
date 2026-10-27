package com.aniruddha81.gaalifinderv2.domain.repository

import androidx.activity.ComponentActivity
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Who is signed in, and how that changes.
 *
 * Exposed as a [StateFlow] rather than a suspend getter so every screen observes one shared
 * truth instead of each caching its own answer.
 */
interface AuthRepository {

    val authState: StateFlow<AuthState>

    /**
     * Shows Google's account chooser and signs in with the result.
     *
     * Needs a [ComponentActivity] because the chooser is a system sheet anchored to it — a plain
     * application `Context` cannot host that.
     */
    suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser>

    suspend fun signInWithEmail(email: String, password: String): DataResult<AuthUser>

    /** Creates the account and signs it in; a verification link is mailed to [email]. */
    suspend fun register(email: String, password: String, displayName: String): DataResult<AuthUser>

    /** Mails a password-reset link if an account exists; the result never says whether it does. */
    suspend fun requestPasswordReset(email: String): DataResult<Unit>

    /** Mails a fresh verification link to the signed-in user. */
    suspend fun resendVerification(): DataResult<Unit>

    suspend fun signOut(): DataResult<Unit>

    /** Re-reads the session from the server, e.g. after returning to the app. */
    suspend fun refresh(): AuthState

    /** Re-reads only the plan, for when an admin has just granted premium. */
    suspend fun refreshProfile(): DataResult<UserProfile>
}
