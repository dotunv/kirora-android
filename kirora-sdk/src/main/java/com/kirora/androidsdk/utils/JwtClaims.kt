package com.kirora.androidsdk.utils

import android.util.Base64
import org.json.JSONObject

/** Reads the (unverified) `sub` and `exp` claims: who is signed in, and when the token runs out. */
internal object JwtClaims {
    fun subject(token: String?): String? = payload(token)?.optString("sub")?.ifEmpty { null }

    /** When the token expires, in epoch milliseconds, or null when it has no `exp`. */
    fun expiresAtMillis(token: String?): Long? {
        val exp = payload(token)?.optLong("exp", 0L) ?: return null
        return if (exp > 0) exp * 1000 else null
    }

    private fun payload(token: String?): JSONObject? {
        if (token == null) return null
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }
}
