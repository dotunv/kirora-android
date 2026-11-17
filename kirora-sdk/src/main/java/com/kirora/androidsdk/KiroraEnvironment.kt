package com.kirora.androidsdk

import com.kirora.androidsdk.push.KiroraPushBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** What the SDK reads from its surroundings, so tests can control background work, time and Firebase. */
internal class KiroraEnvironment(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val clock: () -> Long = System::currentTimeMillis,
    /** Replaces the `android-sdk-push` bridge found by name. */
    val pushBridge: KiroraPushBridge? = null
)
