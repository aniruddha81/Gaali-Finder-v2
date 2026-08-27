package com.aniruddha81.gaalifinderv2.data.remote

import androidx.activity.ComponentActivity
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import io.appwrite.Client
import io.appwrite.Query
import io.appwrite.enums.OAuthProvider
import io.appwrite.services.Account
import io.appwrite.services.Databases
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the app needs from Appwrite Auth, expressed without Appwrite types leaking out. */
interface AuthDataSource {
    /**
     * Runs the Google OAuth flow. Suspends until the browser tab hands a session back, so the
     * caller can simply await it rather than polling for a session to appear.
     */
    suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser>

    /** The signed-in user, or a [AppError.NotSignedIn] failure when the session is gone. */
    suspend fun currentUser(): DataResult<AuthUser>

    suspend fun signOut(): DataResult<Unit>

    /** Reads the admin-managed plan record; absent means free tier. */
    suspend fun loadProfile(userId: String): DataResult<UserProfile>
}

@Singleton
class AppwriteAuthDataSource @Inject constructor(
    private val client: Client,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthDataSource {

    private val account by lazy { Account(client) }
    private val databases by lazy { Databases(client) }

    /**
     * The OAuth call itself must stay on the caller's context: it launches a browser tab against
     * [activity], and the SDK resumes it from the callback activity on the main thread.
     */
    override suspend fun signInWithGoogle(activity: ComponentActivity): DataResult<AuthUser> =
        runCatchingResult {
            ensureConfigured()

            // A stale anonymous session would make Appwrite reject the OAuth session as a
            // conflict, so it is cleared first. A failure here is fine — it usually just means
            // there was no session to delete.
            runCatching { account.deleteSession("current") }

            try {
                account.createOAuth2Session(
                    activity = activity,
                    provider = OAuthProvider.GOOGLE,
                )
            } catch (e: AppErrorException) {
                throw e
            } catch (e: Throwable) {
                throw AppErrorException(AppError.SignInFailed(e))
            }

            withContext(ioDispatcher) { account.get() }.toAuthUser()
        }

    override suspend fun currentUser(): DataResult<AuthUser> = withContext(ioDispatcher) {
        runCatchingResult {
            ensureConfigured()
            // `account.get()` on a guest returns 401, which is a normal state rather than an
            // error worth showing, so it is mapped to the typed "not signed in" case.
            val user = runCatching { account.get() }.getOrElse {
                throw AppErrorException(AppError.NotSignedIn)
            }
            user.toAuthUser()
        }
    }

    override suspend fun signOut(): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            ensureConfigured()
            account.deleteSession("current")
            Unit
        }
    }

    /**
     * Missing or unreadable profiles fall back to the free tier rather than failing.
     *
     * The collection is admin-write-only, so a user who has never been granted premium simply
     * has no document — and treating that as "free" is both correct and the safe default if the
     * read is ever denied.
     */
    override suspend fun loadProfile(userId: String): DataResult<UserProfile> =
        withContext(ioDispatcher) {
            runCatchingResult {
                val collection = AppwriteConfig.userProfilesCollectionId
                if (!AppwriteConfig.isConfigured || collection.isBlank()) {
                    return@runCatchingResult UserProfile.free(userId)
                }

                val documents = runCatching {
                    databases.listDocuments(
                        databaseId = AppwriteConfig.databaseId,
                        collectionId = collection,
                        queries = listOf(
                            Query.equal(AppwriteConfig.Profiles.USER_ID, userId),
                            Query.limit(1),
                        ),
                    ).documents
                }.getOrElse { return@runCatchingResult UserProfile.free(userId) }

                val data = documents.firstOrNull()?.data
                    ?: return@runCatchingResult UserProfile.free(userId)

                UserProfile(
                    userId = userId,
                    isPremium = data[AppwriteConfig.Profiles.IS_PREMIUM] as? Boolean == true,
                    premiumStorageLimitBytes =
                        (data[AppwriteConfig.Profiles.PREMIUM_STORAGE_LIMIT_BYTES] as? Number)
                            ?.toLong()
                            ?.takeIf { it > 0 }
                            ?: UserProfile.free(userId).premiumStorageLimitBytes,
                )
            }
        }

    private fun ensureConfigured() {
        if (!AppwriteConfig.isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
    }
}

private fun io.appwrite.models.User<Map<String, Any>>.toAuthUser() = AuthUser(
    id = id,
    name = name,
    email = email,
)
