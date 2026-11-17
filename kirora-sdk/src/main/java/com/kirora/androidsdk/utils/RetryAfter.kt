package com.kirora.androidsdk.utils

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kirora.androidsdk.model.KiroraApiError
import okhttp3.Headers
import retrofit2.Response

internal object RetryAfter {
    /**
     * The longest wait kept: far beyond any real `Retry-After`, yet small enough that adding it to
     * the clock can't overflow. A longer one is kept at this, never cut to something sooner.
     */
    const val MAX_MS: Long = Long.MAX_VALUE / 4

    /** `Retry-After` in milliseconds from now, in seconds or as an HTTP date; null when absent, malformed or past. */
    fun parse(headers: Headers?, now: Long = System.currentTimeMillis()): Long? {
        val value = headers?.get("Retry-After")?.trim() ?: return null
        val ms = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            // Too many digits for a Long is still a wait, and a long one.
            val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
            if (seconds >= MAX_MS / 1000) MAX_MS else seconds * 1000
        } else {
            headers.getDate("Retry-After")?.let { it.time - now } ?: return null
        }
        return if (ms > 0) ms.coerceAtMost(MAX_MS) else null
    }
}

/** Kirora's `{ error: { code } }`, when the answer has one. Reads the error body, so call once. */
internal fun Response<*>.errorCode(): String? = try {
    errorBody()?.string()?.let { text -> JsonParser.parseString(text).asJsonObject.errorCode() }
} catch (_: Exception) {
    null
}

/** The non-blank string `code` of a `{ error: { code } }` object, or null when there isn't one. */
internal fun JsonObject.errorCode(): String? =
    ((get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.asString?.takeIf { it.isNotBlank() }

internal fun Response<*>.toApiError(): KiroraApiError {
    val code = errorCode()
    return KiroraApiError(code(), code, "Kirora answered HTTP ${code()}${code?.let { " $it" } ?: ""}", RetryAfter.parse(headers()))
}

/** `{ data: … }`, Kirora's answer envelope. */
internal fun JsonElement?.data(): JsonElement? = try {
    this?.asJsonObject?.get("data")
} catch (_: Exception) {
    null
}
