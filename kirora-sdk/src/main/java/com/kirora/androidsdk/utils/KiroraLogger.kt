package com.kirora.androidsdk.utils

import android.util.Log
import com.kirora.androidsdk.model.LogLevel
import java.util.concurrent.CopyOnWriteArrayList

internal data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null
)

/**
 * SDK diagnostics. Nothing is emitted (to logcat or to listeners) above the configured level.
 * The SDK never logs user tokens, push tokens or event payloads.
 */
internal object KiroraLogger {
    private const val TAG = "KiroraSDK"

    @Volatile
    var logLevel: LogLevel = LogLevel.ERROR

    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun addLogListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeLogListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    /** [priority] is the logcat priority; visibility follows [level] (`error` or `debug`). */
    internal fun log(level: LogLevel, message: String, throwable: Throwable? = null, priority: Int = Log.DEBUG) {
        if (level == LogLevel.NONE || logLevel < level) return
        try {
            Log.println(priority, TAG, if (throwable == null) message else "$message\n${Log.getStackTraceString(throwable)}")
        } catch (_: Throwable) {
        }
        if (listeners.isEmpty()) return
        val entry = LogEntry(level = level, message = message, throwable = throwable)
        listeners.forEach { listener ->
            try {
                listener(entry)
            } catch (_: Exception) {
            }
        }
    }

    internal fun d(message: String) = log(LogLevel.DEBUG, message)

    internal fun i(message: String) = log(LogLevel.DEBUG, message, priority = Log.INFO)

    internal fun w(message: String, throwable: Throwable? = null) =
        log(LogLevel.DEBUG, message, throwable, Log.WARN)

    internal fun e(message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, message, throwable, Log.ERROR)
}
