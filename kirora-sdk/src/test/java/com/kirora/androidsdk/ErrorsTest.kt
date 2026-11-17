package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraError
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.FakeKiroraApiService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Errors reach the app, all of base type KiroraError ("Methods", "Errors"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ErrorsTest : KiroraTestBase() {
    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    @Test
    fun identifyThrowsASetupErrorWithNoTokenAndNoFetchUserToken() = runBlocking {
        val kirora = start()
        val e = thrown<KiroraError> { runBlocking { kirora.identify() } }
        assertFalse("a setup error, not the token's", e is UserTokenError)
    }

    @Test
    fun identifyThrowsASetupErrorForAnApiUrlThatIsNotAnAddress() = runBlocking {
        val kirora = Kirora(context, KiroraOptions(clientKey = "kirora_pk_test", apiUrl = "not a url", trackSessions = false))
        val e = thrown<KiroraError> { runBlocking { kirora.identify(jwt("user-a")) } }
        assertTrue(e !is UserTokenError && e !is KiroraApiError)
    }

    @Test
    fun setPushConsentReturnsOnceRecorded() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        kirora.setPushConsent(true)
        assertTrue(fake.pushConsentRequests.single().granted)
    }

    @Test
    fun setPushConsentThrowsWhenNotRecorded() = runBlocking {
        val kirora = start()
        // No one signed in.
        val nobody = thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(nobody !is KiroraApiError)

        kirora.identify(jwt("user-a"))
        fake.consentResponder = { FakeKiroraApiService.error(503, "SERVICE_UNAVAILABLE", retryAfterSeconds = 7) }
        val refused = thrown<KiroraApiError> { runBlocking { kirora.setPushConsent(true) } }
        assertEquals(503, refused.status)
        assertEquals("SERVICE_UNAVAILABLE", refused.code)
        assertEquals(7_000L, refused.retryAfterMs)

        fake.consentResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        assertEquals(401, thrown<KiroraApiError> { runBlocking { kirora.setPushConsent(true) } }.status)

        fake.consentResponder = { throw IOException("offline") }
        val offline = thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(offline !is KiroraApiError && offline.cause is IOException)
        assertFalse(kirora.pushConsent)
    }

    @Test
    fun aRefusedTokenAsksFetchUserTokenOnceThenThrowsTheTokensError() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = { throw IllegalStateException("down") }))
        kirora.identify(jwt("user-a"))
        fake.consentResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        thrown<UserTokenError> { runBlocking { kirora.setPushConsent(true) } }
        Unit
    }

    @Test
    fun theCallbackFormHandsTheErrorToTheBridge() = runBlocking {
        val kirora = start()
        val answer = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { answer.complete(it) }
        assertTrue(answer.await() is KiroraError)

        kirora.identify(jwt("user-a"))
        val ok = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { ok.complete(it) }
        assertNull(ok.await())
    }

    @Test
    fun aDestroyedClientNeverClaimsTheConsentWasRecorded() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        kirora.destroy()
        thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(fake.pushConsentRequests.isEmpty())
    }

    @Test
    fun callbackFormsAnswerAfterDestroy() = runBlocking {
        val kirora = start()
        kirora.destroy()
        val consent = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { consent.complete(it) }
        assertTrue(consent.await() is KiroraError)
        val status = CompletableDeferred<com.kirora.androidsdk.model.PushStatus>()
        kirora.getPushStatusAsync { status.complete(it) }
        status.await()
        val flushed = CompletableDeferred<Unit>()
        kirora.flushAsync { flushed.complete(Unit) }
        flushed.await()
    }

    @Test
    fun overlappingConsentCallsSaveTheValueKiroraAppliedLast() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        var calls = 0
        fake.consentResponder = {
            // The first answer is the slow one: unserialised, it would land last.
            if (++calls == 1) Thread.sleep(300)
            retrofit2.Response.success(204, Unit)
        }
        val first = launch(kotlinx.coroutines.Dispatchers.IO) { kirora.setPushConsent(true) }
        kotlinx.coroutines.delay(50)
        kirora.setPushConsent(false)
        first.join()
        assertEquals(listOf(true, false), fake.pushConsentRequests.map { it.granted })
        assertFalse(kirora.pushConsent)
    }
}
