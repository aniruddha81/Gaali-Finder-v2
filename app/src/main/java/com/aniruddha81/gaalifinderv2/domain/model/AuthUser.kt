package com.aniruddha81.gaalifinderv2.domain.model

/**
 * The signed-in user, as the app thinks about them.
 *
 * Deliberately free of Appwrite types so the UI and ViewModels can be tested without a client.
 */
data class AuthUser(
    val id: String,
    val name: String,
    val email: String,
) {
    /** Appwrite lets the name be blank for some providers; the email is the sensible fallback. */
    val displayName: String
        get() = name.ifBlank { email.substringBefore('@') }.ifBlank { "Someone" }
}

/**
 * Who is using the app right now.
 *
 * [Unknown] exists so the UI can avoid flashing a "signed out" state during the very first
 * session probe on launch.
 */
sealed interface AuthState {
    data object Unknown : AuthState
    data object Guest : AuthState
    data class SignedIn(val user: AuthUser, val profile: UserProfile) : AuthState

    val userOrNull: AuthUser? get() = (this as? SignedIn)?.user
    val isSignedIn: Boolean get() = this is SignedIn
}

/**
 * The app-side plan record for a user, kept in the `user_profiles` collection because Appwrite
 * Auth users cannot carry custom fields the server can trust.
 *
 * Never written by the client — see the permissions checklist in the setup guide.
 */
data class UserProfile(
    val userId: String,
    val isPremium: Boolean = false,
    val premiumStorageLimitBytes: Long = StorageQuota.DEFAULT_PREMIUM_TOTAL_BYTES,
) {
    /** How much this user is allowed to store in total, across every clip they have uploaded. */
    val totalStorageLimitBytes: Long
        get() = if (isPremium) premiumStorageLimitBytes else StorageQuota.FREE_TOTAL_BYTES

    companion object {
        /** What a signed-in user gets before any `user_profiles` document exists for them. */
        fun free(userId: String) = UserProfile(userId = userId, isPremium = false)
    }
}

/** The one place the upload limits are defined, shared by the client checks and the UI copy. */
object StorageQuota {
    /** Per-file cap, applied to every user regardless of tier. */
    const val MAX_FILE_BYTES: Long = 204_800L // 200 KB

    /** Total cap across all of a free user's uploads. */
    const val FREE_TOTAL_BYTES: Long = 10_485_760L // 10 MB

    /** Placeholder premium cap, overridable per-user from the Console. */
    const val DEFAULT_PREMIUM_TOTAL_BYTES: Long = 104_857_600L // 100 MB
}
