package com.kirora.androidsdk.model

/** Answer of [com.kirora.androidsdk.Kirora.getPushStatus], for an app's "notifications on/off" setting. */
public enum class PushStatus(public val wireValue: String) {
    /** Permission granted and this device is registered for the signed-in user. */
    ENABLED("enabled"),

    /** Permission granted, but the device is not registered: turned off, signed out, or the registration failed. */
    DISABLED("disabled"),
    DENIED("denied"),
    UNANSWERED("unanswered"),
    UNSUPPORTED("unsupported")
}
