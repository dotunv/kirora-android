package com.kirora.androidsdk.model

/**
 * A snapshot of the client for an app's support and debug screens, from
 * [com.kirora.androidsdk.Kirora.getState]. Read-only and never sent anywhere.
 */
public data class KiroraState(
    /** The client is set up and not destroyed. */
    val isInitialized: Boolean,
    /** A user is signed in. */
    val isIdentified: Boolean,
    /** The id sent in `context.device.id`, or null when there is none (after tracking consent was withdrawn, say). */
    val deviceId: String?,
    /** The current visit, or null. */
    val sessionId: String?,
    /**
     * The FCM token in this device's current registration for the signed-in user; null while there
     * is none (before sign-in, after `logout()`, while `setPushEnabled(false)` holds, during a detach,
     * once a denied permission has been reported, after a failed registration), even when Firebase
     * holds a token. Notifications turned off in system settings show here at the next `identify()`.
     */
    val pushToken: String?,
    /** Whether behavioural tracking is allowed now. */
    val trackingConsent: Boolean,
    /**
     * The signed-in user's marketing push choice as this device last recorded it with Kirora: saved
     * only once Kirora accepts `setPushConsent` (a refused call keeps the previous value). False when
     * there is none; cleared at `logout()` and when another user signs in.
     */
    val pushConsent: Boolean,
    /** Events waiting on the device, whoever they belong to. */
    val queuedEventCount: Int,
)
