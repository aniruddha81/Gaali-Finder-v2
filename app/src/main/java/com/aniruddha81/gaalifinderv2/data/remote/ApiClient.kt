package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one door to the Gaali API.
 *
 * It attaches the access token, and when the server answers 401 it trades the stored refresh token
 * for a new pair and retries once. Server error codes become typed [AppError]s here, so nothing
 * above this layer ever sees an HTTP status.
 */
class ApiClient(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val baseUrl: String,
) {
    /** Short-lived, so it lives in memory only; the refresh token is what survives a restart. */
    @Volatile
    private var accessToken: String? = null

    /** Serialises refreshes: refresh tokens rotate, so two at once would trip reuse detection. */
    private val refreshLock = Mutex()

    val isConfigured: Boolean get() = baseUrl.isNotEmpty()

    /** True when a refresh token is stored, i.e. the user was signed in last time. */
    val hasSession: Boolean get() = tokens.refreshToken() != null

    /** JSON in, JSON out. An empty response body (204) comes back as an empty object. */
    suspend fun json(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
    ): JSONObject {
        val payload = body?.toString()?.toRequestBody(JSON)
        val bytes = call(auth) { it.url(baseUrl + path).method(method, payload.orEmptyFor(method)) }
        val text = String(bytes, Charsets.UTF_8)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** A public GET for raw bytes, used for audio files. */
    suspend fun bytes(path: String): ByteArray = call(auth = false) { it.url(baseUrl + path).get() }

    /** Multipart upload of one file part named "file". */
    suspend fun upload(path: String, fileName: String, bytes: ByteArray, mimeType: String): JSONObject {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("fileName", fileName)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val response = call(auth = true) { it.url(baseUrl + path).post(form) }
        return JSONObject(String(response, Charsets.UTF_8))
    }

    /** Adopts the tokens from a sign-in, sign-up or refresh response. */
    fun adopt(session: JSONObject) {
        accessToken = session.getString("accessToken")
        tokens.saveRefreshToken(session.getString("refreshToken"))
    }

    /** Forgets the session locally; revoking it on the server is the caller's job. */
    fun forgetSession() {
        accessToken = null
        tokens.clear()
    }

    fun storedRefreshToken(): String? = tokens.refreshToken()

    private suspend fun call(auth: Boolean, build: (Request.Builder) -> Request.Builder): ByteArray {
        var token = if (auth) accessToken ?: refresh(stale = null) else null
        var raw = exchange(token, build)
        if (auth && raw.code == 401) {
            token = refresh(stale = token)
            raw = exchange(token, build)
        }
        if (raw.code !in 200..299) throw AppErrorException(errorFor(raw.code, raw.body))
        return raw.body
    }

    /**
     * Swaps the refresh token for a new pair. [stale] is the access token that just failed: if
     * another coroutine already replaced it while this one waited for the lock, that newer token
     * is used instead of burning a second refresh token.
     */
    private suspend fun refresh(stale: String?): String = refreshLock.withLock {
        accessToken?.takeIf { it != stale }?.let { return it }

        val refreshToken = tokens.refreshToken() ?: throw AppErrorException(AppError.NotSignedIn)
        val body = JSONObject().put("refreshToken", refreshToken).toString().toRequestBody(JSON)
        val raw = exchange(null) { it.url("$baseUrl/v1/auth/refresh").post(body) }
        when {
            raw.code == 401 -> {
                forgetSession()
                throw AppErrorException(AppError.NotSignedIn)
            }
            raw.code !in 200..299 -> throw AppErrorException(errorFor(raw.code, raw.body))
        }
        adopt(JSONObject(String(raw.body, Charsets.UTF_8)))
        accessToken!!
    }

    private suspend fun exchange(token: String?, build: (Request.Builder) -> Request.Builder): Raw =
        suspendCancellableCoroutine { cont ->
            val builder = Request.Builder().apply { token?.let { header("Authorization", "Bearer $it") } }
            val call = http.newCall(build(builder).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        cont.resume(response.use { Raw(it.code, it.body.bytes()) })
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    private class Raw(val code: Int, val body: ByteArray)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** POST/PUT/PATCH must carry a body even when there is nothing to say. */
        fun RequestBody?.orEmptyFor(method: String): RequestBody? =
            this ?: if (method == "POST" || method == "PUT" || method == "PATCH") {
                ByteArray(0).toRequestBody(null)
            } else {
                null
            }
    }
}

/** Maps the API's `{"error": code, ...}` body onto the app's closed error set. */
internal fun errorFor(status: Int, body: ByteArray): AppError {
    val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
    return when (json?.optString("error")) {
        "quota_exceeded" -> AppError.QuotaExceeded(
            usedBytes = json.optLong("usedBytes"),
            limitBytes = json.optLong("limitBytes"),
            isPremium = json.optBoolean("isPremium"),
        )
        "file_too_large" -> AppError.FileTooLarge(json.optLong("sizeBytes"))
        "email_not_verified" -> AppError.EmailNotVerified
        "invalid_credentials" -> AppError.InvalidCredentials
        "email_taken" -> AppError.EmailTaken
        "weak_password" -> AppError.WeakPassword
        "invalid_email" -> AppError.InvalidEmail
        "rate_limited" -> AppError.RateLimited
        "unauthorized", "invalid_token" -> AppError.NotSignedIn
        "not_found" -> AppError.ClipFileMissing
        "invalid_google_token" -> AppError.SignInFailed()
        "unsupported_media", "empty_file", "invalid_request" -> AppError.UploadRejected
        else -> if (status >= 500) AppError.Network() else AppError.Unexpected()
    }
}
