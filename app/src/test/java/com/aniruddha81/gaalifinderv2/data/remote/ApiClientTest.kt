package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class MemoryTokenStore(var token: String? = null) : TokenStore {
    override fun refreshToken() = token
    override fun saveRefreshToken(token: String) { this.token = token }
    override fun clear() { token = null }
}

class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: MemoryTokenStore
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = MemoryTokenStore("rt-1")
        api = ApiClient(OkHttpClient(), tokens, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setBody(body)

    private fun session(access: String, refresh: String) =
        """{"accessToken":"$access","refreshToken":"$refresh","expiresIn":900,
           "user":{"id":"u1","email":"a@b.co","displayName":"Ann","emailVerified":true}}"""

    @Test
    fun `expired access token is refreshed once and the request retried`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/auth/refresh" -> json(200, session("at-2", "rt-2"))
                request.getHeader("Authorization") == "Bearer at-2" -> json(200, """{"ok":true}""")
                else -> json(401, """{"error":"unauthorized"}""")
            }
        }

        val result = api.json("GET", "/v1/me")

        assertTrue(result.getBoolean("ok"))
        assertEquals("the refresh token must rotate", "rt-2", tokens.token)
    }

    @Test
    fun `a rejected refresh token signs the user out locally`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                json(401, """{"error":"invalid_token"}""")
        }

        val error = runCatching { api.json("GET", "/v1/me") }.exceptionOrNull() as AppErrorException

        assertEquals(AppError.NotSignedIn, error.error)
        assertNull(tokens.token)
    }

    @Test
    fun `parallel calls with an expired token share one refresh`() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    json(200, session("at-2", "rt-2"))
                }
                request.getHeader("Authorization") == "Bearer at-2" -> json(200, "{}")
                else -> json(401, """{"error":"unauthorized"}""")
            }
        }

        List(6) { async { api.json("GET", "/v1/me") } }.awaitAll()

        assertEquals("rotating refresh tokens must not be spent twice", 1, refreshes.get())
    }

    @Test
    fun `server error codes become typed app errors`() = runBlocking {
        suspend fun errorOf(code: Int, body: String): AppError {
            server.enqueue(json(code, body))
            return (runCatching { api.json("POST", "/v1/auth/login", auth = false) }
                .exceptionOrNull() as AppErrorException).error
        }

        assertEquals(AppError.InvalidCredentials, errorOf(401, """{"error":"invalid_credentials"}"""))
        assertEquals(AppError.EmailTaken, errorOf(409, """{"error":"email_taken"}"""))
        assertEquals(AppError.EmailNotVerified, errorOf(403, """{"error":"email_not_verified"}"""))
        assertEquals(AppError.RateLimited, errorOf(429, """{"error":"rate_limited"}"""))

        val quota = errorOf(
            402,
            """{"error":"quota_exceeded","usedBytes":400,"limitBytes":500,"isPremium":true}""",
        ) as AppError.QuotaExceeded
        assertEquals(400L, quota.usedBytes)
        assertEquals(500L, quota.limitBytes)
        assertTrue(quota.isPremium)

        val big = errorOf(413, """{"error":"file_too_large","sizeBytes":300000}""") as AppError.FileTooLarge
        assertEquals(300000L, big.sizeBytes)

        assertTrue(errorOf(500, "<html>boom</html>") is AppError.Network)
    }

    @Test
    fun `listClips follows the cursor until the last page`() = runBlocking {
        fun clip(id: String) =
            """{"id":"$id","fileId":"$id.mp3","fileName":"$id.mp3","uploaderId":"u","uploaderName":"U",
               "sizeBytes":10,"createdAt":5,"likeCount":1,"dislikeCount":2}"""
        server.enqueue(json(200, """{"clips":[${clip("a")},${clip("b")}],"nextCursor":"5.b"}"""))
        server.enqueue(json(200, """{"clips":[${clip("c")}],"nextCursor":""}"""))

        val result = ApiAudioDataSource(api).listClips() as DataResult.Success

        assertEquals(listOf("a", "b", "c"), result.data.map { it.documentId })
        assertEquals(2, result.data.first().dislikeCount)
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("cursor=5.b"))
    }

    @Test
    fun `setReaction sends the wire value and returns the settled counts`() = runBlocking {
        server.enqueue(json(200, """{"likeCount":7,"dislikeCount":1}"""))
        tokensWithAccess()

        val result = ApiAudioDataSource(api).setReaction("c1", "u1", ReactionType.None)

        assertEquals(7 to 1, (result as DataResult.Success).data)
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("none", JSONObject(sent.body.readUtf8()).getString("kind"))
    }

    @Test
    fun `deleting a clip that is already gone counts as success`() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        tokensWithAccess()

        assertTrue(ApiAudioDataSource(api).deleteClip("c1", "f1") is DataResult.Success)
    }

    /** Puts an access token in memory so the call under test does not start with a refresh. */
    private fun tokensWithAccess() = api.adopt(JSONObject(session("at-1", "rt-1")))
}
