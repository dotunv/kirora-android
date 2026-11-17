# Kirora Android SDK: comparative review and improvement plan

Compared against `customerio/customerio-android` (CIO, v5.0.3, Oct 2026) and `Iterable/iterable-android-sdk` (ITBL, v3.11.1). Both were shallow-cloned and read only; nothing from them was built or run. Kirora was read at `/home/dotun/AndroidStudioProjects/kiroraandroid` (modules `kirora-sdk`, `kirora-sdk-push`, `app`; about 4.8k lines of Kotlin including tests). No Kirora file was modified.

Paths below are relative to each repo root. CIO source lives under `<module>/src/main/kotlin/io/customer/...` (core, datapipelines) or `src/main/java/io/customer/...` (messagingpush). ITBL source lives under `iterableapi/src/main/java/com/iterable/iterableapi/`. Kirora source lives under `kirora-sdk/src/main/java/com/kirora/androidsdk/`.

---

## 1. Architecture summaries

### Customer.io (CIO): modular, DI-driven, built on a Segment fork

- **Modules (settings.gradle):** `base` (annotations), `core` (DI, logger, stores, EventBus, WorkManager provider), `datapipelines` (the main `CustomerIO` object), `messagingpush`, `messaginginapp` (+ `-compose`), `messaginginbox`, `location`, `geofence`, `tracking-migration`, `common-test`, and two samples. Each feature is an opt-in artifact.
- **Plugin and DI approach:** every optional module implements `CustomerIOModule<Config>` (`core/.../core/module/CustomerIOModule.kt`: `moduleName`, `moduleConfig`, `initialize()`). Modules are added through `CustomerIOConfigBuilder.addCustomerIOModule(...)` and registered in the global `SDKComponent` (`core/.../core/di/SDKComponent.kt`, which extends a small hand-written `DiGraph`: `singleton{}` and `newInstance{}`). Modules talk through an `EventBus` (`core/.../communication/EventBus.kt`: `UserChangedEvent`, `ResetEvent`, `RegisterDeviceTokenEvent`, `ScreenViewedEvent`, ...), not direct references. A narrow `IdentifyHook` interface (`core/.../core/pipeline/IdentifyHook.kt`) lets modules enrich identify events.
- **Pipeline:** `datapipelines` wraps `com.segment.analytics.kotlin`. Behavior is split into plugins (`datapipelines/.../plugins/`: `ContextPlugin`, `IdentifyContextPlugin`, `AutomaticActivityScreenTrackingPlugin`, `AutoTrackDeviceAttributesPlugin`, `CustomerIODestination`, `ScreenFilterPlugin`). Flush policies are pluggable (`BackgroundAwareFrequencyFlushPolicy`: skips ticks while backgrounded because Android 15 blocks background network).
- **Init:** `CustomerIOConfigBuilder(appContext, key)...build()` then `CustomerIO.initialize(config)` (`CustomerIO.kt` around line 597). It is `@Synchronized` on instance creation, rejects a second initialize with an error log, and rejects `sk_` secret keys by installing a *disabled* instance instead of crashing (`CioApiKey.isSecretKey`). The key prefix also selects the region.
- **Identity:** `identify(userId, traits)` is deduped per session, switching profile deletes the device token from the old profile first (`identifyImpl`, about line 293), and `clearIdentify` pins the identity on the delete-token event (`clearIdentifyImpl`, about line 388). Anonymous IDs come from Segment, so pre-identify events are stored under an `anonymousId` and merged server-side. `SecureUserStore` (AES-GCM via Keystore, writes on a dedicated executor) keeps the user id for workers and receivers.
- **Queue and background work:** the event queue is Segment's file storage with `flushAt`/`flushInterval`/policies. The WorkManager use is *targeted*: push "delivered" metrics are persisted in `PendingDeliveryStore` and sent by `PushDeliveryMetricsWorker` (`messagingpush/.../processor/PushDeliveryMetricsBackgroundScheduler.kt`). On the next foreground, `PendingDeliveryFlusher` (`core/.../data/store/PendingDeliveryFlusher.kt`) cancels the worker and hands the entry to the in-process pipeline, with explicit AT_MOST_ONCE / AT_LEAST_ONCE semantics.
- **Push (`messagingpush`):**
  - `CustomerIOFirebaseMessagingService` exposes static `onMessageReceived` and `onNewToken` helpers.
  - `CustomerIOCloudMessagingReceiver` listens to the raw `c2dm.RECEIVE` broadcast so delivery metrics are counted even for notification-type messages.
  - `PushMessageProcessorImpl` dedupes by `deliveryId` using a bounded queue.
  - `NotificationClickReceiverActivity` is a `singleTask` translucent trampoline.
  - `PushClickBehavior` (RESET_TASK_STACK / ACTIVITY_PREVENT_RESTART / ACTIVITY_NO_FLAGS) controls navigation.
  - `DeepLinkUtil` resolves host-app versus external intents.
  - `FCMTokenProviderImpl` checks `GoogleApiAvailability` before asking for a token.
  - `FirebaseTokenSource` supports Firebase Installation IDs via reflection (consumer rule keeps `FirebaseMessaging.register()`).
  - Newer pieces: live notifications (Android 16 promoted-ongoing builder) and templates.
- **Other modules:** in-app (`messaginginapp`: gist, SSE, inline, modal; Compose wrapper) and inbox. Neither is relevant to Kirora yet.
- **Quality and release tooling:** Kotlin binary-compatibility-validator (`<module>/api/*.api`, `apiCheck` in `.github/workflows/binary-validator.yml`, plus a lefthook pre-commit), ktlint (`make lint`, `lefthook.yml`), Android lint per module (`lint.yml`), JUnit5 + MockK + Robolectric with a shared `common-test` module (`UnitTest`/`RobolectricTest`/`AndroidTest` base classes), OkHttp version-compat matrix (`test.yml`), SDK size diff on every PR (`sdk-binary-size.yml`, `scripts/check-sdk-size.sh`), 16 KB page-size check (`page-size-check.yml`), Maestro e2e (`.maestro/`), semantic-release (`.releaserc.json`, `deploy-sdk.yml`), snapshot releases (`snapshot-release.yml`), generated CHANGELOG, `AGENTS.md`, dependabot, a shared Gradle publishing plugin (`io.customer.android.publish-module`). minSdk 24, compileSdk/targetSdk 36, Kotlin 2.1.

### Iterable (ITBL): monolithic, Java-heavy, feature-rich (in-app, embedded, inbox, JWT)

- **Modules:** `iterableapi` (core: tracking, push, in-app, embedded, deep links, auth), `iterableapi-ui` (inbox UI), `app` (sample/debug app), `integration-tests` (real-backend instrumentation), `sample-apps/inbox-customization`. About 114 source files in the core module; `IterableApi.java` alone is 2,000 lines. No DI: `IterableApi.sharedInstance` is a singleton and collaborators are wired by hand, with test constructors on the singleton.
- **Config:** `IterableConfig.Builder` (`IterableConfig.java`, about 25 setters: log level, handlers, `setAuthHandler`, `setAuthRetryPolicy`, `setExpiringAuthTokenRefreshPeriod`, `setAllowedProtocols`, `setDataRegion`, `setKeychainEncryption`, `setDecryptionFailureHandler`, `setIdentityResolution`, ...). Validation is lenient and logged (null or negative values are ignored, with CHANGELOG notes).
- **Init:** `IterableApi.initialize(context, key, config)` is synchronous. There is also `initializeInBackground(...)` (`IterableBackgroundInitializer.java`) that returns at once and queues API calls until ready, plus `isSDKInitialized()` and `onSDKInitialized(callback)`. `initializeForPush(...)` sets the minimal context when a push action arrives before init. `getInAppManager()` before init returns a no-op manager, and the `...OrNull()` variants return null.
- **Identity:** `setEmail` / `setUserId`, each with an optional JWT and success/failure handlers. `IterableIdentityResolution(replayOnVisitorToKnown, mergeOnUnknownToKnown)`. **Unknown-user ("visitor") tracking:** `UnknownUserManager` stores events and updateUser calls locally with a threshold (`enforceEventThresholdLimit`), fetches server criteria, creates an unknown user once criteria match, then `UnknownUserMerge.tryMergeUser` merges into the known user and replays events. Stored credentials go in `IterableKeychain` (AES via Keystore) with `IterableDataEncryptor`, a decryption-failure handler and a keychain migrator.
- **Offline queue:** `IterableApiClient` routes through `RequestProcessor`. `OfflineRequestProcessor` persists only an allowlist of endpoints (`offlineApiSet`: track, push-open, purchase, in-app events, ...) as rows in `IterableTaskStorage` (raw SQLite, capped by `OFFLINE_TASKS_LIMIT = 1000`). `IterableTaskRunner` runs a single `HandlerThread`, triggered by task creation, network-up (`IterableNetworkConnectivityManager`), foreground and "auth token ready". `HealthMonitor` stops scheduling when the DB errors. Failures are classified: 5xx, network errors and 429 are retried, other 4xx are dropped, and 401 pauses the queue until a fresh JWT arrives (`isPausedForAuth`, `AuthTokenReadyListener`). `ApiEndpointClassification` says which endpoints need a JWT.
- **JWT auth:** `IterableAuthManager` (538 lines) with `IterableAuthHandler.onAuthTokenRequested()`, `onTokenRegistrationSuccessful`, `onAuthFailure(AuthFailure)`. It refreshes proactively before expiry (parses `exp`, default 60 s lead via `expiringAuthTokenRefreshPeriod`), also on foreground, and reactively on 401. It has a configurable `RetryPolicy(maxRetry, interval, LINEAR|EXPONENTIAL)`, `pauseAuthRetries()`, a typed `AuthFailureReason` enum (EXPIRED, SIGNATURE_INVALID, FORMAT_INVALID, USER_KEY_INVALID, ...), and an `IterableAuthRefreshReason` enum that says whether a refresh obeys the retry cap. v3.10.1 fixed overlapping refresh timers (single task owner).
- **Push:** `IterableFirebaseMessagingService` with static `handleMessageReceived`, `IterableNotificationHelper` / `IterableNotificationBuilder`, ghost (silent) pushes, `IterableTrampolineActivity` + `IterablePushActionReceiver`, `IterablePushNotificationUtil.processPendingAction` (an action that arrived before init is replayed). **Image download and notification build run in an expedited WorkManager job** (`IterableNotificationWorkScheduler` / `IterableNotificationWorker.kt`) so the FCM thread is not blocked. Small icon fallback chain (v3.10.0 CHANGELOG). `IterableUrlHandler` lets the host intercept every deep link, `setAllowedProtocols` is a protocol allowlist, and `IterableDeeplinkRedirectResolver` resolves tracked links.
- **In-app / embedded / inbox:** WebView in-app with `InAppServices` (layout, orientation, tracking, animation), handler returning SHOW / SKIP / DEFER, `IterableInAppFileStorage`, `IterableEmbeddedManager` with sessions and impressions, `iterableapi-ui` inbox.
- **Quality and release:** 64 unit-test files (Mockito + Robolectric + MockWebServer + work-testing), a real-backend `integration-tests` module (`integration-tests-bcit.yml`), Checkstyle, lint, CodeQL, a **CHANGELOG-required PR check** (`changelog-check.yml`, opt-out phrase "No customer facing changes"), `prepare-release.yml`, CODEOWNERS. No binary-compat validator, no consumer ProGuard file (empty `proguard-rules.pro`), minSdk 21, compileSdk 34, Java 17.

---

## 2. Gap table

"Kirora today" cites the file that decides the behavior. Rec = recommendation (full detail in section 3).

| Practice | Customer.io | Iterable | Kirora today | Recommendation |
|---|---|---|---|---|
| Module layout | 9 library modules, opt-in feature artifacts, `common-test` | 2 libs (core, ui) | 2 libs: core + push (`kirora-sdk`, `kirora-sdk-push`), core finds push by reflection (`Kirora.loadPushBridge`) | Good split. Keep; formalise the bridge as a documented internal contract (P2) |
| DI / testability seams | `SDKComponent` + `DiGraph` + test overrides | singleton + package-private test constructors | Hand-wired in `Kirora.setup()`; `setApiServiceForTesting`, `resetForTesting` | Extract a small `Graph` out of the 765-line `Kirora.kt` (P1) |
| Public config | Builder (`CustomerIOConfigBuilder`), key-prefix region | Builder (`IterableConfig.Builder`) | Non-data class with `@JvmOverloads` ctor + `withClientKey()` copy (`KiroraOptions.kt`) | Fine for binary compat; add a Java-friendly `Builder` and validate/normalize in one place (P2) |
| API compat tooling | `binary-compatibility-validator`, `.api` files, `apiCheck` in CI | none | `explicitApi()` only; no `.api`, no CI. Several accidental public items (`KiroraLogger`, `LogEntry`, `KiroraPushTrampolineActivity`, `KiroraApiError`, `Kirora.options`) | Add BCV now, before the first public release (P0) |
| Init safety | `@Synchronized` singleton, double-init logged, secret-key rejected -> disabled instance | `initializeInBackground`, `isSDKInitialized`, `onSDKInitialized`, no-op managers pre-init | Constructor runs `setup()` under a class lock; replaces prior instance; disabled mode on bad key/URL (`Kirora.setup`). Does Room/prefs/channel/`getPackageInfo` work on the caller (main) thread | Move disk/Keystore-free-but-slow work off main; reject non-public keys; keep replace semantics (P1) |
| Key hygiene | rejects `sk_` keys, `pk_` is public (`CioApiKey`) | none | only `trim()` + non-empty (`Kirora.setup`) | Reject a secret-key prefix once the backend defines it (P1, small) |
| Identify model | client-side `userId` + traits, per-session dedupe, token moves between profiles on switch | email/userId + optional JWT | Server-minted JWT (`fetchUserToken`), `sub` read from JWT, queue partitioned by `userSubject` (`EventDao.getBatchFor`) | Keep (stronger than both). Add anonymous id for pre-identify (P1) |
| Anonymous to known | Segment `anonymousId`, merged server-side | `UnknownUserManager` + `UnknownUserMerge` with replay | Pre-identify events wait locally (cap 1000, drop oldest) and go as the *next* user; nothing leaves the device while signed out (`KiroraBatchManager.flush`) | Send anonymous events with `device.id`, merge server-side; or at least document the cap (P1) |
| Event queue persistence | Segment file storage, flushAt/interval/policies | SQLite `IterableTaskStorage`, 1000 cap, endpoint allowlist | Room, `seq` ordered, unique idempotencyKey, trim to 1000, no-backup dir (`EventDao`, `KiroraDatabase`) | Good. Add DB-error circuit breaker (`HealthMonitor` idea) (P2) |
| Batching | `flushAt` 20, interval 30 s, pluggable policies | per-request tasks (no batching) | 20 events / 30 s / on background / 100 per request (`KiroraBatchManager`) | Good. Skip timer ticks while backgrounded (P2) |
| Retry / backoff | in Segment + `Retry-After`? not visible in this repo | retry 5xx/429/network, drop 4xx, pause on 401 | Exponential 2 s to 5 min, `Retry-After` honored, 401 refresh, bisect-on-400/413/422, never retry 4xx (`handleResponse`, `dropRefused`) | Best of the three. Add jitter; cap retries per event age (P2) |
| Background work | WorkManager for push-delivery metrics + foreground handoff | WorkManager (expedited) for notification build; HandlerThread runner for queue | None. In-process coroutine timer only; events wait until next launch + `identify()` | Use WorkManager only for push-click receipts (P1). Defer persisted-token queue flush (see section 4) |
| JWT lifecycle | n/a (key-based) | proactive refresh from `exp`, foreground refresh, retry policy, pause, typed failure reasons | Refresh only reactively on 401, single mutex + epoch guard, 15 s timeout, `onAuthenticationFailed()` with no detail (`Kirora.refreshUserToken`, `JwtClaims`) | Proactive refresh from `exp`; typed failure reason on callback (P1) |
| Token at rest | `SecureUserStore` AES-GCM on separate executor | `IterableKeychain` AES, decrypt-failure handler | In memory only, by design (`Kirora.userToken`) | Keep. Revisit only if background delivery becomes a requirement |
| Push token handling | FCM + FID, GMS availability check, publishes token via EventBus, re-registers on identify | `onNewToken` -> re-register | FCM token via bridge, registration keyed by `subject|token|permission|day` (`KiroraPushManager.doRegister`) | Good (idempotent). Add GMS availability check + log (P2) |
| Notification rendering | Builder, channels, image downloader, live notifications | expedited WorkManager job for image/build; ghost pushes | Draws on the FCM thread, blocks up to 3 s for image, https-only, 5 MB, 1024 px, buttons, tag replace, channel fallback (`KiroraPushDisplay.render`) | Acceptable. No dedupe of redelivered FCM ids; no silent push (P2) |
| Click/open tracking | trampoline `singleTask`, delivery + open metrics, delete intent | trampoline + receiver, pending action replay | Trampoline activity, `opened`/`clicked`+button, trusted-host check, but **fire-and-forget, in-memory dedupe, no retry or persistence** (`KiroraPushTracker`, `KiroraPushManager.reportClick`) | Persist + retry click receipts (P0/P1) |
| Delivered receipts | `Delivered` metric via WorkManager + pending store | n/a | None client-side (open/click only) | Confirm with backend whether needed (P2) |
| Deep links | `PushClickBehavior`, host vs external intents, `DeepLinkUtil` | `IterableUrlHandler`, `setAllowedProtocols`, tracked-link resolver | Blocklist schemes, app link first then https, launcher fallback; no host override hook (`KiroraPushTrampolineActivity.openAction`) | Add a URL handler hook + allowlist option (P1) |
| Android 13+ permission | host app does it | host app does it | SDK-provided transparent permission activity, shared pending callbacks (`KiroraPermissionActivity`) | Better than both. Keep |
| Permission and consent split | n/a | n/a | OS permission vs marketing consent separated, tracking consent (`setPushConsent`, `requireTrackingConsent`) | Keep |
| Logging | `Logger` interface + `CioLogLevel`, `LogcatLogger`, categorized tags | `IterableLogger` with levels, emoji | `KiroraLogger` object, levels none/error/debug, listener API, never logs tokens/payloads | Add an in-memory ring buffer / `getDiagnostics()`; make `KiroraLogger` non-public or documented (P2) |
| Error handling | sealed `HttpRequestFailure`, Result types | callbacks with reasons | `track` never throws; `UserTokenError`; `onEventsDropped(count, reason)`; unused public `KiroraApiError` | Remove or use `KiroraApiError`; surface refusals (P2) |
| Threading | `DispatchersProvider`, `ScopeProvider`, main-thread poster | dedicated SDK executors (3.11.1), HandlerThread | `Dispatchers.IO` scope, `limitedParallelism(1)` queue writes, `@Volatile` fields, `@Synchronized` identity | Inject dispatchers for tests; keep serial queue (P1) |
| Tests | JUnit5/MockK/Robolectric, `common-test`, ~300 test files, coroutine race tests (`DeviceTokenRefreshRaceTest`) | 64 files, MockWebServer, work-testing, concurrency tests, real-backend integration module | ~1.5k test lines, Robolectric, fake API service, wire-contract test, regression test; no concurrency/race suite, no instrumented tests, no R8 test | Add race tests, R8 smoke build, MockWebServer e2e (P1) |
| ProGuard / R8 | per-module `consumer-rules.pro`, Retrofit-aware, FCM-reflection rule | empty rules file | Broad keeps for `model.**`, `net.models.**`, whole `db.**`; Gson `@SerializedName` rule; bridge keeps (`consumer-rules.pro`) | Narrow the keeps, add verification via a minified sample (P1) |
| Dependency weight | Segment + WorkManager + FCM (`api`) | gson, work, security-crypto, appcompat (`api`!) | Room + Retrofit + Gson + OkHttp 5 + lifecycle; FCM `implementation` in push module | Reconsider Retrofit/Gson; fix FCM scope (P0, see below) |
| OkHttp version collisions | CI matrix over OkHttp 5.4 / 5.5 | n/a (HttpURLConnection) | Pins OkHttp 5.3.2 + Retrofit 3.0.0 as `implementation` | Add a compat check or switch to HttpURLConnection (P2) |
| Min SDK / compile SDK | minSdk 24, compile 36 | minSdk 21, compile 34 | minSdk 24, compile 35, Java 11, firebase-messaging 24.1.0 | OK. Bump compile to 36 and FCM to current on the next cycle (P2) |
| Samples | Compose + Java samples, fastlane, CI-built per PR | `app` + inbox customization | `app` Compose demo (`io.kirora.demo`), not built in CI | Add a Java sample and build samples in CI (P2) |
| CI | ~15 workflows | build, lint, CodeQL, changelog, release | **No CI at all** (no `.github`) | P0 |
| Release | semantic-release, snapshot releases, Central via shared plugin, CHANGELOG generated | `maven-push.gradle`, `prepare-release.yml`, manual CHANGELOG with migration notes | `maven-publish` + `signing` in each module, version in `gradle.properties`, POM missing licenses/developers/scm, no CHANGELOG | P0 (POM/CHANGELOG) / P1 (automation) |
| Docs | docs site, KDoc, `AGENTS.md`, dev-notes | README, CHANGELOG with "Migration guide" sections | One 109-line README (good quality), KDoc on public API | Add CHANGELOG + migration conventions now (P0) |
| Size/perf guardrails | per-PR AAR size diff, 16 KB page check | none | none | Add AAR size report (P2) |

---

## 3. Prioritized recommendations

Effort: S = under a day, M = 1 to 3 days, L = a week or more.

### P0: fix before a public release

#### P0-1. Make `firebase-messaging` an `api` dependency of `kirora-sdk-push` (correctness bug)

- **Why:** `KiroraPush.handleMessage(context, message: RemoteMessage)` and `setFallbackMessageHandler((RemoteMessage) -> Unit)` are public API in `kirora-sdk-push`, but the module declares `implementation(libs.firebase.messaging)`. In the published POM that is runtime scope, so a consumer that follows the README ("brings Firebase Messaging") gets a *compile* error on `RemoteMessage` unless they add the dependency themselves. The demo app hides this because `app/build.gradle.kts` line 58 adds `firebase.messaging` itself.
- **Reference:** CIO `messagingpush/build.gradle` line 41: `api Dependencies.firebaseMessaging` (the public `CustomerIOFirebaseMessagingService.onMessageReceived(context, RemoteMessage)` has the same shape).
- **Kirora files:** `kirora-sdk-push/build.gradle.kts` (switch to `api(libs.firebase.messaging)`); then remove the redundant line in `app/build.gradle.kts`.
- **Effort:** S.

#### P0-2. Adopt Kotlin binary-compatibility-validator and trim the accidental public surface

- **Why:** `explicitApi()` is on, but nothing prevents later breakage, and the public surface already has leaks: `KiroraLogger` and `LogEntry` (public object/data class), `KiroraPushTrampolineActivity` (a public Activity class with public `EXTRA_*` constants), `KiroraApiError` (public, never thrown anywhere in main code; only referenced from `KiroraDataModelsTest`), `Kirora.options`, `Kirora.active`, `Kirora.handlePushMessage/handleNewPushToken/handleNotificationTap` (public statics that exist only so the push artifact can call core). A `data class LogEntry` is also a binary-compat liability.
- **Reference:** CIO root `build.gradle` (`apply plugin: 'binary-compatibility-validator'`, `apiValidation { ignoredPackages += [...] }`), `core/api/core.api`, `datapipelines/api/datapipelines.api`, `.github/workflows/binary-validator.yml`, `scripts/binary-validation.sh`, `docs/dev-notes/KOTLIN-BINARY-VALIDATOR.md`, `@InternalCustomerIOApi` opt-in annotation (`base/.../internal/InternalCustomerIOApi.kt`) used on every cross-module hook.
- **Kirora files:** root `build.gradle.kts` + `gradle/libs.versions.toml` (add plugin); new `kirora-sdk/api/kirora-sdk.api`, `kirora-sdk-push/api/kirora-sdk-push.api`; add a `@KiroraInternalApi` `@RequiresOptIn` annotation and apply it to the cross-module statics in `Kirora.kt` companion, `KiroraPushTrampolineActivity.kt`; make `KiroraLogger.kt` / `LogEntry` internal or deliberately public; delete or wire `KiroraApiError` (`model/KiroraExceptions.kt`).
- **Effort:** S to M.

#### P0-3. Stand up CI (build, unit tests, lint, apiCheck, sample build)

- **Why:** Kirora has no `.github` directory, so none of the 1.5k test lines, the R8 rules or the publishing config is exercised automatically. Both references gate every PR.
- **Reference:** CIO `.github/workflows/test.yml`, `lint.yml`, `binary-validator.yml`, `build-sample-apps.yml`, composite action `.github/actions/setup-android`; ITBL `.github/workflows/build.yml` (wrapper validation, lint, tests), `codeql.yml`, `changelog-check.yml`.
- **Kirora files:** new `.github/workflows/ci.yml` (jobs: `./gradlew :kirora-sdk:testDebugUnitTest :kirora-sdk-push:assembleRelease`, `lintDebug`, `apiCheck`, `:app:assembleDebug`, Gradle wrapper validation), plus `dependabot.yml`.
- **Effort:** S to M.

#### P0-4. Release hygiene: POM metadata, CHANGELOG, migration-note convention, namespace consistency

- **Why:**
  - Both `build.gradle.kts` files say the POM lacks `<licenses>`, `<developers>`, `<scm>` ("deliberately not guessed"); Maven Central will reject the upload.
  - No CHANGELOG exists. ITBL's CHANGELOG is the model: every entry has Added/Fixed/Changed/Deprecated/Removed, "**No action required**" statements, affected versions for regressions, and a "Migration guide" block with code (see `CHANGELOG.md` 3.10.0 and 3.11.0). CIO generates its changelog from conventional commits (`.releaserc.json`).
  - Identifier inconsistency to settle before 1.0 (changing later is a breaking change): Android `namespace = "io.kirora.sdk"` (`kirora-sdk/build.gradle.kts`) and `io.kirora.sdk.push` (push module), but the Kotlin package is `com.kirora.androidsdk`, the Maven group is `com.kirora`, the demo is `io.kirora.demo`, and manifest meta-data keys use `io.kirora.*`. Pick one reverse-DNS root.
- **Reference:** ITBL `CHANGELOG.md`, `.github/workflows/changelog-check.yml` (PR must touch CHANGELOG or say "No customer facing changes"), `.github/pull_request_template.md`; CIO `.releaserc.json`, `CHANGELOG.md`, `.github/workflows/deploy-sdk.yml`.
- **Kirora files:** `kirora-sdk/build.gradle.kts`, `kirora-sdk-push/build.gradle.kts` (pom blocks), new `CHANGELOG.md`, package/namespace decision across both modules, `AndroidManifest.xml` class names, `consumer-rules.pro` (package names are hard-coded there).
- **Effort:** S (POM, CHANGELOG), M (package rename if chosen, mechanical but wide).

#### P0-5. Make push open/click receipts durable (retry, survive offline and process death)

- **Why:** `KiroraPushTracker.report` and `KiroraPushManager.reportClick` are fire-and-forget: one HTTP attempt, in-memory `LinkedHashSet` dedupe, no retry. A tap while offline or just before the process dies is lost forever, which understates campaign performance. The tap path is also the *only* place that runs while the app may be cold, so it is where a durable queue pays off. Additionally `KiroraPushTracker.report` builds a brand-new `OkHttpClient` + Retrofit through `KiroraApiClient.create(...)` on every tap (new connection pool per tap).
- **Reference:** CIO's design for the same problem: persist first (`PendingPushDeliveryMetric` in `PendingDeliveryStore`, `messagingpush/.../store/PendingPushDeliveryMetric.kt`), then enqueue a unique `OneTimeWorkRequest` with a CONNECTED constraint (`PushDeliveryMetricsBackgroundScheduler.scheduleDeliveredPushMetricsReceipt`, `ExistingWorkPolicy.KEEP`, keyed by delivery id), with a worker that claims/sends/restores (`PushDeliveryMetricsWorker`, `store.claimSendRestore`), a no-WorkManager fallback, and a foreground flusher that cancels the worker and replays (`core/.../PendingDeliveryFlusher.kt`). ITBL's alternative is persisting taps into its offline task table (`OfflineRequestProcessor.offlineApiSet` includes `ENDPOINT_TRACK_PUSH_OPEN`).
- **Kirora files:** `push/KiroraPushTracker.kt`, `push/KiroraPushManager.kt` (remove the duplicated trusted-URL logic; there are two copies), `db/KiroraDatabase.kt` (add a `push_receipts` table, schema 3 with a real `Migration`), new `push/PushReceiptWorker.kt`, `kirora-sdk/build.gradle.kts` (add `androidx.work:work-runtime`, already in the version catalog as `work = "2.10.0"`), `Kirora.handleNotificationTap`. Because receipts need no user token (`Kirora-No-Credentials`), they are safe to send from a worker without persisting any secret.
- **Effort:** M.

### P1: high value, do next

#### P1-1. Proactive JWT refresh and typed auth failures

- **Why:** Kirora refreshes only after a 401 (`KiroraBatchManager.flush` -> `TokenSource.refresh`). `JwtClaims` only reads `sub`, so `exp` is ignored. Every token expiry therefore costs a failed request, and `onAuthenticationFailed: () -> Unit` gives the app nothing to act on (expired versus bad signature versus wrong user). ITBL shows the full lifecycle.
- **Reference:** ITBL `IterableAuthManager.java` (`queueExpirationRefresh`, `decodedExpiration`, `onSwitchToForeground`, `getNextRetryInterval`, `handleAuthFailure`), `IterableAuthRefreshReason.java` (which reasons ignore the retry cap), `AuthFailureReason.java`, `AuthFailure.java`, `RetryPolicy.java`, `IterableConfig.Builder.setAuthRetryPolicy / setExpiringAuthTokenRefreshPeriod`; the v3.10.1 CHANGELOG entry on a refresh-timer race (design with a single owner from the start); v3.11.0 entry on validating the period input.
- **Kirora files:** `utils/JwtClaims.kt` (add `expiresAt`), `Kirora.kt` (`adoptToken` schedules a refresh at `exp - lead`; on `ProcessLifecycleOwner` start re-check; keep `tokenMutex` + `identityEpoch`), `model/KiroraOptions.kt` (`onAuthenticationFailed: ((AuthFailureReason) -> Unit)?`, optional `tokenRefreshLeadSeconds`), `net/KiroraBatchManager.kt` (map the 401 `code` field to a reason; Kirora already parses `KiroraApiError.code` conceptually). Do *not* copy Iterable's timer-per-token approach verbatim; use one coroutine `Job` that is cancelled on every `adoptToken`/`logout`.
- **Effort:** M.

#### P1-2. Anonymous-to-known handling for pre-identify events

- **Why:** Today events tracked before `identify()` stay on the device (cap 1000, oldest dropped) and are sent as the next user to sign in (README: "Nothing is sent while nobody is signed in"). For an app with a long pre-login funnel (onboarding, browse) this loses the oldest events and delays attribution until sign-in. Both references solve it, CIO with an anonymous id merged server-side and Iterable with a bounded local visitor store plus a merge call.
- **Reference:** CIO `datapipelines/.../CustomerIO.kt` `identifyImpl` / `clearIdentifyImpl` / `anonymousId` (Segment `analytics.anonymousId()`), `datapipelines/.../plugins/ContextPlugin.kt`; ITBL `UnknownUserManager.java` (`trackUnknownEvent`, `enforceEventThresholdLimit`, `syncEventsAndUserUpdate`), `UnknownUserMerge.tryMergeUser`, `IterableIdentityResolution.kt` (replay and merge toggles).
- **Kirora files:** `net/KiroraBatchManager.kt` (allow an anonymous flush path keyed by `device.id`, which already exists as `KiroraPreferences.getDeviceId()` and is in the context), `Kirora.kt` (`afterIdentify` calls an alias/merge endpoint), `db/EventEntity.kt`. **Requires a backend decision** (anonymous ingestion + merge endpoint). If the backend will not support it, document the cap and expose it (`KiroraOptions.maxQueuedEvents`).
- **Effort:** M plus backend work.

#### P1-3. Host control over deep links and a protocol allowlist

- **Why:** `KiroraPushTrampolineActivity.openAction` blocks `javascript/file/content/intent/data` but otherwise opens any other scheme, and the app can only observe taps through `addNotificationClickListener`, *after* the SDK has already started navigation. Apps with their own router (very common) cannot veto or reroute. A blocklist is also weaker than an allowlist for untrusted payloads.
- **Reference:** ITBL `IterableUrlHandler.handleIterableURL(uri, ctx)` (returning true cancels default handling; invoked from `IterableActionRunner`), `IterableConfig.Builder.setAllowedProtocols`; CIO `PushClickBehavior` (task-stack semantics), `messagingpush/.../util/DeepLinkUtil.kt` (`createDeepLinkHostAppIntent` vs `createDeepLinkExternalIntent`, `takeIfResolvable`), `messagingpush/.../activity/NotificationClickReceiverActivity.kt` (`singleTask`, `noHistory`, `taskAffinity=""`).
- **Kirora files:** `push/KiroraPushTrampolineActivity.kt`, `model/KiroraOptions.kt` (`KiroraPushOptions.onNotificationLink: ((Uri, Map<String,String>) -> Boolean)?`, `allowedLinkSchemes`, `clickBehavior`), `kirora-sdk/src/main/AndroidManifest.xml` (consider `launchMode="singleTask"` as CIO does, to avoid duplicate tasks). Also add `<queries>` for https/http VIEW like CIO's manifest so `resolveActivity` works under Android 11 package visibility; Kirora's `own.resolveActivity(...)` and `startActivity(view)` depend on it.
- **Effort:** S to M.

#### P1-4. Slim `Kirora.kt` and add test seams for concurrency

- **Why:** `Kirora.kt` is 765 lines mixing identity, token refresh, queue writes, consent, push facade, lifecycle and a global `active`. The concurrency logic (epoch guard, `adoptToken` under `@Synchronized`, `detachQueue`, serial dispatcher) is the most delicate part of the SDK and is only exercised through `Kirora` itself. Both references isolate it (CIO: DI graph + `DispatchersProvider` + `ScopeProvider`; tests like `DeviceTokenRefreshRaceTest`, `ConcurrentScreenViewTest`, `DataPipelinesStandardDispatcherTest`; ITBL: `IterableRequestExecutionConcurrencyTest`, `EmbeddedSessionManagerThreadSafetyTest`, `IterableAuthRefreshOwnershipTest`).
- **Reference:** CIO `core/.../core/util/DispatchersProvider.kt`, `ScopeProvider.kt`, `Clock.kt`, `core/.../di/DiGraph.kt`; `common-test/.../core/{UnitTest,RobolectricTest}.kt`.
- **Kirora files:** split `Kirora.kt` into `IdentityManager` (token, subject, epoch, refresh), `EventTracker` (validate, enqueue), `ConsentManager`, and keep `Kirora` a thin facade; inject `DispatchersProvider` and a `Clock` (replace direct `System.currentTimeMillis()` and `Dispatchers.IO` in `Kirora`, `KiroraBatchManager`, `SessionManager`, `KiroraPushManager`); add race tests (identify vs logout, 401 refresh vs user switch, consent withdrawn mid-enqueue). Remove `setApiServiceForTesting` once the graph is injectable.
- **Effort:** M to L.

#### P1-5. Move slow setup off the caller's thread; add an explicit readiness signal

- **Why:** The README promises that creating `Kirora` "never blocks on the network", which is true, but `setup()` runs on the main thread inside `Application.onCreate`: `KiroraPreferences` (SharedPreferences load plus a `noBackupFilesDir` marker file check, `resetIfRestoredFromBackup`), `KiroraDatabase.getInstance` (Room builder), `getPackageInfo`, `KiroraPushDisplay.ensureDefaultChannel` (binder call), `ConnectivityManager.registerNetworkCallback`, and `prefs.savePushDisplay`. CIO shipped a fix for exactly this class of issue ("move keystore work off the caller thread at init and login", 5.0.3), and ITBL added `initializeInBackground` + `onSDKInitialized` + `isSDKInitialized` for ANR avoidance.
- **Reference:** CIO `CHANGELOG.md` 5.0.3; `core/.../data/store/SecureUserStore.kt` (dedicated writer executor, in-memory "current" value so reads are consistent before disk catches up); ITBL `IterableBackgroundInitializer.java`, `IterableApi.initializeInBackground/onSDKInitialized`.
- **Kirora files:** `Kirora.kt` `setup()`; `utils/KiroraPreferences.kt`; keep the public contract that `track()` right after construction works (buffer in memory until the background part is done, as CIO's store does). Add `Kirora.onReady(callback)` or a `StateFlow<Boolean>` instead of the bare `@Volatile isInitialized`. Verify with StrictMode in the demo app.
- **Effort:** M.

#### P1-6. Verify ProGuard/R8 behavior with a minified consumer and narrow the keep rules

- **Why:** `consumer-rules.pro` keeps all of `model.**`, `net.models.**` and `db.**` and every `@SerializedName` field in *any* class (`-keepclassmembers class * { @SerializedName <fields>; }` applies to every library in the host app). The library itself builds with `isMinifyEnabled = false`, so the rules are never executed in CI, and nothing proves that a minified host still works (Gson + Retrofit + Room + reflection bridge `Class.forName(PUSH_BRIDGE_CLASS)`).
- **Reference:** CIO per-module `consumer-rules.pro` (Retrofit `-if interface * { @retrofit2.http.* <methods> }` blocks, `-keepattributes Signature,...`), `messagingpush/consumer-rules.pro` (precise `-keepclassmembers` on one Firebase method), sample apps built in CI (`build-sample-apps.yml`) and `samples/*/proguard-rules.pro`.
- **Kirora files:** `kirora-sdk/consumer-rules.pro` (scope `@SerializedName` rule to `com.kirora.androidsdk.**`; drop `-keep class ...db.** { *; }` in favor of what Room needs, which ships its own rules), `kirora-sdk-push/consumer-rules.pro`, `app/build.gradle.kts` (enable `isMinifyEnabled = true` with `isShrinkResources` for a `release` variant and run a simple instrumented or Robolectric smoke test against it in CI). Also add `-dontwarn` entries only as proven necessary.
- **Effort:** S to M.

#### P1-7. Reject non-public client keys; add unified config validation

- **Why:** CIO refuses `sk_` keys and logs an error while leaving a disabled instance, so a leaked secret key never gets embedded in a shipping app. Kirora only trims and checks emptiness. Validation (`clientKey`, `apiUrl` https-or-localhost) is already centralized in `Kirora.setup`; extending it is cheap. ITBL's CHANGELOG (3.11.0) also shows the user-facing value of logging at ERROR when a config is silently corrected, because the log level is not applied yet at build time.
- **Reference:** CIO `core/.../core/util/CioApiKey.kt`, `datapipelines/.../CustomerIO.kt` `initialize` + `initializeDisabled`, `DataPipelinesLogger.coreSdkSecretKeyRejected`; CHANGELOG 4.25.0 ("reject secret keys and pick region from key").
- **Kirora files:** `Kirora.kt` `setup()`, `model/KiroraOptions.kt`; needs the backend's key-prefix spec (README only shows `kirora_pk_`). Reject `kirora_sk_`, log at ERROR, stay disabled.
- **Effort:** S.

### P2: improvements and polish

#### P2-1. Skip flush ticks while backgrounded; add jitter; cap retry age

- **Why:** `KiroraBatchManager.startTimer` ticks every 30 s for the life of the process, including while the app is in the background, where Android 15+ may block network. A failed background request goes through `applyBackoff`, which doubles `currentBackoffMs` even though nothing was wrong with the server. `applyBackoff` also has no jitter, so a fleet that comes online together retries in lockstep.
- **Reference:** CIO `datapipelines/.../plugins/policies/BackgroundAwareFrequencyFlushPolicy.kt` and `util/AppForegroundState.kt`; ITBL `IterableTaskRunner.isPermanentFailure` for the 4xx/429/network classification (Kirora's version is already stricter and better; no change needed there).
- **Kirora files:** `net/KiroraBatchManager.kt` (timer loop + `applyBackoff`), `session/SessionManager.kt` (`foreground` flag already exists); `Kirora.kt` (`onBackground` still flushes once).
- **Effort:** S.

#### P2-2. FCM hardening: GMS availability, message-id dedupe, expedited work for images, optional silent push

- **Why:** `fetchFcmToken` calls FCM without checking Google Play Services (Huawei and de-Googled devices log noise and retry on every `registerDevice`). Redelivered FCM messages without a `tag` render twice (no dedupe), while CIO dedupes on `deliveryId` with a bounded recent queue. `KiroraPushDisplay.render` blocks the FCM callback for up to 3 s on the image; ITBL moved this to an expedited worker. There is no data-only "ghost" path for silent syncs.
- **Reference:** CIO `messagingpush/.../provider/FCMTokenProviderImpl.kt` (`isGooglePlayServicesAvailable`), `processor/PushMessageProcessorImpl.getOrUpdateMessageAlreadyProcessed`; ITBL `IterableNotificationWorkScheduler.java` / `IterableNotificationWorker.kt` (`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`), `IterableNotificationHelper.isGhostPush`.
- **Kirora files:** `kirora-sdk-push/.../FcmPushBridge.kt` (GMS check, returns null + log), `push/KiroraPushDisplay.kt` (dedupe on a `messageId`/`trackingUrl` payload key; optional worker for image), `kirora-sdk-push/build.gradle.kts` (play-services-base if checking GMS). Only do the worker if 3 s FCM blocking shows up in field data; it adds WorkManager (which P0-5 may already bring).
- **Effort:** S (GMS + dedupe), M (worker).

#### P2-3. Observability: diagnostics snapshot and ring buffer

- **Why:** Today support has to ask customers for `debug` logcat. The SDK already tracks `droppedEventCount`, `getQueuedEventCountFlow`, `sessionId`, `deviceId`, `pushToken`, consent state; a single `Kirora.diagnostics()` (queue depth, last flush time/result, backoff state, token expiry, push registration state, SDK version) plus a small redacted in-memory log buffer makes bug reports actionable. CIO logs init/module lifecycle with categorized tags (`DataPipelinesLogger.INIT_TAG`, `PUSH_TAG`, `PushNotificationLogger`); ITBL exposes `HealthMonitor`.
- **Reference:** CIO `core/.../core/util/Logger.kt` + `LoggerImpl.kt` + `datapipelines/.../DataPipelinesLogger.kt`, `messagingpush/.../logger/PushNotificationLogger.kt`; ITBL `HealthMonitor.java`.
- **Kirora files:** `utils/KiroraLogger.kt`, `Kirora.kt`, `net/KiroraBatchManager.kt`, `db/KiroraDatabase.kt` (`HealthMonitor` idea: if Room throws on open/insert repeatedly, stop scheduling, log once, and surface `onEventsDropped(…, STORAGE_ERROR)`; currently every insert failure is only logged in `trackInternal`, and a corrupted DB means silent loss).
- **Effort:** S to M.

#### P2-4. Auto screen tracking and lifecycle event options

- **Why:** CIO ships opt-in activity screen tracking (`autoTrackActivityScreens`, `AutomaticActivityScreenTrackingPlugin`, `ScreenFilterPlugin`) and lifecycle events including a fix for SDK initializing after the first Activity (4.25.1). Kirora has `trackAppLifecycle` and a manual `trackScreenView` only. Since `Kirora` is created in `Application.onCreate`, the late-init edge CIO fixed does not apply, but a replaced instance mid-session might.
- **Reference:** CIO `datapipelines/.../plugins/AutomaticActivityScreenTrackingPlugin.kt`, `ScreenFilterPlugin.kt`, `core/.../tracking/TrackableScreen.kt`.
- **Kirora files:** `session/SessionManager.kt`, `model/KiroraOptions.kt` (`autoTrackActivityScreens`), `Kirora.kt`.
- **Effort:** S to M.

#### P2-5. Dependency weight and host-app compatibility

- **Why:** Kirora pulls Retrofit 3 + Gson + OkHttp 5.3.2 + Room + lifecycle into every host. A host on OkHttp 4 or Gson 2.8 is silently upgraded by Gradle conflict resolution, and Gson's reflection is the main reason `consumer-rules.pro` needs broad keeps. The SDK uses a handful of endpoints (six in `KiroraApiService`). CIO keeps the surface small (Segment + WorkManager; no Retrofit in `core`) and tests against multiple OkHttp versions in CI (`test.yml` okhttp-compatibility-test matrix); ITBL uses `HttpURLConnection` and `org.json`.
- **Kirora files:** `net/KiroraApiClient.kt`, `net/KiroraApiService.kt`, `net/models/NetworkModels.kt`, `kirora-sdk/build.gradle.kts`. Options: (a) keep OkHttp, drop Retrofit + Gson in favor of hand-written calls with `org.json` (Android built-in) or kotlinx-serialization; (b) at least add a CI job that builds a host on OkHttp 4.12 and 5.x. Measure with an AAR size report first (P2-7). Weigh against rewrite cost: this is optional and should be driven by measured size and conflict reports.
- **Effort:** M to L.

#### P2-6. Java-friendly surface and docs

- **Why:** `fetchUserToken: (suspend () -> String?)?` and `suspend fun identify` are unusable from Java (`identifyAsync` covers the call, but the option cannot be set from Java). ITBL and CIO both serve Java apps (CIO ships `samples/java_layout`; ITBL is Java). Add a `KiroraOptions.Builder` and a callback overload of `fetchUserToken` (`TokenProvider` interface), `@JvmStatic` on companion members where missing, and a Java sample.
- **Reference:** CIO `CustomerIOConfigBuilder.kt`, `samples/java_layout/`; ITBL `IterableConfig.Builder`.
- **Kirora files:** `model/KiroraOptions.kt`, `Kirora.kt`, `app/` (or a new `samples/java`).
- **Effort:** S to M.

#### P2-7. Size, 16 KB and sample-app CI guardrails

- **Why:** Cheap regression guards CIO already runs: AAR size diff on every PR (`sdk-binary-size.yml` + `scripts/check-sdk-size.sh`), 16 KB page-size check for the host (`page-size-check.yml`, `.github/scripts/check_16kb_page_size.py`; relevant because Room/Firebase can pull native libs), and sample apps built on every PR. Add a `lintDebug`/`lintRelease` run with `abortOnError` and a `detekt`/`ktlint` config (CIO `lefthook.yml`, `Makefile` `lint-no-format`).
- **Kirora files:** new `.github/workflows/size.yml`, `scripts/`, root `build.gradle.kts` (ktlint/detekt), `.editorconfig`.
- **Effort:** S.

#### P2-8. Plugin-style extension points (only when a second feature module appears)

- **Why:** CIO's `CustomerIOModule` + EventBus + `IdentifyHook` is what lets in-app, inbox, location and geofence ship as separate artifacts without circular dependencies. Kirora's reflection bridge (`KiroraPushBridge` + `Class.forName`) does the same job for exactly one module. If in-app/inbox/embedded messaging are on the roadmap, introduce `KiroraModule { name; initialize(ctx) }` and a tiny internal event bus (`UserChanged`, `Reset`, `TokenRegistered`, `ScreenViewed`) at that time, not before.
- **Reference:** CIO `core/.../core/module/CustomerIOModule.kt`, `communication/EventBus.kt`, `Event.kt`, `pipeline/IdentifyHook.kt`, `docs/dev-notes/MODULES.md`.
- **Kirora files:** `push/KiroraPushBridge.kt`, `Kirora.kt` (`loadPushBridge`, companion statics).
- **Effort:** M (when needed).

#### P2-9. Targeted cleanups found while reading (small)

- `KiroraPushTracker.report` creates a new `OkHttpClient` per tap (use a lazily created shared client).
- `KiroraPushManager.reportClick` and `KiroraPushTracker.trusted` duplicate the same host/path trust logic; keep one.
- `KiroraPushManager` holds unused or duplicated listener APIs (`addNotificationClickListener` etc. also exist on `Kirora`'s companion); keep one path.
- `KiroraPushManager.CHANNEL_ID_DEFAULT` duplicates `PushDisplayConfig.DEFAULT_CHANNEL_ID`.
- `KiroraPermissionActivity.onDestroy` has a duplicated comment; `Kirora.kt` has a mis-indented KDoc and a KDoc attached to the wrong function (`requestPushPermission`).
- The `KiroraDatabase` has `fallbackToDestructiveMigration(true)`: a failed future migration silently deletes queued events. Prefer failing to an in-memory-free state with `onEventsDropped` and keep the explicit migrations.
- Bump `firebase-messaging` (24.1.0 in `libs.versions.toml`; CIO is on 26) and `compileSdk` 35 -> 36 on the next dependency pass; consider the FID support CIO added (`FirebaseTokenSource`) only if the backend wants installation IDs.
- **Effort:** S total.

---

## 4. What Kirora already does well, and what not to copy

### Already strong (keep; in several cases better than both references)

- **User-partitioned queue and identity safety.** `EventEntity.userSubject` + `EventDao.getBatchFor`, `Kirora.adoptToken` (user switch without logout detaches the old user's events and sends them under the old token), `detachQueue`, and the `identityEpoch` guard that discards a token fetched for an older sign-in state. This is more rigorous than CIO's identify (which stamps identity at processing time and needs workarounds, see the comments in `CustomerIO.clearIdentifyImpl`) and ITBL's (merge-based). Do not simplify it.
- **Delivery semantics.** Idempotency keys (unique index), `seq` ordering, `Retry-After` parsing, exponential backoff, bisecting a refused batch to isolate the bad event (`dropRefused`), `onEventsDropped(count, reason)` with a typed `EventDropReason`, and `droppedEventCount`. ITBL just deletes 4xx tasks; CIO delegates to Segment. Keep.
- **Token never at rest.** In-memory-only user JWT, app re-supplies it at start (`fetchUserToken`), `Kirora-No-Credentials` interceptor header, redirects disabled so the client key cannot leave the API host, https enforced (localhost/10.0.2.2 exempt), log interceptor prints method/path/status only. ITBL and CIO both persist identity (encrypted); Kirora's stance has a smaller attack surface and needs no Keystore (a source of ANRs/timeouts in both references: ITBL 3.11.0 keychain timeout fix, CIO 5.0.3).
- **Consent model.** `requireTrackingConsent` (stores nothing until granted), withdrawal clears the queue, and OS permission is separated from marketing consent with a pending-sync flag (`setPushConsent`, `KiroraPreferences.isPushConsentPending`). Neither reference models this.
- **Push UX.** The SDK-provided Android 13+ permission flow (`KiroraPermissionActivity` with shared pending callbacks), a trampoline *Activity* (no service/receiver in the tap path, compliant with Android 12 trampoline limits), cold-start support (display config persisted in prefs; pending-click buffer replayed to late listeners), untrusted-payload handling (trusted tracking host, https-only buttons, blocked schemes), a low-priority default FCM service that yields to the app's own (`priority="-500"`), and a clear fallback chain for the small icon. Comparable to or better than both.
- **Restore-from-backup safety.** The no-backup DB file and the install-marker check (`KiroraPreferences.resetIfRestoredFromBackup`) prevent replaying another device's events. Neither reference handles this.
- **Public API hygiene basics.** `explicitApi()`, an options class that is deliberately not a `data class` (binary-compat friendly), `@JvmOverloads`, `track` that never throws, `identifyAsync` for non-coroutine callers, and a README that documents behavior honestly (including limits and security notes). The README quality is above both references' READMEs.
- **Tests with intent.** `KiroraWireContractTest` (payload shape pinned), `KiroraRegressionTest`, a fake API service, and Room schema export (`schemas/*.json`) with an explicit migration.

### Do NOT copy

- **A hand-rolled global DI graph with a public `SDKComponent`.** CIO's `SDKComponent` is a public `object` with mutable global state (reset needed in tests, `modules` map). It earns its keep only with 9 modules. For Kirora, use constructor injection from a small internal factory (P1-4) and keep the single public entry point.
- **Wrapping a third-party analytics engine (Segment fork).** It brings a large dependency and its own storage/flush semantics. Kirora's queue is simpler and stronger on identity partitioning.
- **Monolithic god-class API (`IterableApi.java`, 2,000 lines, 60+ overloads of `setEmail`/`setUserId` with every handler combination).** Keep one method per concept, named arguments and a callback overload at most.
- **Persisting identity or JWT at rest just to enable background sends.** Both references do it and both have shipped Keystore-timeout fixes (ITBL 3.11.0 "keychain treating a transient crypto timeout as a permanent decryption failure"; CIO 5.0.3). If background flushing after process death becomes a real requirement, treat it as a separate, explicit design (encrypted token with TTL, never wipe on timeout) rather than adopting it incidentally. P0-5 needs no token because receipts are anonymous.
- **Lenient silent coercion of bad config.** ITBL accepts nulls and negatives and corrects them with a log line; it spent a changelog entry (3.11.0) cleaning that up. Kirora's current approach (log at ERROR and stay disabled) is the better default.
- **Unbounded `api` dependencies.** ITBL exposes `appcompat`, `legacy-support-v4` and Firebase as `api`. Only declare `api` for types that appear in your public signatures (coroutines core, and per P0-1, Firebase in the push module).
- **Emoji log prefixes, `AsyncTask`-era executors, raw `HandlerThread` queue runners, raw SQLite helpers** (ITBL): Kirora's Room + coroutines are the right modern equivalent.
- **Per-feature raw-WorkManager everything.** CIO uses WorkManager only where a durable, constraint-based retry is the point (delivery metrics, geofence events). Use it the same way (P0-5), not as a replacement for the in-process queue.
- **Live notifications / geofence / location / in-app SSE / inbox / embedded messaging.** Out of scope for the current product surface; revisit with P2-8 only when a roadmap item exists.

---

## 5. Suggested sequencing

1. Week 1 (P0): `api(firebase-messaging)`, BCV + visibility trim, CI, POM + CHANGELOG + namespace decision.
2. Week 2 (P0 + P1 quick wins): durable push receipts, key prefix validation, R8 smoke + narrowed rules, deep-link hook.
3. Weeks 3 to 4 (P1): proactive JWT refresh, off-main init + readiness signal, `Kirora.kt` split with injected dispatchers/clock and race tests, anonymous-event design (with backend).
4. Then P2 items in the order of observed field pain (diagnostics and background-tick skipping first).
