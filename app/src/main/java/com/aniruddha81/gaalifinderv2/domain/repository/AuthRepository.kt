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
     * Runs the Google OAuth flow.
     *
     * Needs a [ComponentActivity] because Appwrite launches a browser tab and resumes through
     * the callback activity — a plain application `Context` cannot host that.
     */
    suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser>

    suspend fun signOut(): DataResult<Unit>

    /** Re-reads the session from the server, e.g. after returning to the app. */
    suspend fun refresh(): AuthState

    /** Re-reads only the plan record, for when an admin has just granted premium. */
    suspend fun refreshProfile(): DataResult<UserProfile>
}
