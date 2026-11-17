package com.kirora.androidsdk.push.fcm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.kirora.androidsdk.push.KiroraPushBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Found by name from the core SDK when `push = true`. */
internal class FcmPushBridge : KiroraPushBridge {

    override suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    override fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit) {
        KiroraPermissionActivity.request(context, onResult)
    }
}
