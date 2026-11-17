package com.kirora.androidsdk.model

/** Result of [com.kirora.androidsdk.Kirora.requestPushPermission]. */
public enum class PushPermission(public val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    /** The prompt was dismissed without an answer. */
    UNANSWERED("unanswered"),
    UNSUPPORTED("unsupported")
}
