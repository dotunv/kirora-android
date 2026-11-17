package com.kirora.androidsdk

import com.google.gson.Gson
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.PushPermission
import com.kirora.androidsdk.model.PushStatus
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushTracker
import com.kirora.androidsdk.utils.GlobalPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Push registration, the device's own push choice, app opens and `trackingUrl` reports ("Push", "Push reporting"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class PushConformanceTest : KiroraTestBase() {
    private val token = jwt("user-a")
    private val pushOptions = KiroraOptions(trackSessions = false, push = KiroraPushOptions())

    @Test
    fun registrationPutsTheDeviceIdInThePathAndSendsTheDeviceDetails() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)

        val (path, request) = fake.deviceRegistrations.single()
        assertEquals(kirora.deviceId, path)
        val body = Gson().toJsonTree(request).asJsonObject
        assertFalse(body.has("externalInstallationId"))
        assertEquals("granted", body["permission"].asString)
        assertEquals("fcm_token", body["targetKind"].asString)
        assertEquals("fcm-1", body["target"].asString)
        assertEquals(FakeKiroraApiService().pushApp!!.pushAppId, body["pushAppId"].asString)
        assertTrue(body.has("osVersion") && body.has("locale") && body.has("timezone"))
        assertEquals("Bearer $token", fake.registrationAuthorizations.single())
    }

    @Test
    fun aSignedInStartCostsNoPushCallsUntilTheUserTokenOrDayChanges() = runBlocking {
        start(pushOptions).identify(token)
        // A new process for the same user, token and details: nothing to tell Kirora.
        start(pushOptions, clearQueue = false).identify(token)
        assertEquals(1, fake.deviceRegistrations.size)
        assertEquals(1, fake.pushAppQueries.size)

        val kirora = Kirora.active!!
        kirora.onNewPushToken("fcm-2")
        waitUntil(message = "registered with the new token") { fake.deviceRegistrations.size == 2 }
        assertEquals("fcm-2", fake.deviceRegistrations.last().second.target)

        kirora.identify(jwt("user-b"))
        assertEquals(3, fake.deviceRegistrations.size)
    }

    @Test
    fun anotherPushAppKindIsNotRegistered() = runBlocking {
        fake.pushApp = fake.pushApp!!.copy(targetKind = "apns_token")
        start(pushOptions).identify(token)
        assertTrue(fake.deviceRegistrations.isEmpty())
    }

    @Test
    fun pushStatusAndTheDevicesOwnChoice() = runBlocking {
        val kirora = start(pushOptions)
        assertEquals(PushStatus.DISABLED, kirora.getPushStatus()) // signed out
        kirora.identify(token)
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())

        kirora.setPushEnabled(false)
        assertEquals(kirora.deviceId to "Bearer $token", fake.deviceDeletions.single())
        assertEquals(PushStatus.DISABLED, kirora.getPushStatus())

        // The choice is the device's: it holds across sign-ins.
        kirora.logout()
        kirora.identify(token)
        assertEquals(1, fake.deviceRegistrations.size)

        kirora.setPushEnabled(true)
        assertEquals(2, fake.deviceRegistrations.size)
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())
    }

    @Test
    fun aGrantedPromptTurnsTheDevicesPushBackOn() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.setPushEnabled(false)
        assertEquals(PushPermission.GRANTED, kirora.requestPushPermission(context))
        waitUntil(message = "registered again") { fake.deviceRegistrations.size == 2 }
    }

    @Test
    fun pushIsUnsupportedWhenOff() = runBlocking {
        val kirora = start()
        assertEquals(PushStatus.UNSUPPORTED, kirora.getPushStatus())
        assertEquals(PushPermission.UNSUPPORTED, kirora.requestPushPermission(context))
    }

    @Test
    fun anAppOpenGoesOnlyForARegisteredDeviceWithItsPushApp() = runBlocking {
        val withoutPush = start()
        withoutPush.identify(token)
        withoutPush.appOpens!!.onAppOpen()
        delay(100)
        assertTrue(fake.appOpens.isEmpty())

        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the app open") { fake.appOpens.isNotEmpty() }
        val (path, body) = fake.appOpens.single()
        assertEquals(kirora.deviceId, path)
        assertEquals(fake.pushApp!!.pushAppId, body.pushAppId)

        // At most one in 30 minutes.
        kirora.appOpens!!.onAppOpen()
        delay(100)
        assertEquals(1, fake.appOpens.size)
    }

    @Test
    fun aLaunchBeforeSignInIsReportedOnceTheDeviceIsRegistered() = runBlocking {
        val kirora = start(pushOptions)
        kirora.appOpens!!.onAppOpen()
        delay(50)
        assertTrue(fake.appOpens.isEmpty())
        kirora.identify(token)
        waitUntil(message = "the held app open") { fake.appOpens.size == 1 }
    }

    @Test
    fun anAppOpenRefusedWith404IsDroppedAndOneThatFailsWaits() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        fake.appOpenResponder = { FakeKiroraApiService.error(503) }
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the failed attempt") { fake.appOpens.size == 1 }
        delay(50)
        assertEquals(1, kirora.databaseForTesting!!.appOpenDao().count())

        fake.appOpenResponder = { FakeKiroraApiService.error(404, "NOT_FOUND") }
        kirora.appOpens!!.onNetworkAvailable()
        waitUntil(message = "the 404") { fake.appOpens.size == 2 }
        waitUntil(message = "dropped") { kirora.databaseForTesting!!.appOpenDao().count() == 0 }
    }

    @Test
    fun noAppOpenWhileTrackingIsRefused() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.setTrackingConsent(false)
        kirora.appOpens!!.onAppOpen()
        delay(100)
        assertTrue(fake.appOpens.isEmpty())
    }

    @Test
    fun deliveredIsReportedOnceAndRetriedOnce() = runBlocking {
        val kirora = start(pushOptions)
        var calls = 0
        fake.trackingResponder = {
            calls++
            FakeKiroraApiService.error(503)
        }
        val url = "https://api.kirora.io/t/p/abc"
        KiroraPushTracker.report(context, kirora.options.apiUrl, url, KiroraPushTracker.DELIVERED, fake)
        KiroraPushTracker.report(context, kirora.options.apiUrl, url, KiroraPushTracker.DELIVERED, fake)
        waitUntil(message = "two attempts") { calls == 2 }
        delay(100)
        assertEquals(2, calls)
        assertEquals("delivered", fake.trackingReports.first().second.event)
    }

    @Test
    fun aRefusedReportIsNotRetriedAndAPushWithoutTrackingUrlReportsNothing() = runBlocking {
        val kirora = start(pushOptions)
        fake.trackingResponder = { FakeKiroraApiService.error(400) }
        KiroraPushTracker.report(context, kirora.options.apiUrl, "/t/p/xyz", KiroraPushTracker.OPENED, fake)
        KiroraPushTracker.report(context, kirora.options.apiUrl, null, KiroraPushTracker.OPENED, fake)
        delay(200)
        assertEquals(1, fake.trackingReports.size)
        assertEquals("opened", fake.trackingReports.single().second.event)
    }

    @Test
    fun onlyKirorasOwnTrackingHostIsCalled() {
        val base = "https://api.kirora.io".toHttpUrl()
        assertEquals("https://api.kirora.io/t/p/abc", KiroraPushTracker.trusted(base, "/t/p/abc").toString())
        assertNull(KiroraPushTracker.trusted(base, "https://evil.example.com/t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://api.kirora.io/other/path"))
    }

    @Test
    fun theFcmTokenComesFromTheBridgeAndIsKept() = runBlocking {
        start(pushOptions).identify(token)
        assertEquals("fcm-1", GlobalPreferences(context).getPushToken())
    }
}
