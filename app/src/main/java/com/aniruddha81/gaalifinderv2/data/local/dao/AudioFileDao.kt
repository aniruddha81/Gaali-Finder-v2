package com.aniruddha81.gaalifinderv2.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    /** Ordering happens in SQL so the list is stable without re-sorting on every emission. */
    @Query("SELECT * FROM audio_files ORDER BY LOWER(fileName) ASC")
    fun observeAll(): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM audio_files WHERE id = :id")
    suspend fun findById(id: Long): AudioFileEntity?

    /** Returns the new row id, or -1 when the insert was ignored as a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AudioFileEntity): Long

    @Update
    suspend fun update(entity: AudioFileEntity): Int

    @Delete
    suspend fun delete(entity: AudioFileEntity): Int

    @Query("SELECT EXISTS(SELECT 1 FROM audio_files WHERE fileName = :fileName COLLATE NOCASE)")
    suspend fun existsByFileName(fileName: String): Boolean

    /** Same as [existsByFileName] but ignoring one row, for validating a rename in place. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM audio_files " +
            "WHERE fileName = :fileName COLLATE NOCASE AND id != :excludingId)"
    )
    suspend fun existsByFileNameExcluding(fileName: String, excludingId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM audio_files WHERE source = :remoteId)")
    suspend fun existsByRemoteId(remoteId: String): Boolean

    @Query("SELECT * FROM audio_files WHERE durationMs <= 0 OR sizeBytes <= 0")
    suspend fun findWithMissingMetadata(): List<AudioFileEntity>

    @Query("UPDATE audio_files SET isNew = 0 WHERE id = :id")
    suspend fun markSeen(id: Long): Int
}
