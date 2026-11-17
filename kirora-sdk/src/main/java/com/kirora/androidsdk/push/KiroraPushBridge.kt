package com.kirora.androidsdk.push

import android.content.Context

/**
 * The Firebase-specific half of push, implemented by the optional `android-sdk-push` artifact
 * (`com.kirora.androidsdk.push.fcm.FcmPushBridge`) and found by name when `push = true`.
 * Not meant to be implemented by apps.
 */
@com.kirora.androidsdk.KiroraInternalApi
public interface KiroraPushBridge {
    /** The current FCM registration token, or null when Firebase has none. */
    public suspend fun fetchToken(): String?

    /** Shows the Android 13+ notification prompt; reports null when it was dismissed unanswered. */
    public fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit)
}
