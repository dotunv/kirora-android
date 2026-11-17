package com.kirora.androidsdk.utils

import io.kirora.sdk.BuildConfig

/**
 * The `Kirora-SDK` header: `kirora-android/<version>`, or a wrapper's own name and version
 * (`kirora-react-native/<v>`, `kirora-flutter/<v>`) when React Native or Flutter drives this SDK.
 */
internal object SdkInfo {
    const val NAME = "kirora-android"
    val VERSION: String = BuildConfig.SDK_VERSION

    @Volatile
    var name: String = NAME
        private set

    @Volatile
    var version: String = VERSION
        private set

    val HEADER: String
        get() = "$name/$version"

    private val NAME_PATTERN = Regex("^[a-z0-9-]{1,40}$")
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z.+-]{1,40}$")

    /** False, changing nothing, for a name or version the server would refuse. */
    fun setWrapper(wrapperName: String, wrapperVersion: String): Boolean {
        if (!NAME_PATTERN.matches(wrapperName) || !VERSION_PATTERN.matches(wrapperVersion)) return false
        name = wrapperName
        version = wrapperVersion
        return true
    }

    fun resetForTesting() {
        name = NAME
        version = VERSION
    }
}
