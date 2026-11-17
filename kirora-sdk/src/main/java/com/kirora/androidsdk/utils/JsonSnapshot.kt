package com.kirora.androidsdk.utils

import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

/** Thrown by [JsonSnapshot], saying what kind of value it met but never the value. */
internal class NotJsonError(message: String) : Exception(message)

/**
 * Deep copies of app values made only of what JSON holds: objects with string keys, arrays,
 * strings, finite numbers, booleans and null. Anything JSON would change or lose (NaN, a cycle,
 * an arbitrary object) throws [NotJsonError] instead of being sent as something else.
 *
 * As the standard encoders write them: a date is its ISO 8601 string and an enum its name.
 */
internal object JsonSnapshot {
    private const val MAX_DEPTH = 64

    /** A copy of [map] as a JSON object. */
    fun of(map: Map<String, Any?>?): JsonObject {
        if (map == null) return JsonObject()
        val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return copy(map, ancestors, 0) as JsonObject
    }

    private fun copy(value: Any?, ancestors: MutableSet<Any>, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) throw NotJsonError("values nested too deeply")
        return when (value) {
            null -> JsonNull.INSTANCE
            is JsonElement -> value.deepCopy()
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Double -> if (value.isFinite()) JsonPrimitive(value) else throw NotJsonError("a number JSON has no form for")
            is Float -> if (value.isFinite()) JsonPrimitive(value) else throw NotJsonError("a number JSON has no form for")
            is Number -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name)
            is Date -> JsonPrimitive(formatIso8601(value.time))
            is Calendar -> JsonPrimitive(formatIso8601(value.timeInMillis))
            is CharSequence -> JsonPrimitive(value.toString())
            else -> container(value, ancestors, depth) ?: javaTime(value)
                ?: throw NotJsonError("a value of type ${value.javaClass.simpleName}")
        }
    }

    private fun container(value: Any, ancestors: MutableSet<Any>, depth: Int): JsonElement? {
        val array = when (value) {
            is IntArray -> return JsonArray().also { a -> value.forEach { a.add(it) } }
            is LongArray -> return JsonArray().also { a -> value.forEach { a.add(it) } }
            is ShortArray -> return JsonArray().also { a -> value.forEach { a.add(it) } }
            is ByteArray -> return JsonArray().also { a -> value.forEach { a.add(it) } }
            is BooleanArray -> return JsonArray().also { a -> value.forEach { a.add(it) } }
            is DoubleArray -> value.toList()
            is FloatArray -> value.toList()
            is CharArray -> value.map { it.toString() }
            is Array<*> -> value.asList()
            is Iterable<*> -> value
            is Map<*, *> -> null
            else -> return null
        }
        if (!ancestors.add(value)) throw NotJsonError("a circular reference")
        try {
            if (array != null) {
                return JsonArray().also { a -> array.forEach { a.add(copy(it, ancestors, depth + 1)) } }
            }
            val obj = JsonObject()
            (value as Map<*, *>).forEach { (key, member) ->
                if (key !is String) throw NotJsonError("a map key that is not a string")
                obj.add(key, copy(member, ancestors, depth + 1))
            }
            return obj
        } finally {
            ancestors.remove(value)
        }
    }

    /** `java.time` values print as ISO 8601; only looked for where the platform has them. */
    private fun javaTime(value: Any): JsonElement? {
        if (Build.VERSION.SDK_INT < 26) return null
        return if (value is java.time.temporal.TemporalAccessor) JsonPrimitive(value.toString()) else null
    }

    /** A detached Kotlin copy of a JSON object, for `beforeSend`. */
    fun toMap(obj: JsonObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        obj.entrySet().forEach { (key, value) -> map[key] = toValue(value) }
        return map
    }

    private fun toValue(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonObject -> toMap(element.asJsonObject)
        element.isJsonArray -> element.asJsonArray.map { toValue(it) }
        else -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber
                else -> primitive.asString
            }
        }
    }

    fun formatIso8601(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestamp))
    }

    fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}
