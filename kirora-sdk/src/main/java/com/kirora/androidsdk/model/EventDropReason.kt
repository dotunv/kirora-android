package com.kirora.androidsdk.model

/** Why Kirora gave up on an event; logged. */
internal enum class EventDropReason {
    /** The device queue was over 1,000 events or 1,000,000 bytes and the oldest were removed. */
    QUEUE_FULL,

    /** The event could not be queued: a blank name, values that are not JSON, or larger than the queue. */
    INVALID,

    /** Kirora refused the event and sending it again would be refused the same way. */
    REJECTED,

    /** A signed-out user's events could not be sent before sign-out completed. */
    UNDELIVERABLE
}
