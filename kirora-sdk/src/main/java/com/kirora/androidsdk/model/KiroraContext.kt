package com.kirora.androidsdk.model

import com.google.gson.JsonObject
import com.kirora.androidsdk.utils.SdkInfo

/** The app, for push registration. */
internal data class AppInfo(
    val name: String = "",
    val version: String = "",
    val build: String = "",
    val namespace: String = ""
)

/** The device and its software; fixed for the life of the process. */
internal data class DeviceInfo(
    val manufacturer: String = "",
    val osVersion: String = ""
)

/** The screen when the event was tracked: it changes with rotation and resizing. */
internal data class ScreenInfo(
    val width: Int = 0,
    val height: Int = 0,
    val density: Float = 0f
)

/**
 * The `context` object of an event, in the shape the server's strict `eventContextSchema`
 * accepts: `sessionId`, `locale`, `timezone`, `device` (`id`, `manufacturer`, `type: "android"`),
 * `app`, `os`, `screen` and `library`. Nothing else: no device model (with screen, OS and locale it
 * helps fingerprint a device) and no network details.
 * A field that is unknown is left out.
 */
internal data class KiroraContext(
    val deviceId: String,
    val sessionId: String? = null,
    val locale: String = "",
    val timezone: String = "",
    val device: DeviceInfo = DeviceInfo(),
    val screen: ScreenInfo = ScreenInfo()
) {
    /** [app] is fixed for the process, so the queue adds it rather than each capture. */
    fun toJson(app: AppInfo): JsonObject = JsonObject().apply {
        sessionId?.let { addProperty("sessionId", it) }
        if (locale.isNotEmpty()) addProperty("locale", locale)
        if (timezone.isNotEmpty()) addProperty("timezone", timezone)
        add("device", JsonObject().apply {
            addProperty("id", deviceId)
            putIfSet("manufacturer", device.manufacturer)
            addProperty("type", "android")
        })
        // The app sets its own label and version name, so only these can run past the server's limits.
        add("app", JsonObject().apply {
            putIfSet("name", app.name.take(256))
            putIfSet("version", app.version.take(64))
            putIfSet("build", app.build)
            putIfSet("namespace", app.namespace)
        })
        add("os", JsonObject().apply {
            addProperty("name", "Android")
            putIfSet("version", device.osVersion)
        })
        val screenJson = JsonObject().apply {
            if (screen.width in 1..MAX_SCREEN) addProperty("width", screen.width)
            if (screen.height in 1..MAX_SCREEN) addProperty("height", screen.height)
            if (screen.density > 0f && screen.density <= MAX_DENSITY) addProperty("density", screen.density)
        }
        if (screenJson.size() > 0) add("screen", screenJson)
        add("library", JsonObject().apply {
            addProperty("name", SdkInfo.name)
            addProperty("version", SdkInfo.version)
        })
    }

    private fun JsonObject.putIfSet(key: String, value: String) {
        if (value.isNotEmpty()) addProperty(key, value)
    }

    private companion object {
        const val MAX_SCREEN = 100_000
        const val MAX_DENSITY = 10f
    }
}
