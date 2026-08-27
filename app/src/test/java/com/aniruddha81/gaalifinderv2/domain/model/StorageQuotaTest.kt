package com.aniruddha81.gaalifinderv2.domain.model

import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tier-dependent limits, which the client and the server Function must agree on. */
class StorageQuotaTest {

    @Test
    fun `the documented limits are exactly what the spec asks for`() {
        assertEquals(204_800L, StorageQuota.MAX_FILE_BYTES)
        assertEquals(10_485_760L, StorageQuota.FREE_TOTAL_BYTES)
    }

    @Test
    fun `a free profile gets the free total limit`() {
        assertEquals(
            StorageQuota.FREE_TOTAL_BYTES,
            UserProfile.free("u1").totalStorageLimitBytes,
        )
    }

    @Test
    fun `a premium profile gets its own configured limit, not the free one`() {
        val profile = UserProfile(
            userId = "u1",
            isPremium = true,
            premiumStorageLimitBytes = 52_428_800L,
        )
        assertEquals(52_428_800L, profile.totalStorageLimitBytes)
    }

    @Test
    fun `premium falls back to the placeholder limit when none was set`() {
        val profile = UserProfile(userId = "u1", isPremium = true)
        assertEquals(StorageQuota.DEFAULT_PREMIUM_TOTAL_BYTES, profile.totalStorageLimitBytes)
    }

    @Test
    fun `an upload fits only while the running total stays within the limit`() {
        val usage = StorageUsage(usedBytes = 9_000_000, limitBytes = 10_000_000, isPremium = false)

        assertTrue(usage.hasRoomFor(1_000_000))
        assertFalse(usage.hasRoomFor(1_000_001))
    }

    @Test
    fun `landing exactly on the limit is allowed, not rejected`() {
        val usage = StorageUsage(usedBytes = 0, limitBytes = 100, isPremium = false)
        assertTrue(usage.hasRoomFor(100))
    }

    @Test
    fun `remaining space never reports a negative number`() {
        // An admin lowering a limit below what is already stored must not produce nonsense.
        val usage = StorageUsage(usedBytes = 500, limitBytes = 100, isPremium = false)
        assertEquals(0L, usage.remainingBytes)
        assertEquals(1f, usage.fractionUsed, 0.001f)
    }

    @Test
    fun `a zero limit yields zero progress rather than dividing by zero`() {
        val usage = StorageUsage(usedBytes = 10, limitBytes = 0, isPremium = false)
        assertEquals(0f, usage.fractionUsed, 0.001f)
    }
}
