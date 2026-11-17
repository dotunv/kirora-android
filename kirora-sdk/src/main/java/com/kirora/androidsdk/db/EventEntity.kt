package com.kirora.androidsdk.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A behavioural event waiting to be sent. `seq` gives a strict, tie-free delivery order. The
 * owner is kept on the device only, never sent: Kirora derives the contact from the token.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["owner"])]
)
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val idempotencyKey: String,
    val name: String,
    val propertiesJson: String,
    val contextJson: String,
    val occurredAt: Long,
    /** `sub` of the user selected when the event was tracked; null until a sign-in adopts it. */
    val owner: String? = null,
    /** The event's serialized size in UTF-8 bytes, ownership included, for the queue's byte budget. */
    val sizeBytes: Int
)
