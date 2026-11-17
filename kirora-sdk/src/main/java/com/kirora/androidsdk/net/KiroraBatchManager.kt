package com.kirora.androidsdk.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kirora.androidsdk.db.EventEntity
import com.kirora.androidsdk.identity.Identity
import com.kirora.androidsdk.identity.SignedInUser
import com.kirora.androidsdk.model.EventDropReason
import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.models.BatchEventsRequest
import com.kirora.androidsdk.net.models.KiroraEventApiDto
import com.kirora.androidsdk.utils.JsonSnapshot
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.data
import com.kirora.androidsdk.utils.errorCode
import com.kirora.androidsdk.utils.toApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Sends queued events in batches, retrying with backoff. Each user's events only ever go with
 * that user's own token, and nothing is sent while behavioural tracking is disallowed.
 *
 * A successful answer alone settles nothing: each event is settled by its own result, matched by
 * idempotency key. An event Kirora accepted, or refused for good, is removed; one with a missing,
 * repeated or unreadable result, or a refusal a later attempt may not get, waits for a retry with
 * its original payload and key.
 */
internal class KiroraBatchManager(
    private val store: EventStore,
    @Volatile private var apiService: KiroraApiService,
    private val scope: CoroutineScope,
    private val identity: Identity,
    private val trackingAllowed: () -> Boolean,
    /** A sign-in whose token Kirora refused before ever accepting it gives back the events it adopted. */
    private val onTokenRefused: (SignedInUser) -> Unit = {},
    private val onDropped: (count: Int, reason: EventDropReason) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val sendMutex = Mutex()
    private val inFlight: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var retryDelayMs = FIRST_RETRY_MS

    /** No send before this (epoch ms), after a failure. */
    @Volatile
    private var notBefore = 0L

    /** True when [notBefore] is the server's `Retry-After`, which nothing may cut short. */
    @Volatile
    private var delayFromServer = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var consecutiveAuthFailures = 0

    private var timer: Job? = null
    private var timerAt = 0L

    fun updateApiService(service: KiroraApiService) {
        apiService = service
    }

    /** After an event is queued: send at once if 20 wait, otherwise within 30 seconds. */
    fun onEventQueued() {
        val user = identity.user ?: return
        scope.launch {
            val waiting = try {
                store.countFor(user.subject)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            if (waiting >= FLUSH_AT_EVENTS) flush() else schedule(clock() + FLUSH_INTERVAL_MS)
        }
    }

    /**
     * Attempts to send the signed-in user's waiting events now. [waiveBackoff] skips the SDK's own
     * backoff, never the server's `Retry-After`. Returns when this attempt stops, which does not
     * mean every event was accepted.
     */
    suspend fun flush(waiveBackoff: Boolean = false) {
        if (destroyed) return
        val job = scope.launch {
            sendMutex.withLock { sendCurrent(waiveBackoff) }
        }
        inFlight.add(job)
        try {
            job.join()
        } finally {
            inFlight.remove(job)
        }
    }

    /**
     * Sends [user]'s events with [user]'s own token, for a sign-out still under way; the caller
     * bounds it with its deadline. Never refreshes the token.
     */
    suspend fun flushAs(user: SignedInUser) {
        if (destroyed || !trackingAllowed()) return
        if (clock() < notBefore && delayFromServer) return
        // Tracked, so a consent withdrawal or destroy() aborts it as it does any other send.
        val job = scope.launch {
            sendMutex.withLock {
                deliver(user, refreshAllowed = false) { !destroyed && trackingAllowed() }
            }
        }
        inFlight.add(job)
        try {
            job.join()
        } catch (e: CancellationException) {
            // Logout's deadline passed: stop the send rather than leave it running.
            job.cancel()
            throw e
        } finally {
            inFlight.remove(job)
        }
    }

    /** Cancels the scheduled send and the batches on their way, as tracking consent is withdrawn. */
    fun cancel() {
        stopTimer()
        inFlight.forEach { it.cancel() }
    }

    /** Stops for good: no more timers or sends. Queued events stay stored. */
    fun destroy() {
        destroyed = true
        cancel()
    }

    /** Back online: our own backoff no longer applies; the server's `Retry-After` still does. */
    fun onNetworkAvailable() {
        if (!delayFromServer) {
            retryDelayMs = FIRST_RETRY_MS
            notBefore = 0L
        }
        scope.launch { flush() }
    }

    private suspend fun sendCurrent(waiveBackoff: Boolean) {
        if (destroyed) return
        if (clock() < notBefore && (delayFromServer || !waiveBackoff)) {
            schedule(notBefore)
            return
        }
        val user = identity.user ?: return
        deliver(user, refreshAllowed = true) { identity.user === user && !destroyed && trackingAllowed() }
    }

    /** Sends batch after batch of [user]'s events while [mayStillSend], until the queue is empty or a send fails. */
    private suspend fun deliver(user: SignedInUser, refreshAllowed: Boolean, mayStillSend: () -> Boolean) {
        var maxEvents = MAX_EVENT_BATCH
        while (mayStillSend()) {
            val oldest = try {
                store.oldestFor(user.subject, maxEvents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("Could not read the event queue", e)
                return
            }
            val batch = fitBatch(oldest)
            // Consent may have been withdrawn, or the user changed, while the queue was read.
            if (batch.isEmpty() || !mayStillSend()) return
            when (val outcome = sendBatch(user, batch, refreshAllowed)) {
                Outcome.Split -> {
                    maxEvents = (batch.size + 1) / 2
                    continue
                }
                Outcome.Stop -> return
                is Outcome.Settled -> {
                    store.remove(outcome.keys)
                    // Past the event a split isolated, the rest go in full batches again.
                    maxEvents = MAX_EVENT_BATCH
                    if (outcome.keys.size < batch.size) {
                        KiroraLogger.d("Retrying ${batch.size - outcome.keys.size} events later")
                        retryLater(null)
                        return
                    }
                    resetBackoff()
                }
            }
        }
    }

    private sealed class Outcome {
        object Split : Outcome()
        object Stop : Outcome()
        class Settled(val keys: Set<String>) : Outcome()
    }

    private suspend fun sendBatch(user: SignedInUser, batch: List<EventEntity>, refreshAllowed: Boolean): Outcome {
        val keys = batch.mapTo(LinkedHashSet()) { it.idempotencyKey }
        val token = user.token
        var response = post(batch, token) ?: return Outcome.Stop
        if (response.code() == 401 && refreshAllowed) {
            val fresh = try {
                identity.refresh(user, token)
            } catch (e: UserTokenError) {
                KiroraLogger.e(e.message ?: "fetchUserToken failed", e.cause)
                retryLater(null)
                return Outcome.Stop
            }
            if (fresh != null && fresh != token) response = post(batch, fresh) ?: return Outcome.Stop
        }
        if (response.isSuccessful) {
            user.confirmed = true
            consecutiveAuthFailures = 0
            return Outcome.Settled(acknowledged(response.body(), keys))
        }
        return classifyFailure(response.toApiError(), keys, user)
    }

    /** Null after a network failure, which has already scheduled a retry. `sentAt` is new for every attempt. */
    private suspend fun post(batch: List<EventEntity>, token: String): Response<JsonElement>? = try {
        apiService.sendEventBatch(buildRequest(batch), "Bearer $token")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KiroraLogger.d("Events will be retried: ${e.javaClass.simpleName}")
        retryLater(null)
        null
    }

    private fun classifyFailure(error: KiroraApiError, keys: Set<String>, user: SignedInUser): Outcome {
        if (error.retryable) {
            retryLater(error)
            return Outcome.Stop
        }
        if (error.status == 401) {
            KiroraLogger.e("Events not sent: Kirora refused the user token")
            onTokenRefused(user)
            if (++consecutiveAuthFailures == AUTH_FAILURES_BEFORE_REPORT) {
                KiroraLogger.e("Kirora keeps refusing the user token; call identify() again.")
            }
            // Worth trying again only if a fresh token can be asked for.
            if (identity.canRefresh) retryLater(null)
            return Outcome.Stop
        }
        // Too large or invalid: halve until the bad event stands alone, so the rest still go.
        if (error.status in SPLIT_STATUSES && keys.size > 1) return Outcome.Split
        KiroraLogger.e("${keys.size} events dropped: Kirora refused them (HTTP ${error.status}${error.code?.let { " $it" } ?: ""})")
        onDropped(keys.size, EventDropReason.REJECTED)
        return Outcome.Settled(keys)
    }

    /**
     * The events Kirora answered for once each, accepted or refused for good. Refusals are logged
     * by code, never with the event. Unknown keys acknowledge nothing.
     */
    private fun acknowledged(body: JsonElement?, keys: Set<String>): Set<String> {
        val results = (body.data() as? JsonObject)?.get("results") as? JsonArray ?: return emptySet()
        val answers = HashMap<String, String?>()
        val repeated = HashSet<String>()
        for (element in results) {
            val result = element as? JsonObject ?: continue
            val key = (result.get("idempotencyKey") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: continue
            if (key !in keys) continue
            if (answers.containsKey(key)) repeated.add(key)
            // Either `{ error: { code } }` or `{ event: { … } }`; anything else is unreadable,
            // so the event waits for a retry with its key.
            val error = result.get("error")
            val code: String? = if (error == null || error.isJsonNull) {
                if (result.get("event") !is JsonObject) continue
                null
            } else {
                result.errorCode() ?: continue
            }
            answers[key] = code
        }
        val settled = HashSet<String>()
        val refusals = HashMap<String, Int>()
        for ((key, code) in answers) {
            if (key in repeated) continue
            if (code != null && code in RETRYABLE_EVENT_CODES) continue
            settled.add(key)
            if (code != null) refusals[code] = (refusals[code] ?: 0) + 1
        }
        refusals.forEach { (code, count) -> KiroraLogger.e("$count events refused by Kirora: $code") }
        val refused = refusals.values.sum()
        if (refused > 0) onDropped(refused, EventDropReason.REJECTED)
        return settled
    }

    /** Backs off, doubling up to 5 minutes, and never sooner than `Retry-After`. */
    private fun retryLater(error: KiroraApiError?) {
        val retryAfterMs = error?.retryAfterMs ?: 0L
        // Jitter only ever adds to the server's wait; RetryAfter caps it well below overflow.
        val delayMs = max(jittered(retryDelayMs), (retryAfterMs * (1 + Random.nextDouble() * JITTER)).toLong())
        notBefore = clock() + delayMs
        delayFromServer = retryAfterMs > 0
        retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_MS)
        schedule(notBefore)
    }

    private fun resetBackoff() {
        retryDelayMs = FIRST_RETRY_MS
        notBefore = 0L
        delayFromServer = false
    }

    /** Flushes at [at], or sooner if one is already due then, but never before [notBefore]. */
    @Synchronized
    private fun schedule(at: Long) {
        if (destroyed) return
        val due = max(at, notBefore)
        // A timer that already fired (it may be the caller, mid-flush) doesn't count as armed.
        if (timer?.isActive == true && timerAt <= due && timerAt > clock()) return
        timer?.cancel()
        timerAt = due
        timer = scope.launch {
            delay(max(0L, due - clock()))
            flush()
        }
    }

    @Synchronized
    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun buildRequest(batch: List<EventEntity>) = BatchEventsRequest(
        sentAt = JsonSnapshot.formatIso8601(clock()),
        events = batch.map { entity ->
            KiroraEventApiDto(
                idempotencyKey = entity.idempotencyKey,
                name = entity.name,
                properties = parse(entity.propertiesJson),
                context = parse(entity.contextJson),
                occurredAt = JsonSnapshot.formatIso8601(entity.occurredAt)
            )
        }
    )

    private fun parse(json: String): JsonElement = try {
        JsonParser.parseString(json)
    } catch (_: Exception) {
        JsonObject()
    }

    companion object {
        const val MAX_EVENT_BATCH = 100
        const val FLUSH_AT_EVENTS = 20
        const val FLUSH_INTERVAL_MS = 30_000L
        /** Stays under the server's body limit; a 413 gets split anyway. */
        const val MAX_BATCH_BYTES = 200_000
        /** `{"sentAt":"…","events":[]}` around the events. */
        private const val ENVELOPE_BYTES = 64
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 300_000L
        private const val JITTER = 0.1
        private const val AUTH_FAILURES_BEFORE_REPORT = 3
        private val SPLIT_STATUSES = setOf(400, 413, 422)
        private val RETRYABLE_EVENT_CODES = setOf("RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE")

        /** As many events as fit in [MAX_BATCH_BYTES], but always at least one. */
        internal fun fitBatch(events: List<EventEntity>): List<EventEntity> {
            var bytes = ENVELOPE_BYTES
            events.forEachIndexed { index, event ->
                val size = event.sizeBytes + 1
                if (bytes + size > MAX_BATCH_BYTES && index > 0) return events.subList(0, index)
                bytes += size
            }
            return events
        }

        private fun jittered(delayMs: Long): Long =
            (delayMs * (1 - JITTER + Random.nextDouble() * 2 * JITTER)).toLong()
    }
}
