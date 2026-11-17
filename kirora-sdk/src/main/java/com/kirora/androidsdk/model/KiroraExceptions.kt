package com.kirora.androidsdk.model

/** Every error the SDK throws, so an app can catch them all with one type. */
public open class KiroraError(message: String, cause: Throwable? = null) : Exception("Kirora: $message", cause)

/** `fetchUserToken` failed, didn't answer within 30 seconds, or there is no way to get a token. */
public class UserTokenError(
    message: String,
    cause: Throwable? = null
) : KiroraError(message, cause)

/** Kirora refused a call. [code] is Kirora's error code, when it sent one. */
public class KiroraApiError(
    public val status: Int,
    public val code: String?,
    message: String,
    /** From the `Retry-After` header, when there was one. */
    public val retryAfterMs: Long? = null
) : KiroraError(message) {
    /** True for 408, 425, 429 and 5xx: the same call may succeed later. */
    public val retryable: Boolean
        get() = status == 408 || status == 425 || status == 429 || status >= 500
}
