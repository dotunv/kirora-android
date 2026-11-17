package com.kirora.androidsdk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface AppOpenDao : PurgeTarget {

    @Insert
    suspend fun insert(open: AppOpenEntity): Long

    @Query("SELECT * FROM app_opens WHERE owner = :owner ORDER BY id ASC LIMIT :limit")
    suspend fun oldestFor(owner: String, limit: Int): List<AppOpenEntity>

    @Query("DELETE FROM app_opens WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM app_opens WHERE occurredAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM app_opens WHERE owner = :owner")
    suspend fun deleteOwnedBy(owner: String): Int

    @Query("DELETE FROM app_opens")
    suspend fun clearAll()

    override suspend fun purgeAll() = clearAll()

    override suspend fun purgeOwner(owner: String) = deleteOwnedBy(owner)

    @Query("DELETE FROM app_opens WHERE id NOT IN (SELECT id FROM app_opens ORDER BY id DESC LIMIT :maxSize)")
    suspend fun trimToSize(maxSize: Int)

    @Query("SELECT COUNT(*) FROM app_opens")
    suspend fun count(): Int

    @Transaction
    suspend fun insertAndTrim(open: AppOpenEntity, maxSize: Int) {
        insert(open)
        trimToSize(maxSize)
    }
}
