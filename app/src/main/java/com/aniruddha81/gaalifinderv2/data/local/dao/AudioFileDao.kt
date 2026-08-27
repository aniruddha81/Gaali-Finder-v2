package com.aniruddha81.gaalifinderv2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    /** Ordering happens in SQL so the list is stable without re-sorting on every emission. */
    @Query("SELECT * FROM audio_clips ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM audio_clips WHERE documentId = :id")
    suspend fun findById(id: String): AudioFileEntity?

    @Query("SELECT * FROM audio_clips")
    suspend fun findAll(): List<AudioFileEntity>

    @Update
    suspend fun update(entity: AudioFileEntity): Int

    @Query("DELETE FROM audio_clips WHERE documentId = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE audio_clips SET isNew = 0 WHERE documentId = :id")
    suspend fun markSeen(id: String): Int

    @Query("UPDATE audio_clips SET cachedPath = :path, durationMs = :durationMs WHERE documentId = :id")
    suspend fun setCached(id: String, path: String?, durationMs: Long): Int

    @Query("UPDATE audio_clips SET myReaction = :reaction, likeCount = :likeCount, dislikeCount = :dislikeCount WHERE documentId = :id")
    suspend fun setReaction(id: String, reaction: String?, likeCount: Int, dislikeCount: Int): Int

    /** Clears every user's reaction marker — used on sign-out, since it is per-account state. */
    @Query("UPDATE audio_clips SET myReaction = NULL")
    suspend fun clearAllReactions(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AudioFileEntity>)

    @Query("DELETE FROM audio_clips WHERE documentId NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>): Int

    @Query("DELETE FROM audio_clips")
    suspend fun deleteAll(): Int

    /**
     * Replaces the cached catalogue with what the server just returned, in one transaction so
     * the UI never observes a half-synced list.
     *
     * Locally-known state that the server does not carry — the "new" badge and the downloaded
     * copy — is merged back in from the existing rows rather than being reset on every sync.
     */
    @Transaction
    suspend fun replaceCatalogue(fresh: List<AudioFileEntity>) {
        val existing = findAll().associateBy { it.documentId }
        val merged = fresh.map { row ->
            val previous = existing[row.documentId]
            if (previous == null) {
                row
            } else {
                row.copy(
                    isNew = previous.isNew,
                    cachedPath = previous.cachedPath,
                    durationMs = if (row.durationMs > 0) row.durationMs else previous.durationMs,
                )
            }
        }
        upsertAll(merged)
        if (merged.isEmpty()) deleteAll() else deleteMissing(merged.map { it.documentId })
    }
}
