package com.aniruddha81.gaalifinderv2.data.remote

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiAuthDataSource @Inject constructor(
    private val api: ApiClient,
) : AuthDataSource {

    override suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser> =
        runCatchingResult {
            ensureConfigured()
            if (ApiConfig.googleWebClientId.isEmpty()) {
                throw AppErrorException(AppError.RemoteNotConfigured)
            }

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(ApiConfig.googleWebClientId).build())
                .build()
            val credential = try {
                CredentialManager.create(activity).getCredential(activity, request).credential
            } catch (e: GetCredentialException) {
                // Dismissing the chooser lands here too; either way the user stays a guest.
                throw AppErrorException(AppError.SignInFailed(e))
            }
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw AppErrorException(AppError.SignInFailed())
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken

            startSession("/v1/auth/google", JSONObject().put("idToken", idToken))
        }

    override suspend fun signInWithEmail(email: String, password: String): DataResult<AuthUser> =
        runCatchingResult {
            ensureConfigured()
            startSession(
                "/v1/auth/login",
                JSONObject().put("email", email.trim()).put("password", password),
            )
        }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): DataResult<AuthUser> = runCatchingResult {
        ensureConfigured()
        startSession(
            "/v1/auth/register",
            JSONObject()
                .put("email", email.trim())
                .put("password", password)
                .put("displayName", displayName.trim()),
        )
    }

    override suspend fun requestPasswordReset(email: String): DataResult<Unit> = runCatchingResult {
        ensureConfigured()
        api.json("POST", "/v1/auth/forgot", JSONObject().put("email", email.trim()), auth = false)
        Unit
    }

    override suspend fun resendVerification(): DataResult<Unit> = runCatchingResult {
        ensureConfigured()
        api.json("POST", "/v1/auth/resend-verification")
        Unit
    }

    override suspend fun currentUser(): DataResult<AuthUser> = runCatchingResult {
        ensureConfigured()
        // A guest has no refresh token, which is a normal state rather than an error worth
        // showing, so it is reported as the typed "not signed in" case without any request.
        if (!api.hasSession) throw AppErrorException(AppError.NotSignedIn)
        api.json("GET", "/v1/me").getJSONObject("user").toAuthUser()
    }

    override suspend fun signOut(): DataResult<Unit> = runCatchingResult {
        // Best effort: even if the server cannot be reached, this device is signed out.
        api.storedRefreshToken()?.let { token ->
            runCatching {
                api.json("POST", "/v1/auth/logout", JSONObject().put("refreshToken", token), auth = false)
            }
        }
        api.forgetSession()
    }

    /**
     * Missing or unreadable plans fall back to the free tier rather than failing: that is both
     * the right answer for a user nobody has upgraded and the safe default if the read is denied.
     */
    override suspend fun loadProfile(userId: String): DataResult<UserProfile> = runCatchingResult {
        if (!api.isConfigured) return@runCatchingResult UserProfile.free(userId)
        runCatching {
            val me = api.json("GET", "/v1/me")
            val premium = me.optBoolean("isPremium")
            UserProfile(
                userId = userId,
                isPremium = premium,
                premiumStorageLimitBytes = me.optLong("limitBytes")
                    .takeIf { premium && it > 0 }
                    ?: StorageQuota.DEFAULT_PREMIUM_TOTAL_BYTES,
            )
        }.getOrElse { UserProfile.free(userId) }
    }

    private suspend fun startSession(path: String, body: JSONObject): AuthUser {
        val session = api.json("POST", path, body, auth = false)
        api.adopt(session)
        return session.getJSONObject("user").toAuthUser()
    }

    private fun ensureConfigured() {
        if (!api.isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
    }
}

private fun JSONObject.toAuthUser() = AuthUser(
    id = getString("id"),
    name = optString("displayName"),
    email = optString("email"),
    emailVerified = optBoolean("emailVerified", true),
)
