package com.kirora.androidsdk

/**
 * Marks API that exists only so the Kirora artifacts can talk to each other. It is not part of the
 * supported surface and can change or disappear in any release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to the Kirora SDK and can change without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class KiroraInternalApi
