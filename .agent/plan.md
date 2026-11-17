# Project Plan

Build Kirora Mobile SDK for Android (Kotlin) based on the Kirora Engineering Specification and Client SDK interface document.

Key Features & Specifications:
1. Public Interface:
   - Kirora.initialize(context, options: KiroraOptions)
   - identify(token?)
   - logout()
   - track(name, properties?)
   - trackScreenView(name)
   - requestPushPermission()
   - setPushConsent(granted)
   - setTrackingConsent(granted)
   - flush()

2. Configuration Options (KiroraOptions):
   - clientKey (String, required)
   - fetchUserToken (callback returning String?)
   - push (Boolean or PushOptions)
   - requireTrackingConsent (Boolean, default false)
   - trackSessions (Boolean, default true)
   - trackAppLifecycle (Boolean, default false)
   - logLevel (LogLevel: NONE, ERROR, DEBUG)
   - apiUrl (String, default https://api.kirora.io)

3. Core Subsystems & Logic:
   - Persistent Durable Queue (SQLite / Room or light SQLite storage): stores up to 1,000 events across app restarts (oldest dropped when full).
   - Event Batching & Delivery: Batches of up to 100 events, auto-send when 20 events queued or 30 seconds interval, or app backgrounded.
   - Retry Logic & Backoff: Exponential backoff with doubling gaps up to 5 minutes, respecting server Retry-After header. Handles HTTP 401 (re-fetches token via fetchUserToken once), HTTP 429/5xx, HTTP 403 PLAN_FEATURE_UNAVAILABLE (drops custom event batch).
   - Session & Visit Management: Session Started event when visit begins, context.sessionId on every event. Activity is tracked event or app foregrounding. Session expires after 30 minutes of inactivity. Saved in persistent storage.
   - Context Generation: Automatic $device_id (UUID stored locally), $session_id, $locale, $timezone, $platform ("android"), $os_version, $app_version, $sdk_version ("kirora-android/1.0.0"), $device_model.
   - Authentication & Wire Contract:
     - Headers: Kirora-Client-Key, Authorization: Bearer <user_token>, Kirora-SDK: kirora-android/<version>
     - Endpoints: GET /client/v1/push-app, PUT /client/v1/devices, DELETE /client/v1/devices/{externalInstallationId}, PUT /client/v1/push-consent, POST /client/v1/events/batch
   - Push Integration:
     - FCM registration & token handling.
     - Notification received/opened tracking (sending to trackingUrl POST /t/p/{token}).
     - Separate tracking consent & marketing push consent.
   - Thread safety, non-blocking track() API.
   - Demo / Sample App showcasing SDK features, tracking, identity, sessions, and logs.

## Project Brief

# Project Brief: Kirora Mobile SDK & Demo App for Android

## Features

1. **Core SDK API & Context Auto-Enrichment**: Thread-safe, non-blocking public interface (`initialize`, `identify`, `logout`, `track`, `trackScreenView`, `flush`) providing automatic context injection including device ID, session ID, platform, OS/SDK versions, and device model.
2. **Durable Event Queueing & Batch Delivery**: Persistent local storage (Room/SQLite) holding up to 1,000 events across app restarts with automated batch flushing (20 queued events, 30s timer, or app backgrounding), exponential retry backoff, and auth token auto-refresh.
3. **Session & Lifecycle Management**: Automatic session state tracking, foreground/background app lifecycle monitoring, and 30-minute inactivity expiration handling.
4. **Push Notification & Consent Management**: Full Firebase Cloud Messaging (FCM) integration handling token registration, notification delivery/open tracking, and granular tracking/push consent toggles.
5. **Interactive Showcase Demo App**: Jetpack Compose sample application demonstrating SDK initialization, identity management, manual event tracking, FCM push permission requests, and real-time log inspecting.

## High-Level Tech Stack

* **Language**: Kotlin
* **UI Framework**: Jetpack Compose
* **Navigation**: Jetpack Navigation 3 (state-driven)
* **Adaptive Layouts**: Compose Material Adaptive library
* **Concurrency & Async**: Kotlin Coroutines & Flow
* **Local Persistence**: Room Database / SQLite (required for durable event queueing & session state across restarts)
* **Networking & Push**: OkHttp / Retrofit, Firebase Cloud Messaging (FCM)

## Implementation Steps
**Total Duration:** 1h 26m 58s

### Task_1_CoreSDKArchitectureAndData: Build core Kirora SDK architecture including data models, thread-safe public SDK API interface (initialize, identify, logout, track, trackScreenView, flush), automatic context enrichment, and Room database persistent queue.
- **Status:** COMPLETED
- **Updates:** Core SDK data models, KiroraOptions, KiroraContext, Room database event queue (with 1,000 max capacity trimming), thread-safe Kirora public interface, non-blocking track(), consent state management, and unit test suite successfully implemented and verified.
- **Acceptance Criteria:**
  - Kirora SDK public interface defined with thread-safe public methods
  - Automatic context enrichment injecting device ID, session ID, OS/SDK versions, and device model
  - Room Database setup for persistent event queueing supporting up to 1,000 events
- **Duration:** 45m 29s

### Task_2_NetworkingBatchDeliverySessionAndPush: Implement networking layer with batch delivery, exponential backoff retry, automatic session/lifecycle tracking with 30-min inactivity timeout, and FCM push integration with consent management.
- **Status:** COMPLETED
- **Updates:** Networking layer (Retrofit/OkHttp), batch delivery with 20-event/30s/background triggers, 401 token refresh retry, 403 batch drop, exponential backoff, SessionManager with 30-min inactivity timeout and ProcessLifecycleObserver, app lifecycle tracking, and FCM push/consent/permission integration successfully built and verified with passing unit tests.
- **Acceptance Criteria:**
  - Batch delivery triggers on 20 queued events, 30s timer, or app backgrounding with backoff retry
  - Automatic session state tracking and 30-minute inactivity expiration handling
  - FCM token registration, notification delivery/open tracking, and granular consent toggles
- **Duration:** 36m 14s

### Task_3_InteractiveJetpackComposeDemoApp: Build interactive Jetpack Compose Showcase Demo App demonstrating SDK initialization, identity management, manual event tracking, FCM push integration, and live diagnostic logging screen.
- **Status:** COMPLETED
- **Updates:** Jetpack Compose Showcase Demo App built with adaptive NavigationRail/NavigationBar layout, SDK Configuration & Identity Screen, Event & Screen View Tracking Screen, Push Notifications & Consent Screen, and live Diagnostic Console & Queue Inspector Screen. Connected KiroraLogger and Room event queue Flow for real-time monitoring. Verified clean assembleDebug build and testDebugUnitTest suite.
- **Acceptance Criteria:**
  - Jetpack Compose UI displaying SDK initialization, identity management, event tracking, FCM, and diagnostic logs
  - Adaptive layout and state-driven navigation implemented
- **Duration:** 5m 15s

### Task_4_RunAndVerify: Build project, execute test suite, launch app, confirm alignment with all requirements, and instruct critic_agent to verify application stability (no crashes), confirm alignment with user requirements, and report critical UI issues.
- **Status:** IN_PROGRESS
- **Acceptance Criteria:**
  - make sure all existing tests pass
  - build pass
  - app does not crash
  - critic_agent verifies application stability and user requirements alignment
- **StartTime:** 2026-10-17 03:17:04 WAT

