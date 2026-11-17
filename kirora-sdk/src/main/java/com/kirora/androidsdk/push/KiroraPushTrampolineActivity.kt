package com.kirora.androidsdk.push

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.utils.KiroraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transparent Activity that is the destination of a notification and of its buttons (no service
 * or receiver in between), so it works under Android 12+'s trampoline restrictions. It handles taps directly,
 * ensuring compliance with Android 12+ (API 31+) notification trampoline restrictions.
 * It reports click/open tracking, dispatches SDK listeners, launches target deep links or
 * the launcher Activity, and finishes immediately.
 */
internal class KiroraPushTrampolineActivity : Activity() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var waiting = false
        try {
            waiting = processIntent(intent)
        } catch (e: Exception) {
            KiroraLogger.e("Error processing notification trampoline intent", e)
        } finally {
            if (!waiting) finish()
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    /** True while a wrapper's deep-link callback is being asked: the activity stays until it answers. */
    private fun processIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val extras = intent.extras ?: return false

        val data = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            if (key in KiroraPushDisplay.INTERNAL_EXTRAS) continue
            extras.get(key)?.let { data[key] = it.toString() }
        }
        val buttonIndex = if (extras.containsKey(EXTRA_BUTTON_INDEX)) extras.getInt(EXTRA_BUTTON_INDEX) else null
        val buttonUrl = extras.getString(EXTRA_BUTTON_URL)?.takeIf { it.isNotBlank() }
        // `link` is the push's `actionUrl` in Android's data-only payload.
        val actionUrl = buttonUrl ?: data["link"]?.takeIf { it.isNotBlank() }
        val trackingUrl = data["trackingUrl"]

        // Buttons do not dismiss the notification by themselves.
        if (buttonIndex != null) {
            NotificationManagerCompat.from(this).cancel(
                extras.getString(EXTRA_NOTIFICATION_TAG),
                extras.getInt(EXTRA_NOTIFICATION_ID, 0)
            )
        }

        // `opened` for a plain tap; `clicked` when the tap follows a link or a button.
        Kirora.handleNotificationTap(
            applicationContext,
            data,
            trackingUrl,
            clicked = buttonIndex != null || actionUrl != null
        )

        if (actionUrl != null && Kirora.active?.options?.handleDeepLink == null && openable(actionUrl) &&
            Kirora.awaitsWrapperCallbacks(applicationContext)
        ) {
            // A wrapper installed push at launch but hasn't set its deep-link handler yet: open the
            // app so its code runs, and hand the link over once it does (or after 10 seconds).
            launchApp(this)
            Kirora.openWhenWrapperReady(applicationContext, actionUrl)
            return false
        }

        if (actionUrl != null) {
            val wrapper = Kirora.wrapperDeepLinkHandler
            if (Kirora.active?.options?.handleDeepLink == null && wrapper != null && openable(actionUrl)) {
                // Asked across the wrapper's async bridge; the activity stays open (so it may still
                // start one) until the answer, or 1.5 seconds without one.
                mainScope.launch {
                    val handled = withTimeoutOrNull(Kirora.WRAPPER_ANSWER_MS) {
                        try {
                            wrapper(actionUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            KiroraLogger.e("Error in the wrapper's deep link handler", e)
                            false
                        }
                    } ?: false
                    try {
                        val activity = this@KiroraPushTrampolineActivity
                        when {
                            // The app routed it, perhaps with no screen showing: bring it forward.
                            handled -> bringAppForward(activity)
                            !openAction(activity, actionUrl) -> launchApp(activity)
                        }
                    } finally {
                        finish()
                    }
                }
                return true
            }
            if (openAction(this, actionUrl)) return false
        }
        launchApp(this)
        return false
    }

    companion object {
        const val EXTRA_BUTTON_INDEX: String = "kirora_button_index"
        const val EXTRA_BUTTON_TITLE: String = "kirora_button_title"
        const val EXTRA_BUTTON_URL: String = "kirora_button_url"
        const val EXTRA_ACTION_TYPE: String = "kirora_action_type"
        const val EXTRA_NOTIFICATION_TAG: String = "kirora_notification_tag"
        const val EXTRA_NOTIFICATION_ID: String = "kirora_notification_id"
        private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "data")

        internal fun openable(url: String): Boolean {
            val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
            return scheme !in BLOCKED_SCHEMES
        }

        /** An app link or custom scheme goes to this app first; a web page to whatever opens it. */
        internal fun openAction(context: Context, url: String): Boolean {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme in BLOCKED_SCHEMES) return false
            val options = Kirora.active?.options
            val handler = options?.handleDeepLink
            if (handler != null) {
                try {
                    if (handler(url)) {
                        bringAppForward(context)
                        return true
                    }
                } catch (e: Exception) {
                    KiroraLogger.e("Error in handleDeepLink", e)
                }
            }
            val allowed = options?.push?.allowedLinkSchemes
            if (allowed != null && allowed.none { it.equals(scheme, ignoreCase = true) }) {
                KiroraLogger.w("The notification's link scheme is not in allowedLinkSchemes; opening the app instead.")
                return false
            }
            val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val own = Intent(view).setPackage(packageName)
            try {
                if (own.resolveActivity(packageManager) != null) {
                    context.startActivity(own)
                    return true
                }
                if (scheme == "https" || scheme == "http") {
                    context.startActivity(view)
                    return true
                }
            } catch (e: Exception) {
                KiroraLogger.w("Could not open the notification's link; opening the app instead", e)
            }
            return false
        }

        /** Brings the app's task to the front as the launcher would, without resetting its screens. */
        internal fun bringAppForward(context: Context) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                KiroraLogger.w("Could not bring the app forward", e)
            }
        }

        internal fun launchApp(context: Context) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
            } else {
                KiroraLogger.w("No launch intent found for package: ${context.packageName}")
            }
        }
    }
}
