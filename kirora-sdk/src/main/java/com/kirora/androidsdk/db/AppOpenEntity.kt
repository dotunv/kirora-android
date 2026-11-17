package com.kirora.androidsdk.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An app open still to be reported (`POST /devices/{id}/app-opens`). Not an event, so it has its
 * own table and never counts against the event queue or the plan's events. Bound at capture to
 * the user and the push app the device was registered with.
 */
@Entity(tableName = "app_opens")
internal data class AppOpenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val pushAppId: String,
    val owner: String
)
