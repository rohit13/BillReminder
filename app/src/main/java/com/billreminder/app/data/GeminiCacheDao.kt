package com.billreminder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeminiCacheDao {

    @Query("SELECT * FROM gemini_cache WHERE emailId = :emailId LIMIT 1")
    suspend fun getByEmailId(emailId: String): GeminiCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: GeminiCache)

    /** Purge entries older than [cutoff] millis — call periodically to keep DB lean. */
    @Query("DELETE FROM gemini_cache WHERE checkedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** Wipes the entire cache — use when forcing a full re-evaluation of all emails. */
    @Query("DELETE FROM gemini_cache")
    suspend fun clearAll()
}
