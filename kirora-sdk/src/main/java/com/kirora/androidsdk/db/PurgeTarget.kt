package com.kirora.androidsdk.db

/** A table of behavioural rows that a refused deletion can be owed against (see `PendingPurges`). */
internal interface PurgeTarget {
    suspend fun purgeAll()

    /** Returns how many rows were deleted. */
    suspend fun purgeOwner(owner: String): Int
}
