# Android SDK review

Reviewed 17 November 2026. **Verdict at review: changes required before production
integration.** All nine findings have since been fixed; see "Status after fixes".
The release libraries build and pass Android lint, but normal event context is incompatible
with the backend, and identity transitions and failed storage operations have correctness
problems. Successful compilation does not establish SDK conformance.

## Status after fixes (17 November 2026)

Every finding below was fixed in the SDK the same day, each with a regression test in
[ReviewFindingsTest.kt](../kirora-sdk/src/test/java/com/kirora/androidsdk/ReviewFindingsTest.kt).
The whole suite (122 tests) passes, twice in a row. Both release AARs build, release lint
passes, and the demo app builds. The findings are kept below as they were reviewed.

| Finding | Fix | Test |
| --- | --- | --- |
| A01 context refused | The backend added the mobile fields to `eventContextSchema` (uncommitted, 17 November; live with its next deploy). The SDK sends exactly that shape: `device` (`id`, `manufacturer`, `type: "android"`), `app`, `os`, `screen`; no device model and no `network`. An event's real serialized context, checked with the backend's own `eventContextSchema.safeParse`, is accepted. | `eventContextHoldsOnlyFieldsTheServerAccepts` |
| A02 logout boundary | `logout()` ends the identity (`Identity.end()`) before its first suspension; flush and detach use the captured user, then that user's leftovers are dropped. | `eventsTrackedWhileLogoutRunsWaitForTheNextUser` |
| A03 detach vs registration | The detach decision and registration snapshot run under the registration mutex, in a job not cancelled by logout's deadline. | `aDetachWaitsForATokenRefreshRegistrationAndNamesIt`, `aDetachCoversAFirstRegistrationStillUnderWay` |
| A04 failed purge | `PendingPurges` records a refused deletion (all rows, or one owner's) in preferences; the table is not read or written until it is carried out. As nothing is written while one is owed, it covers exactly the rows stored when it was refused, with no dependence on the device clock. Events and app opens both. | `aRefusedPurgeKeepsWithdrawnEventsUnavailableEvenAfterARestart`, `aRefusedSignOutPurgeKeepsThatUsersEventsFromComingBack` |
| A05 malformed results | Only `{ event: {…} }` or `{ error: { code: "…" } }` settles a key. | `malformedResultsAcknowledgeNothing` |
| A06 double budget | One budget across Room and memory; memory moves into Room once it takes writes; `oldestFor` merges by capture time. | `diskAndMemoryShareOneBudget`, `eventsHeldInMemoryJoinTheDatabaseInCaptureOrderOnceItRecovers` |
| A07 Retry-After | No one-hour cap; overflow saturates; deadlines can't overflow. A push report asked to wait over 60 seconds is dropped, not retried early. | `retryAfterKeepsTheServersWholeWait`, `aLongRetryAfterHoldsEventsBackPastAnHour` |
| A08 replacement client | The user is carried only when the namespace (client key and API URL) is the same. | `aClientForAnotherWorkspaceOrApiStartsSignedOut` |
| A09 app-open spacing | In memory when `trackSessions` is off; removed from preferences on withdrawal. | `withSessionsOffTheAppOpenSpacingIsNotStored`, `withdrawingConsentForgetsTheStoredAppOpenTime` |

Other gaps:

- **Context capture timing:** fixed. The context is read on the caller's thread when
  `track()` is called; it reads only in-memory values. `contextIsCapturedWhenTheEventIsTracked`.
- **Wrapper teardown:** both wrappers (React Native `invalidate`, Flutter
  `onDetachedFromEngine`) remove their own tap and foreground listeners as they call
  `wrapperDetached()`. With no tap listener, taps are held again for the next one. This is
  documented on `wrapperDetached()`.
- **Dependency and storage disclosure:** the README's "Data and storage" section now
  describes the actual context and storage.
- **Public surface drift:** settled with the backend session. The push appearance options
  are now in the shared interface as Android push options. `tokenRefreshLeadSeconds` is
  removed (60 seconds, internal). `onEventsDropped`, `onAuthenticationFailed` and
  `droppedEventCount` are held back from 0.1.0 (internal, logged) until the shared interface
  decides on them for every SDK.
- **Publication:** the release repository is `github.com/kiroratech/kirora-android`; both
  POMs' SCM metadata and the git remote name it.
- **Still open:** public Maven publication, signing and resolution from a fresh project;
  real-device FCM checks; splitting `Kirora.kt` (left as is: the fixes did not need it).

## Scope and evidence

The requested `kirora/android-sdk` directory does not exist. This review covers
`kirora/kirora-android`: the core library, optional FCM library, sample application,
publication configuration, manifests, consumer rules, existing test sources and CI.
The contract is the shared [SDK interface](../../../omnichannel/docs/sdk-interface.md),
checked against the actual backend validation and Android FCM payload construction.
Customer.io and Iterable comparisons use their official Android documentation, accessed
on the review date. Their features are benchmarks, not replacements for Kirora's contract.

The checkout has no commits and its files are untracked; there is no commit SHA to cite.
A SHA-256 file snapshot was captured before verification. Builds and standalone probes ran
in `/private/tmp/kirora-android-review-20261117`, excluding credentials and Firebase
configuration. No implementation, test, build configuration or CI files were changed in
the source checkout. The shared spec changed concurrently only in its iOS link-handling
section and corresponding changelog; that diff was checked and does not change these
Android findings. The test suite was neither run nor modified, as requested.

Findings marked **reproduced** were exercised against compiled SDK code or the actual
backend schema. Findings marked **source analysis** describe a concrete code path but
were not reproduced on a device or through a live Kirora deployment. P1 means a release
blocker; P2 means a material correctness or contract defect to resolve before claiming
conformance. Recommendations below are proposed work, not fixes delivered by this review.

## Findings

### A01 — P1: default event context is rejected by the backend

**Reproduced against the real schema.**
[KiroraContext.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/model/KiroraContext.kt),
lines 47–90, serializes `app`, `network`, `os`, `screen`, and device
`manufacturer`, `model` and `type`.
[KiroraContextProvider.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/utils/KiroraContextProvider.kt)
populates these for ordinary tracking calls. Even the default context adds `os`.

The backend's
[eventContextSchema](../../../omnichannel/src/modules/events/schemas/event.schema.ts),
lines 150–179, is strict at both the context and device levels. It does not yet accept
those mobile additions; its device object accepts only `id`.
[SdkClientService.trackEvents](../../../omnichannel/src/modules/sdk/services/sdk-client.service.ts)
passes the supplied context into event tracking rather than removing unsupported fields.
The spec says mobile additions must join the server schema with the mobile SDKs; that
coordination has not happened in this checkout.

A standalone JVM program serialized the actual `KiroraContext`, and the resulting JSON
was passed to the backend's imported `eventContextSchema.safeParse`. It failed with
unrecognized context keys `app`, `network`, `os`, `screen` and unrecognized device keys
`manufacturer`, `model`, `type`. Consequently the normal batch path can reject individual
events and the client can permanently discard them as terminal refusals. The schema
failure was executed; the full HTTP delivery consequence is established by source analysis,
not a live endpoint test.

**Required change:** agree the mobile context contract, then make the SDK and strict
backend schema match. Either restrict collection to currently supported fields or ship
an explicitly documented backend/schema extension. Check serialized Android fixtures
against the backend validator, including default events, sessions and screen views.
Do not make the backend accept arbitrary context just to suppress the error.

### A02 — P1: events can join the outgoing user after logout has started

**Source analysis.** [Kirora.logout](../kirora-sdk/src/main/java/com/kirora/androidsdk/Kirora.kt),
lines 585–607, retains `identity.user` while flushing and detaching. Local sign-out runs
only after that network work finishes. In the same file, `trackInternal`, around
line 764, chooses the owner directly from `identity.user?.subject`.

Start logout for A and hold the detach response. A concurrent `track` is still captured
as A's event. Depending on timing it may be included in A's drain or removed by
`clearOwnedBy(A)` after local sign-out. The contract explicitly requires logout to freeze
A's pending work and prevent new work from joining A; new work must remain unbound for
the next identity. This can both misattribute post-sign-out activity and lose it.

**Required change:** introduce the outgoing-identity boundary before the first suspension.
Retain A's immutable credentials for its bounded cleanup, while capture observes an
unbound state. Verify tracking during a stalled flush/detach, concurrent identify, and
repeated logout. Preserve existing protection against relabeling A's old events as B's.

### A03 — P1: detachment can miss a registration already in progress

**Source analysis.**
[KiroraPushManager.register](../kirora-sdk/src/main/java/com/kirora/androidsdk/push/KiroraPushManager.kt),
lines 119–181, holds `operations` across the registration request and saves its result
when the response arrives. `detach`, lines 325–342, reads and clears the registration
record **before** acquiring that mutex.

Concrete sequence: A has registration r1; an FCM token refresh starts PUT r2; logout
captures r1 and waits for the registration lock; PUT r2 finishes and stores r2; DELETE
uses r1's `lastSeenAt`. The server correctly preserves r2 with 409, which cleanup treats
as success. A's newer registration remains reachable after logout. If the first
registration is in flight and no record exists yet, detachment can return without
attempting DELETE at all. This concerns background token refresh registration, not just
initial identify work that `identity.settled()` already waits for.

**Required change:** order the detach decision and registration snapshot behind pending
registration work under the same serialization boundary. Keep the outgoing user and
registration generation explicit. Verify stalled PUT → logout → completed PUT, including
first registration, token rotation, same-user re-identification and a genuinely newer
B registration. A newer B registration should still survive a late A cleanup.

### A04 — P1: a failed purge lets withdrawn events return after storage recovers

**Reproduced with the compiled EventStore and a DAO failure simulation.**
[EventStore.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/net/EventStore.kt),
lines 81–120, converts database failures into successful-looking fallback returns.
`clearAll` clears memory after a failed disk purge and records no outstanding purge.
`clearOwnedBy` has the same problem for logout. Tracking withdrawal calls these paths
from [Kirora.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/Kirora.kt), around
lines 967–977. App-open clearing also logs a failure without remembering the purge.

With 1,000 persisted records, a simulated read-only DAO made `clearAll` fail. Once that
DAO recovered, `count()` returned 1,000: withdrawn records were available again. Sending
is gated while consent remains refused, but regranting consent can release old data
that the withdrawal was supposed to delete. A same-user login can similarly expose
records that logout was supposed to discard.

**Required change:** remember required global/owner purges and finish them before recovered
storage is read or sent. If deletion cannot be confirmed, keep those records unavailable.
Cover both behavioural events and influenced app opens. Logging the failure and allowing
normal recovery is insufficient. The probe simulated DAO behavior; it did not corrupt a
real Room database or test recovery across process death.

### A05 — P2: malformed per-event results can acknowledge an event

**Reproduced against the compiled acknowledgement parser.**
[KiroraBatchManager.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/net/KiroraBatchManager.kt),
lines 270–307, treats an unreadable `error` as terminal `UNKNOWN`. Its success branch
checks that the `event` member exists, rather than validating its shape.

For a known pending key, a result containing `"error": "unreadable"` was returned as
acknowledged by the actual parser. The batch then has permission to remove that record
without a valid success or terminal error. Similarly, JSON null or scalar `event`
members satisfy the mere-presence check. This is distinct from a result containing only
an idempotency key, which Android correctly leaves pending.

**Required change:** recognize the documented success/error union before settling a key.
Keep malformed and missing results for retry with their original keys. Preserve the
existing protections for unknown keys, duplicate results and retryable error codes.

### A06 — P2: disk and memory fallback each get the entire queue budget

**Reproduced with the compiled EventStore and a DAO failure simulation.**
[EventStore.add/count](../kirora-sdk/src/main/java/com/kirora/androidsdk/net/EventStore.kt),
lines 28–48 and 101–105, independently caps disk and memory at 1,000 events and
1,000,000 bytes, then adds their counts. A database that remains readable while inserts
fail retains its full queue while another full queue accumulates in memory.

The standalone probe held 1,000 readable disk records, rejected inserts, added 1,000
fallback records, and observed `count() == 2000`. The same separate-budget design also
permits the combined byte budget to be exceeded. `oldestFor` additionally gives disk
records priority over memory records instead of merging by capture order.

**Required change:** enforce one logical count/byte budget across both stores and retain
the oldest-first eviction and delivery policy across recovery. Verify a partially
working database, not only a wholly unavailable database or a normal writable one.

### A07 — P2: Retry-After can be shortened, and numeric conversion can overflow

**Parser reproduced; retry scheduling checked by source analysis.**
[RetryAfter.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/utils/RetryAfter.kt),
lines 10–19, truncates every server delay to one hour and multiplies numeric seconds
without overflow protection. The compiled parser returned `3600000` for `Retry-After:
7200`, rather than 7,200,000 ms, and null for the overflowing numeric input
`9223372036854776`.

[KiroraPushTracker.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/push/KiroraPushTracker.kt),
lines 40–69, further clamps its one retry to 60 seconds even when the server asks for
longer. Both paths violate the contract's earliest-retry guarantee; the tracker comment
claims a guarantee its code does not implement.

**Required change:** parse numeric/date values with safe arithmetic and retain the server's
full earliest deadline. A best-effort receipt that cannot wait within its permitted
lifetime should forgo that retry rather than retry early. Check delays beyond one minute
and one hour, HTTP dates, overflow and foreground/network resets. No wall-clock multi-hour
request was performed in this review.

### A08 — P2: a replacement client carries identity across workspace/API boundaries

**Source analysis.** [Kirora.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/Kirora.kt),
lines 333–336, captures the active client's user before destroying it. Lines 456–460
restore that user and run sign-in follow-up in the new client without comparing client
key or normalized API origin.

Creating a staging or second-workspace client can therefore forward the old JWT to the
new configured API and associate that client's unbound events with the old subject.
Namespaced storage does not prevent this in-memory transfer. A different workspace's
backend should reject the token; this is not evidence of an authorization bypass.
It is unexpected credential forwarding and incorrect client isolation.

**Required change:** carry identity only within the same authenticated configuration
namespace, or require explicit identify on every replacement. Treat any change to the
published replacement behavior as a contract decision. Verify different keys, different
API origins and same-workspace replacement separately.

### A09 — P2: app-open spacing is persisted with sessions disabled and survives withdrawal

**Source analysis.**
[KiroraAppOpenReporter.kt](../kirora-sdk/src/main/java/com/kirora/androidsdk/net/KiroraAppOpenReporter.kt),
lines 119–137, always reads/writes `preferences.lastAppOpen` when a signed-in,
push-registered device records an open. The reporter is not given `trackSessions`, so
turning sessions off does not change that storage policy. The spec explicitly requires
in-memory spacing when sessions are off.

The reporter’s `clearAll` does not remove this timestamp, and
[KiroraPreferences.clearSession](../kirora-sdk/src/main/java/com/kirora/androidsdk/utils/KiroraPreferences.kt),
lines 219–221, removes session fields but not `KEY_LAST_APP_OPEN`. A behavioral app-open
time can remain persisted after tracking withdrawal. This finding concerns the spacing
metadata as well as the queued records covered in A04.

**Required change:** keep spacing in memory when sessions are disabled and clear behavioral
spacing state on withdrawal. Check the stored preferences after recording an open,
disabling tracking and restarting; also check a regrant within 30 minutes.

## Other contract and release gaps

These need explicit resolution; they are not all reasons to add new features.

- **Public surface drift:** `KiroraOptions` adds `onEventsDropped`,
  `onAuthenticationFailed` and enabled-by-default `tokenRefreshLeadSeconds = 60` beyond
  the shared options. Native notification appearance options are useful, but their
  platform-specific contract should also be documented centrally. Decide which additions
  are approved before declaring cross-SDK parity. Removing public options would be a
  breaking change; do not silently remove them during cleanup.
- **Context capture timing:** `trackInternal` snapshots properties, owner, time and
  session synchronously, but calls `provider.getContext` later on `queueDispatcher`.
  Locale, timezone and network context can describe queue execution rather than capture.
  The spec requires a capture-time snapshot. Move collection to an appropriate capture
  boundary without introducing unbounded caller-thread work.
- **Wrapper teardown:** `wrapperDetached`, around line 1359 of `Kirora.kt`, resets routing
  callbacks but does not clear the process-global notification-click listeners.
  Reconcile that lifecycle with the spec's requirement to forget the departing wrapper's
  tap listener; verify engine destruction and recreation before claiming wrapper parity.
- **Publication:** both POMs generate with `com.kirora` coordinates, but successful public
  repository resolution, signed artifacts, sources documentation and external-consumer
  installation have not been established. The configured publishing destination is a
  local staging directory. SCM metadata currently names `dotunv/kirora-android`; confirm
  the intended release repository rather than inventing an organization URL in prose.
- **Dependency and storage disclosure:** core is Firebase-free, not dependency-free; it
  uses Room, lifecycle, coroutines, Retrofit/Gson and OkHttp. Behavioural context currently
  includes device model, OS, screen, app and network details. The SDK uses ordinary
  app-private preferences/Room, not an SDK encryption layer. The demo separately persists
  its manually entered user token; use disposable tokens in the demo and do not describe
  that sample storage as the SDK's authentication design.
- **Maintainability:** `Kirora.kt` combines identity, consent, queue orchestration,
  lifecycle and wrapper coordination in roughly 1,590 lines. The transition bugs above
  justify clearer ownership boundaries and smaller transition methods. They do not
  justify replacing Room, adding an outbox, or refactoring unrelated code.

## Comparison with Customer.io and Iterable

| Area | Customer.io Android | Iterable Android | Kirora assessment |
| --- | --- | --- | --- |
| Installation | Separates data collection, FCM and in-app packages. [Package options](https://docs.customer.io/integrations/sdk/android/getting-started/packages-options/) | Documents a core library and optional inbox UI. [Android guide](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | Core/optional FCM is a good boundary. Add verifiable release installation and clear import names. |
| Delivery | Documents a persistent task queue and 20-task/30-second/reopen triggers. [Queue behavior](https://docs.customer.io/integrations/sdk/android/getting-started/how-it-works/) | Documents JWT handling and conditional offline auth retry. [Authentication and offline processing](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | Similar batching controls exist, but A01/A04–A07 prevent equivalent reliability claims. |
| Authentication | Its quick start uses a CDP API key and identify calls. [Quick start](https://docs.customer.io/integrations/sdk/android/quick-start-guide/) | Supports mobile keys, JWT auth handlers, proactive refresh and failure handling; offline JWT retry requires project enablement. [Android guide](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | Public client key plus server-signed per-user JWT is appropriate. Keep secrets off-device and resolve identity boundary defects before adding auth knobs. |
| Push integration | Documents FCM setup as a distinct integration. [Push guide](https://docs.customer.io/integrations/sdk/android/push/push/) | Provides push setup and configurable link handling. [Android guide](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | Default service, custom-service forwarding, channels, actions and cold-start routing exist. Registration ordering needs correction. |
| Local storage | Documents persisted work so queued operations survive closure. [Queue behavior](https://docs.customer.io/integrations/sdk/android/getting-started/how-it-works/) | Documents encryption of sensitive user state on supported Android versions. [Android guide](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | JWT is memory-only in the SDK. Queued properties can still contain personal data; disclose actual storage and fix failed purges. Encryption is a separate product/security decision. |
| Product scope | Offers in-app messaging packages alongside collection and push. [Package options](https://docs.customer.io/integrations/sdk/android/getting-started/packages-options/) | Offers inbox and embedded messaging. [Android guide](https://support.iterable.com/hc/en-us/articles/360035019712-Iterable-s-Android-SDK) | Missing inbox/in-app/embedded messaging is not a defect against this SDK spec. Prioritize conformance over competitor feature count. |

The useful blueprint is modular setup, a short first-integration path, explicit identity
and push lifecycle documentation, and observable failure behavior. Do not copy a
competitor's API names or consent model into an already shared Kirora interface.

## Android push and security observations

The backend's
[FCM provider](../../../omnichannel/src/modules/push/providers/fcm.provider.ts) must be read
alongside the SDK renderer: its Android SDK data-message builder intentionally maps
`imageUrl` to `image`, `actionUrl` to `link`, and collapse keys to `tag`. These differences
from other platforms are not wire-format bugs.

The code also has useful safeguards: non-exported service/trampoline activities,
immutable pending intents, blocked dangerous link schemes, a same-origin tracking URL
check, request-scoped captured identities, and outgoing cleanup that uses its captured
token rather than fetching the next user's token. Device identity is a random UUID,
not an advertising or hardware identifier. These are source observations, not a complete
penetration test.

Kirora handles data-only FCM messages and renders notifications itself. Firebase documents
that notification payloads and data-only payloads have different background behavior,
and that `onMessageReceived` has a short execution window. Consequently cold start,
process death, permission denial, channel settings and image loading need real-device
verification. Connect/read image timeouts are not proof of a complete end-to-end deadline.
See [Firebase receive-message guidance](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
and [message-priority guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority).
No real FCM delivery, notification interaction or OEM background behavior was exercised.

## Verification performed

| Check | Result and limit |
| --- | --- |
| Core and FCM release AARs | **Passed:** `:kirora-sdk:assembleRelease :kirora-sdk-push:assembleRelease`, offline in the isolated copy. |
| SDK release lint | **Passed:** `:kirora-sdk:lintRelease :kirora-sdk-push:lintRelease`, separately completed after the sample check stopped. |
| Publication metadata | Both `generatePomFileForReleasePublication` tasks completed. Public publishing, signing and external resolution were not attempted. |
| Sample release | Kotlin compilation completed, but the overall build **did not pass**: offline `:app:lintVitalAnalyzeRelease` could not resolve Compose `material-icons-core-desktop` and `material-icons-extended-desktop` 1.7.8. R8/package completion remains unverified. |
| Context contract | Actual compiled `KiroraContext.toJson()` output rejected by actual backend `eventContextSchema`. |
| Acknowledgements | Actual compiled parser settled a known key with malformed string `error`. |
| Partial storage failure | Actual compiled EventStore with a simulated DAO reported 2,000 combined records; 1,000 disk records returned after failed clear and recovery. |
| Backoff parser | Actual compiled parser shortened 7,200 seconds to one hour and returned null for an overflowing numeric delay. |
| README Kotlin examples | **Passed:** examples were combined with typed host-value placeholders in a temporary sample source and compiled using `:app:compileReleaseKotlin`. This checks API use, not server authentication or live push. |
| Existing suite | Read relevant identity, consent, queue and wrapper test sources. **Not run or edited.** No coverage percentage or passing-suite claim. |

The standalone probes are temporary Java programs calling the built library. The storage
probe uses a fake DAO and bypasses Android constructors; it does not demonstrate a real
filesystem fault or a complete Android lifecycle. Backend validation ran through a direct
schema import without starting the server or using a database. Compiler warnings included
deprecated APIs and future Gradle incompatibilities; successful tasks do not validate
future toolchain releases.

## Recommended sequence

1. Resolve the SDK/backend context contract and verify a real batch reaches the correct
   contact with per-event results the SDK understands.
2. Correct logout capture and registration/detachment ordering together, preserving the
   server's generation guard and outgoing credentials.
3. Make failed purges durable in intent, apply combined storage budgets, and validate
   acknowledgements and Retry-After deadlines.
4. Isolate replacement-client identity and remove behavioral spacing persistence where
   the contract forbids it. Settle the remaining public API and wrapper differences.
5. In a subsequent implementation task, add focused regression coverage for these concrete
   failures, run the existing suite, complete sample R8 validation and verify signed
   publication from a fresh consuming project. Exercise FCM on physical devices across
   foreground, background, terminated launch and permission changes.

This review delivers documentation only. The findings remain open; the rewritten README
explains the implemented API and explicitly identifies the release limitations.
