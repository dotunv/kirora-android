package com.kirora.androidsdk.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Default FCM entry point. A Kirora push (`kirora` = `"true"`) is drawn by the SDK in every app state;
 * any other push goes to [KiroraPush.setFallbackMessageHandler]. Apps that already have a
 * `FirebaseMessagingService` keep it and call [KiroraPush.handleMessage] and [KiroraPush.onNewToken].
 */
internal class KiroraFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        KiroraPush.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        KiroraPush.handleMessage(applicationContext, remoteMessage)
    }
}
