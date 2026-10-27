package com.aniruddha81.gaalifinderv2.data.remote

import androidx.activity.ComponentActivity
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile

/** Everything the app needs from the API's auth endpoints, expressed without HTTP leaking out. */
interface AuthDataSource {
    /**
     * Shows Google's account chooser and signs in with the ID token it returns. Needs the
     * hosting [ComponentActivity] because the chooser is a system sheet anchored to it.
     */
    suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser>

    suspend fun signInWithEmail(email: String, password: String): DataResult<AuthUser>

    /** Creates the account, signs it in, and mails a verification link. */
    suspend fun register(email: String, password: String, displayName: String): DataResult<AuthUser>

    /** Always succeeds from the caller's view; the server never reveals whether the email exists. */
    suspend fun requestPasswordReset(email: String): DataResult<Unit>

    suspend fun resendVerification(): DataResult<Unit>

    /** The signed-in user, or an [com.aniruddha81.gaalifinderv2.core.error.AppError.NotSignedIn] failure. */
    suspend fun currentUser(): DataResult<AuthUser>

    suspend fun signOut(): DataResult<Unit>

    /** Reads the server-managed plan; anything unreadable falls back to the free tier. */
    suspend fun loadProfile(userId: String): DataResult<UserProfile>
}
