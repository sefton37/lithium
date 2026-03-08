# Plan: Lithium — Android Notification Manager Implementation

## Context

Lithium is a greenfield Android app for notification management, targeting users with ADHD,
autism spectrum conditions, and executive function challenges. The full specification is in
`README.md`. The project directory currently contains only that README — no Gradle files,
no source tree, nothing. Everything must be created from scratch.

The architecture is: `NotificationListenerService` → encrypted Room+SQLCipher database →
deterministic Rule Engine (real-time, hot path) → ONNX AI Engine (WorkManager, overnight) →
Compose Chat UI. The defining constraint is local-first / zero-trust: no network in the main
app, no backups, AES-256 at rest.

This plan follows the M1–M6 milestone structure from the README and prepends a Phase 0
(scaffolding) that must be complete before any feature work begins. The order is strict:
DI wiring must exist before services can use it, the database must exist before repositories,
repositories before the listener, and the listener before there is anything to test end-to-end.

---

## Phase 0: Project Scaffolding

**Goal:** A buildable, installable skeleton with Hilt wired, Room+SQLCipher connected, the
module structure in place, and every security manifest flag set. No features. No UI beyond a
placeholder Activity. But every subsequent phase can be built on top of this without revisiting
plumbing.

### 0.1 — Gradle and Module Setup

**Files to create:**

```
settings.gradle.kts
build.gradle.kts                   (root)
app/build.gradle.kts
diagnostics/build.gradle.kts
gradle/libs.versions.toml          (version catalog)
gradle/wrapper/gradle-wrapper.properties
```

**`gradle/libs.versions.toml` — complete version catalog:**

```toml
[versions]
agp                 = "8.4.0"
kotlin              = "1.9.24"
ksp                 = "1.9.24-1.0.20"
compose-bom         = "2024.05.00"
hilt                = "2.51.1"
room                = "2.6.1"
sqlcipher           = "4.5.6"
onnxruntime         = "1.18.0"
workmanager         = "2.9.0"
security-crypto     = "1.1.0-alpha06"
lifecycle           = "2.8.0"
coroutines          = "1.8.1"
kotlinx-json        = "1.6.3"
junit               = "4.13.2"
junit-android       = "1.1.5"
espresso            = "3.5.1"
mockk               = "1.13.11"
robolectric         = "4.12.2"
turbine             = "1.1.0"

[libraries]
# Compose
compose-bom                = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui                 = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling         = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3          = { group = "androidx.compose.material3", name = "material3" }
compose-activity           = { group = "androidx.activity", name = "activity-compose", version = "1.9.0" }
compose-navigation         = { group = "androidx.navigation", name = "navigation-compose", version = "2.7.7" }
compose-viewmodel          = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# Hilt
hilt-android               = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler              = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose    = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
hilt-work                  = { group = "androidx.hilt", name = "hilt-work", version = "1.2.0" }
hilt-work-compiler         = { group = "androidx.hilt", name = "hilt-compiler", version = "1.2.0" }

# Room + SQLCipher
room-runtime               = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx                   = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler              = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
sqlcipher-android          = { group = "net.zetetic", name = "sqlcipher-android", version.ref = "sqlcipher" }
sqlite-ktx                 = { group = "androidx.sqlite", name = "sqlite-ktx", version = "2.4.0" }

# ONNX Runtime
onnxruntime-android        = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }

# WorkManager
workmanager-ktx            = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workmanager" }

# Security
security-crypto            = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }

# Lifecycle
lifecycle-runtime-ktx      = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-viewmodel-ktx    = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }

# Coroutines
coroutines-android         = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# JSON (for condition_json and AI output parsing)
kotlinx-json               = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-json" }

# Core
core-ktx                   = { group = "androidx.core", name = "core-ktx", version = "1.13.1" }

# Testing
junit                      = { group = "junit", name = "junit", version.ref = "junit" }
junit-android              = { group = "androidx.test.ext", name = "junit", version.ref = "junit-android" }
espresso                   = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
mockk                      = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
mockk-android              = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
robolectric                = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
coroutines-test            = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
turbine                    = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
room-testing               = { group = "androidx.room", name = "room-testing", version.ref = "room" }
work-testing               = { group = "androidx.work", name = "work-testing", version.ref = "workmanager" }

[plugins]
android-application   = { id = "com.android.application", version.ref = "agp" }
android-library       = { id = "com.android.library", version.ref = "agp" }
kotlin-android        = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-kapt           = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
kotlin-parcelize      = { id = "org.jetbrains.kotlin.plugin.parcelize", version.ref = "kotlin" }
kotlin-serialization  = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp                   = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt                  = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

**Key decisions in `app/build.gradle.kts`:**

- `compileSdk = 34`, `minSdk = 29`, `targetSdk = 34`
- `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }`
- `kotlinOptions { jvmTarget = "17" }`
- Enable Compose: `buildFeatures { compose = true }`
- Add `packagingOptions` to exclude conflicting native libraries from SQLCipher and ONNX:
  `packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }`
- `kotlinx-serialization` plugin applied here for `condition_json` parsing
- KSP arguments block for Room schema export:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
```

**Diagnostics module exclusion flag in `app/build.gradle.kts`:**

```kotlin
if (!project.hasProperty("excludeDiagnostics")) {
    implementation(project(":diagnostics"))
}
```

**`settings.gradle.kts`:**

```kotlin
include(":app")
include(":diagnostics")
```

### 0.2 — AndroidManifest Security Hardening

**File:** `app/src/main/AndroidManifest.xml`

Set ALL security flags before writing any feature code. These must be present from day one;
retrofitting them after data is written risks migration issues.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- No INTERNET permission in main app manifest -->
    <uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application
        android:name=".LithiumApp"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:usesCleartextTraffic="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Lithium">
    </application>
</manifest>
```

**`res/xml/data_extraction_rules.xml`** — exactly as specified in the README:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" />
    </device-transfer>
</data-extraction-rules>
```

**`res/xml/network_security_config.xml`:**

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 0.3 — Application Class and Hilt Root

**Files:**

```
app/src/main/java/ai/talkingrock/lithium/LithiumApp.kt
app/src/main/java/ai/talkingrock/lithium/di/AppModule.kt
app/src/main/java/ai/talkingrock/lithium/di/DatabaseModule.kt
app/src/main/java/ai/talkingrock/lithium/di/AiModule.kt
```

`LithiumApp` is annotated `@HiltAndroidApp`. This is the Hilt root — every injected dependency
traces back to this. Create the class now, even if the DI modules are empty stubs.

`LithiumApp` also implements `Configuration.Provider` for the custom Hilt worker factory
(required to use `@HiltWorker`):

```kotlin
@HiltAndroidApp
class LithiumApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

This requires disabling WorkManager's auto-initialization in the manifest by removing the
`androidx.startup` initializer for `WorkManagerInitializer`. Without this step, the app will
crash with a duplicate initialization error.

```xml
<!-- In AndroidManifest.xml, inside <application> -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

`AppModule` provides: application `Context`, `EncryptedSharedPreferences` instance
(for settings and config), application-scoped singletons like `ListenerState`.

`DatabaseModule` provides: `LithiumDatabase` (Room+SQLCipher), all DAOs. This module is where
the Keystore key derivation lives. Detailed in Phase 0.4.

`AiModule` is a stub in Phase 0; populated in M3.

### 0.4 — Room + SQLCipher Database Shell

**Files:**

```
app/src/main/java/ai/talkingrock/lithium/data/db/LithiumDatabase.kt
```

Plus stub files for all 6 DAOs and all 6 entity model classes (at minimum one `@Entity` must
exist for Room to compile the database class).

**SQLCipher key derivation — the most security-critical decision in Phase 0:**

The database key must be: (1) random, not derived from user input or hardcoded; (2) stored in
Android Keystore (hardware-backed where available); (3) never written to disk in plaintext.

The standard approach for this is to generate an AES-256 key in the Android Keystore and use
it to encrypt a fixed nonce. The resulting ciphertext serves as the SQLCipher passphrase. The
nonce is a compile-time constant; security comes from the Keystore key being hardware-protected.

```kotlin
// In DatabaseModule.kt — conceptual outline (not production-ready pseudocode)
// 1. Create or retrieve an AES/GCM key in the AndroidKeyStore under alias "lithium_db_key_v1"
// 2. Use that key to AES-GCM encrypt a fixed 32-byte application constant
// 3. The resulting 48-byte ciphertext (32 bytes data + 16 bytes GCM tag) is the passphrase
// 4. Pass the passphrase to SupportFactory(passphrase)
// 5. Build the Room database with .openHelperFactory(factory)
```

Key considerations for the implementer:
- Use a **deterministic IV** for the passphrase derivation (same key + same IV + same plaintext
  = same ciphertext on every call). A fixed 12-byte IV stored as a compile-time constant is
  acceptable here because this is key derivation, not data encryption. The security property
  is "the Keystore key is hardware-protected," not "the IV is random."
- Store the IV as a compile-time constant. If the IV were stored in SharedPreferences, losing
  it would mean losing access to the database.
- Test: instantiate the database, close it, re-instantiate it using the same derivation path.
  Verify the second open succeeds and the first row inserted is still readable.

`LithiumDatabase.kt` schema setup:

```kotlin
@Database(
    entities = [
        NotificationRecord::class,
        SessionRecord::class,
        Rule::class,
        Report::class,
        Suggestion::class,
        QueuedNotification::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LithiumDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun sessionDao(): SessionDao
    abstract fun ruleDao(): RuleDao
    abstract fun reportDao(): ReportDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun queueDao(): QueueDao
}
```

Enable WAL mode via `.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)` in the
builder. This is critical for write performance — it prevents the listener callback from
being serialized behind read queries.

### 0.5 — Placeholder MainActivity and Navigation Shell

**Files:**

```
app/src/main/java/ai/talkingrock/lithium/MainActivity.kt
app/src/main/java/ai/talkingrock/lithium/ui/theme/Theme.kt
app/src/main/java/ai/talkingrock/lithium/ui/theme/Color.kt
app/src/main/java/ai/talkingrock/lithium/ui/theme/Type.kt
```

`MainActivity` is annotated `@AndroidEntryPoint`. It calls
`WindowCompat.setDecorFitsSystemWindows(window, false)` for edge-to-edge layout and applies
`FLAG_SECURE` to the window immediately — before any `setContent` call — so notification
content is protected from screen capture from day one.

**Theme:** Dark mode default per the README design language. Set `dynamicColor = false`
(monochrome accent, user-configurable later). Use `FontFamily.Default` everywhere — no custom
typefaces. No `Typography` overrides except scale adjustments for large tap targets (minimum
48dp touch targets enforced via `Modifier.defaultMinSize`).

**Navigation:** Single `NavHost` with placeholder routes for all 5 screens: `setup`,
`briefing`, `queue`, `rules`, `settings`. Each route points to a
`Text("TODO: [screen name]")` composable for now. This scaffold means all subsequent phases
drop in screens without touching navigation wiring.

### Phase 0 — Done Criteria

- [ ] `./gradlew :app:assembleDebug` succeeds with no errors or unresolved dependency warnings
- [ ] App installs and launches on a physical device (API 29+) without crashing
- [ ] Hilt injection compiles — no missing binding errors
- [ ] Room database schema exports to `app/schemas/ai.talkingrock.lithium.data.db.LithiumDatabase/1.json`
- [ ] SQLCipher database creates and opens without crash; second open after process restart succeeds
- [ ] `FLAG_SECURE` set — confirmed by attempting a screenshot from within the app (should be blocked)
- [ ] `android:allowBackup="false"` confirmed in merged manifest:
      `./gradlew :app:processDebugManifest` then inspect `app/build/intermediates/merged_manifest/`
- [ ] No `INTERNET` permission in merged manifest for `:app` module (check with `aapt dump permissions`)
- [ ] WorkManager auto-init disabled — app does not crash on launch with the custom `Configuration.Provider`

---

## Phase M1: Observe

**Goal:** `NotificationListenerService` logs every incoming notification to the encrypted
database. A read-only notification log screen shows what has been captured. Permission setup
flow handles the two special-grant permissions.

### M1.1 — Data Models and DAOs

**Files to create:**

```
data/model/NotificationRecord.kt
data/model/SessionRecord.kt
data/model/Rule.kt
data/model/Report.kt
data/model/Suggestion.kt
data/model/QueuedNotification.kt
data/model/RuleCondition.kt        (new — not in README file list, but required)
data/db/NotificationDao.kt
data/db/SessionDao.kt
data/db/RuleDao.kt
data/db/ReportDao.kt
data/db/QueueDao.kt
data/db/SuggestionDao.kt
```

Implement the full schema from the README exactly. Use `@ColumnInfo(name = "snake_case")` on
every property so Kotlin camelCase property names do not mismatch SQL column names.

**Key decisions:**

- `condition_json` in `rules` is stored as `String` in the Room model. Parsing to/from a
  typed `RuleCondition` sealed class is done at the repository layer, not the DAO layer.
  This keeps Room simple and the DAO testable without the JSON parser.
- `ai_classification` in `notifications` stores the enum label as a plain string, not an
  integer. Store `"personal"`, `"promotional"` etc. directly. No `TypeConverter` needed.
  The database is readable without a decoder.
- All timestamps are `Long` (milliseconds since epoch, UTC). No `Date` types anywhere.

**`RuleCondition.kt`** — a `kotlinx.serialization` sealed class, used to type-safely
deserialize `condition_json`:

```kotlin
@Serializable
sealed class RuleCondition {
    @Serializable @SerialName("package_match")
    data class PackageMatch(val packageName: String) : RuleCondition()

    @Serializable @SerialName("channel_match")
    data class ChannelMatch(val packageName: String?, val channelId: String) : RuleCondition()

    @Serializable @SerialName("category_match")
    data class CategoryMatch(val category: String) : RuleCondition()

    @Serializable @SerialName("not_from_contact")
    data object NotFromContact : RuleCondition()

    @Serializable @SerialName("composite_and")
    data class CompositeAnd(val conditions: List<RuleCondition>) : RuleCondition()
}
```

**DAO query patterns to implement:**

- `NotificationDao`: `insertOrReplace`, `updateRemoval(key, removedAt, reason)`,
  `getRecent(sinceMs: Long): Flow<List<NotificationRecord>>`,
  `getUnclassified(limit: Int): List<NotificationRecord>`,
  `getByPackage(pkg: String): Flow<List<NotificationRecord>>`,
  `deleteOlderThan(thresholdMs: Long)`
- `RuleDao`: `insertRule`, `getApprovedRules(): Flow<List<Rule>>`, `updateStatus`, `getAll`
- `ReportDao`: `insertReport`, `getLatestUnreviewed(): Flow<Report?>`, `markReviewed(id)`
- `QueueDao`: `enqueue`, `getPendingQueue(): Flow<List<QueuedNotification>>`,
  `markReviewed(id, action)`, `clearAll()`
- `SuggestionDao`: `insertSuggestions`, `getPendingForReport(reportId: Long): Flow<List<Suggestion>>`,
  `updateStatus(id, status, userComment)`

All DAO methods returning multiple rows should return `Flow<List<T>>` for reactive UI updates.
Methods called from `NotificationListenerService` (insert, update) must be `suspend` functions,
not `Flow` — they are called inside launched coroutines, not observed.

### M1.2 — Repositories

**Files:**

```
data/repository/NotificationRepository.kt
data/repository/RuleRepository.kt
data/repository/ReportRepository.kt
```

Repository responsibilities:

- `NotificationRepository`: wraps DAO; all methods use `withContext(Dispatchers.IO)` for
  suspend functions; exposes `Flow`s directly from DAOs for observation (Flows already run
  on Room's query executor).
- `RuleRepository`: wraps `RuleDao` and additionally maintains an **in-memory cache** of
  approved rules as a `StateFlow<List<Rule>>`. The cache is populated by collecting
  `ruleDao.getApprovedRules()`. The `RuleEngine` reads `.value` from this `StateFlow` — no
  database query on every notification. This cache is the performance linchpin of M5.
- `ReportRepository`: wraps `ReportDao` and `SuggestionDao`. Provides combined access for
  the `BriefingViewModel`.

No business logic in repositories. No AI calls. No rule evaluation. Pure data access + cache.

### M1.3 — NotificationListenerService

**Files:**

```
service/LithiumNotificationListener.kt
service/BootReceiver.kt
```

**Listener lifecycle management — one of the two trickiest parts of the project.**

**Approach A: Pure NotificationListenerService with disconnection detection (recommended)**

Android guarantees the service stays running as long as the user has granted notification
access. The system kills and restarts it on its own schedule. Inject dependencies via Hilt
using `@AndroidEntryPoint` on the service class. On restart, the in-memory rule cache in
`RuleRepository` rebuilds automatically from the database because `RuleRepository` is
`@Singleton` and collects from the `Flow`.

Mitigation for OEM battery kill: detect `onListenerDisconnected` and show a persistent
(but low-priority) notification nudging the user to add Lithium to the "do not optimize"
list. This is the standard pattern for notification listeners and alarm apps.

**Approach B: Foreground Service companion**

Run a `ForegroundService` alongside the `NotificationListenerService` to keep the process
alive, displaying a persistent notification ("Lithium is active").

Trade-off: more reliable on OEM-killed devices (Xiaomi MIUI, some Samsung configurations),
but adds a persistent notification the user did not ask for, adds code complexity, and
contradicts the "feels like it barely exists" design principle. The `NotificationListenerService`
binding itself is sufficient signal to stock Android that the process is needed.

**Recommendation: Approach A.** The disconnection detection + user nudge handles the OEM kill
case at the cost of one extra notification, without permanently cluttering the notification
shade. If field data shows this is insufficient on a significant device segment, add Approach B
as an opt-in in Settings.

**Critical implementation detail:** `onNotificationPosted` runs on the main thread of the
service process. It must return quickly. Rule evaluation (M5) is synchronous and in-memory.
Database writes are dispatched to a coroutine scope — never block the callback:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
    val record = buildRecord(sbn)           // synchronous, fast
    serviceScope.launch { repo.insert(record) }   // async, non-blocking

    when (ruleEngine.evaluate(record)) {    // synchronous, in-memory (M5)
        RuleAction.SUPPRESS -> cancelNotification(sbn.key)
        RuleAction.QUEUE -> {
            cancelNotification(sbn.key)
            serviceScope.launch { repo.enqueue(record) }
        }
        RuleAction.ALLOW -> Unit
    }
}
```

`serviceScope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created in
`onCreate()` and cancelled in `onDestroy()`.

**`onListenerConnected` / `onListenerDisconnected`:** Update a `ListenerState` singleton
(provided by Hilt as `@Singleton`) that exposes `isConnected: StateFlow<Boolean>`. The
Settings screen observes this to show accurate permission status without polling.

**`BootReceiver`** — registers for `ACTION_BOOT_COMPLETED`. For M1, it is a no-op
placeholder. In M3 it re-enqueues the WorkManager AI job (WorkManager jobs do not survive
reboot on all devices despite the claim otherwise).

### M1.4 — Setup Screen

**File:** `ui/setup/SetupScreen.kt`

The first screen new users see. Per the README design language: not an onboarding wizard,
not a multi-step flow. A single screen with three permission buttons.

1. **Notification Access** — deep-links to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
   Status shown live via `ListenerState.isConnected`.
2. **Usage Access** — deep-links to `Settings.ACTION_USAGE_ACCESS_SETTINGS` with the app
   package URI so the user lands directly on the Lithium toggle (not the full app list).
   Status checked via `AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`.
3. **Contacts (optional)** — standard `ActivityResultContracts.RequestPermission` for
   `READ_CONTACTS`. Labeled "Recommended" in the UI, not required. Show a single-sentence
   rationale before launching the system dialog.

**Navigation logic:** If notification access is already granted when the app launches,
navigate directly to `briefing`. The `SetupScreen` only appears when `ListenerState.isConnected`
is false.

### M1.5 — Debug Notification Log (Debug Builds Only)

A temporary `LazyColumn` showing the 50 most recent `NotificationRecord` rows — package name,
title, timestamp. No actions. Purpose: prove the listener is recording during development.

Gate this behind `BuildConfig.DEBUG`. It must not be reachable in release builds.
Add it as a route in the nav graph only when `BuildConfig.DEBUG == true`.

### M1 — Done Criteria

- [ ] `NotificationListenerService` records every notification to the database on a physical device
- [ ] `onNotificationRemoved` records `removed_at` and `removal_reason` correctly
- [ ] Debug log screen shows real-time notification stream on device
- [ ] Setup screen correctly identifies missing permissions and links to correct settings panels
- [ ] After device reboot, listener reconnects automatically (verified via debug log)
- [ ] Database file exists at `databases/lithium.db` in app-private storage
- [ ] SQLCipher verification: open the `.db` file with DB Browser for SQLite — must require a
      passphrase (will fail to open without it)
- [ ] No notification content (`title`, `text`) appears in Logcat for release variant

---

## Phase M2: Correlate

**Goal:** `UsageTracker` correlates notification taps with app sessions. `ContactsResolver`
flags notifications from known contacts. Both signal dimensions are stored in the database.

### M2.1 — ContactsResolver

**File:** `engine/ContactsResolver.kt`

Extracts sender identity from `StatusBarNotification`. Extraction order:

1. **MessagingStyle people:** `notification.extras.getParcelableArrayList(EXTRA_PEOPLE_LIST)`
   — extract `Person.getUri()` or `Person.getName()`.
2. **Email sender:** Heuristic on `notification.extras.getString(EXTRA_TEXT)` for
   email-shaped strings.
3. **Package-specific extras:** For well-known messaging apps, map known bundle keys.

Lookup strategy:
- For URI-bearing people: `ContentResolver.query(contactUri)`.
- For email strings: query `ContactsContract.CommonDataKinds.Email.CONTENT_URI`.
- For phone strings: query `ContactsContract.PhoneLookup.CONTENT_FILTER_URI`.

Returns `Boolean`. Cache lookups in a `LruCache<String, Boolean>` (key = sender identifier,
capacity = 500) to avoid repeated `ContentResolver` queries on the notification callback
thread. Invalidate the cache on `Intent.ACTION_CONTACTS_CHANGED` broadcast.

When `READ_CONTACTS` permission is absent: return `false` immediately. No crash. The Setup
screen handles the permission request.

Read contacts data is never stored. Only the boolean result is persisted as `is_from_contact`.

### M2.2 — UsageTracker

**File:** `engine/UsageTracker.kt`

Implement `measureSessionAfterTap` exactly as shown in the README. Returns `SessionRecord?`.

**Integration:** When `onNotificationRemoved` fires with `reason == REASON_CLICK`, launch a
coroutine with a 5-second delay, then call `usageTracker.measureSessionAfterTap(packageName, removedAt)`.
The delay allows the app to come to foreground before the usage event query runs.

```kotlin
// In NotificationListenerService.onNotificationRemoved, inside serviceScope.launch:
if (reason == REASON_CLICK) {
    delay(5_000)
    val session = usageTracker.measureSessionAfterTap(record.packageName, record.removedAt!!)
    if (session != null) sessionRepo.insert(session)
}
```

When `PACKAGE_USAGE_STATS` permission is absent: return `null` silently. No crash.

**`SessionRepository`** (new file): wraps `SessionDao`. Simple coroutine-dispatched insert.

### M2.3 — Wire ContactsResolver into Listener

Update `LithiumNotificationListener.buildRecord()` to call
`contactsResolver.isSenderInContacts(sbn)` and set `isFromContact` on the `NotificationRecord`
before inserting it.

The contacts resolver call is synchronous and fast (LRU cache hit on repeat senders). Keep
it on the callback thread — no coroutine needed for the lookup.

### M2 — Done Criteria

- [ ] `sessions` table populates after tapping a notification that leads to an app session
- [ ] `duration_ms` in session records is accurate within ±5 seconds of manual timing
- [ ] `is_from_contact` is `1` for a notification from an app where the sender is in contacts
      (verified with a real SMS from a saved contact)
- [ ] `is_from_contact` is `0` (not a crash) when `READ_CONTACTS` permission is denied
- [ ] Session records are not created (not a crash) when `PACKAGE_USAGE_STATS` is denied
- [ ] Contacts lookup cache confirms via Logcat that repeat senders hit the cache, not ContentResolver

---

## Phase M3: Classify

**Goal:** ONNX Runtime integrated. `AiAnalysisWorker` runs on WorkManager schedule
(charging + idle + battery not low), classifies the past 24h of unclassified notifications,
and writes classifications back to the `notifications` table.

### M3.1 — ONNX Runtime Integration

**Dependency** (already in version catalog): `onnxruntime-android`

**Model delivery — the second of the two trickiest parts of the project.**

**Approach A: Download on first AI use, via DownloadManager (recommended)**

The model is not bundled in the APK. When the user first navigates to the Briefing screen
and no model exists, present a download prompt. Use Android's `DownloadManager` system service
to perform the download — no OkHttp, no Retrofit, consistent with the no-network-library
constraint. Download to `context.getExternalFilesDir(null)` and move to
`context.filesDir/models/active_model.onnx` on completion. Verify SHA-256 before first use.

Why `DownloadManager`: handles interruptions, resumes downloads, integrates with the system
download notification UI, and requires zero additional dependencies.

**Approach B: Bundle a micro-model in assets, offer full model as an upgrade**

Bundle a DistilBERT or MobileBERT model fine-tuned for 6-class text classification (~25MB)
in `assets/`. Use immediately. Offer Qwen3-0.6B as a "deeper analysis" download.

Trade-off: requires sourcing or training a suitable small classification model. Accuracy for
nuanced cases (distinguishing `personal` from `social_signal`) will be lower than a reasoning
model. Adds two-model complexity. APK is 25MB larger. Does not address the report generation
task (M4), which needs a generative model regardless.

**Recommendation: Approach A.** The 400MB download is a one-time cost with explicit user
consent. The app is fully functional for M1/M2 without it — the user has working notification
logging and correlation before the model is needed. Approach B trades quality for a smoother
first-run at the cost of ongoing accuracy compromises.

**`AiModule.kt`** (replace stub from Phase 0):

```kotlin
// Conceptual structure — not pseudocode
// Provides OrtEnvironment as @Singleton (expensive to create, create once)
// Provides OrtSession? as @Singleton (null if model file not yet present)
// OrtSession is lazy — only created when model file exists at filesDir/models/active_model.onnx
```

A `null` `OrtSession` means the model has not been downloaded. All AI callers handle `null`
gracefully — log and skip, no crash.

### M3.2 — Tokenizer

ONNX Runtime provides inference only — it does not tokenize. The model requires tokenized
input before inference can run.

**Options evaluated:**

1. Use an ONNX export that includes the tokenizer as ONNX ops. Some export pipelines
   (via `optimum` with `--task text-classification`) bake the tokenizer into the ONNX graph
   as `SentencePiece` or `BPE` ops. If the Qwen3 export supports this, no separate
   tokenizer is needed.

2. Implement minimal BPE in `ai/Tokenizer.kt` using the model's `tokenizer.json` vocab file
   stored in `assets/` (~2MB). This is the fallback if the ONNX export does not include
   tokenizer ops. A minimal BPE tokenizer for inference (no training) is approximately 200 LOC.

**Recommendation:** When exporting the Qwen3 model, attempt the `optimum` export with
tokenizer ops included. If that succeeds, `Tokenizer.kt` is not needed. Document the export
command and outcome in `model/README.md`. If the export does not include tokenizer ops,
implement Approach 2.

**`NotificationClassifier.kt`:** Takes a `NotificationRecord`, constructs the input string:

```
[APP: {packageName}] [CHANNEL: {channelId}] [TITLE: {title}] [TEXT: {text}]
```

Tokenizes, runs inference, returns `ClassificationResult(label: String, confidence: Float)`.
The 6 classification labels are: `personal`, `engagement_bait`, `promotional`,
`transactional`, `system`, `social_signal`.

### M3.3 — AiAnalysisWorker (Classification Pass)

**File:** `ai/AiAnalysisWorker.kt`

Annotated `@HiltWorker`. Injected via `@AssistedInject` (required for Hilt + WorkManager).

WorkManager constraints (exactly as in README):

```kotlin
Constraints.Builder()
    .setRequiresCharging(true)
    .setRequiresBatteryNotLow(true)
    .setRequiresDeviceIdle(true)
    .build()
```

Unique periodic work with `ExistingPeriodicWorkPolicy.KEEP` — do not re-enqueue if already
scheduled. Enqueued from `LithiumApp.onCreate()` after first-time setup is complete.
Also re-enqueued from `BootReceiver` (WorkManager periodic work does not reliably survive
reboot on all devices).

**Worker logic for M3 (classification only — report/suggest added in M4):**

1. Check `OrtSession != null`. If null, log and return `Result.success()` (not failure — no
   retry needed, the next scheduled run will check again).
2. Query `notificationRepo.getUnclassified(limit = 500)`.
3. For each record, run `classifier.classify(record)`.
4. Batch-update `ai_classification` and `ai_confidence`.
5. Log row counts (not content) to Logcat in debug builds only.

Process in batches of 500 maximum. If more unclassified records exist, the next scheduled
run catches up. Do not process unbounded.

### M3.4 — Model Download Screen

**File:** `ui/setup/ModelDownloadScreen.kt` (or as a dialog in `SetupScreen`)

Shown when `OrtSession` is null and the user reaches the Briefing screen for the first time.
Not shown at first launch — the app works without the model for M1/M2 use.

Flow:
1. Explain what the model does and why it is needed (~2 sentences, plain language).
2. Show model size options (400MB recommended / 300MB lighter).
3. Check `NetworkCapabilities` — warn if not on Wi-Fi before starting.
4. "Download" button triggers `DownloadManager`.
5. Progress indicator (poll `DownloadManager` query via `ContentResolver` on a coroutine).
6. On completion: verify SHA-256 against hardcoded constants in `AiModule.kt`. On success:
   move to `filesDir/models/active_model.onnx`. On failure: show error with retry.

### M3 — Done Criteria

- [ ] `AiAnalysisWorker` can be triggered manually for testing:
      `adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS_UPDATE`
      (or via the WorkManager test helper in instrumented tests)
- [ ] After a worker run, `ai_classification` and `ai_confidence` populate for recent notifications
- [ ] All 6 classification labels appear across a realistic test set
- [ ] Worker handles `OrtSession == null` — logs, exits `Result.success()`, no crash
- [ ] Model download: completes, SHA-256 verified, model opens in ONNX Runtime
- [ ] Worker respects constraints — confirmed by triggering while screen is on and unplugged
      (should not run)
- [ ] Memory: heap monitored during inference — no OOM on a 3GB RAM device
- [ ] Inference time per notification: target < 500ms on mid-range hardware (Snapdragon 730+)

---

## Phase M4: Report

**Goal:** The AI engine generates a daily plain-language report from the past 24h of
classified notifications. The Briefing screen shows the report.

### M4.1 — PatternAnalyzer

**File:** `ai/PatternAnalyzer.kt`

Pure Kotlin. No AI model. Aggregates database records into a structured summary:

```kotlin
data class NotificationSummary(
    val periodStart: Long,
    val periodEnd: Long,
    val perApp: List<AppStats>,
    val engagementBaitCount: Int,
    val personalCount: Int,
    val tappedCount: Int,
    val ignoredCount: Int,
    val longestSession: SessionRecord?,
    val contactRatio: Float       // personalCount / totalCount
)

data class AppStats(
    val packageName: String,
    val totalNotifications: Int,
    val tapped: Int,
    val dismissed: Int,
    val ignored: Int,
    val classifications: Map<String, Int>,   // label -> count
    val avgSessionMinutes: Float?
)
```

This summary is serialized to JSON and injected into the AI prompt template. The AI never
receives raw notification content — only aggregated statistics. This serves two purposes:
(1) keeps sensitive message text out of the model context; (2) keeps prompt length manageable
for a 0.6B parameter model.

### M4.2 — Report and Suggestion Generation

**Files:**

```
ai/ReportGenerator.kt
ai/SuggestionGenerator.kt
```

**Important: two viable approaches for report generation.**

**Approach A: Full LLM generation via autoregressive decode loop**

Use `OrtSession` to run the full Qwen3-0.6B model autoregressively. Feed the prompt template
with the serialized `NotificationSummary`. Generate tokens until end-of-sequence or a max
token limit. Parse the output for the report text and the suggestions JSON block.

This requires implementing an autoregressive decode loop: manage the KV-cache tensors between
steps, run the session once per token, concatenate outputs. This is approximately 300-500 LOC
of non-trivial tensor manipulation. ONNX Runtime Android supports this but examples for
autoregressive LLM generation in Kotlin/Android are sparse. The main risk is that a 0.6B
model produces inconsistent or malformed JSON for the suggestions block, requiring defensive
parsing.

**Approach B: Template-based report, AI used only for classification (recommended for MVP)**

`PatternAnalyzer` computes the stats. `ReportGenerator` fills a fixed template with those
stats:

```
In the last 24 hours you received {total} notifications from {appCount} apps.
{engagementBaitCount} were engagement bait — {top_offender} sent the most.
{personalCount} came from people in your contacts.
{ignoredPercent}% went ignored.
```

`SuggestionGenerator` produces rules deterministically from the stats:
- If `engagementBaitCount / totalForApp > 0.7` and `tapped / totalForApp < 0.1`: suggest suppress.
- If `ignoredPercent > 0.9` for an app: suggest queue or suppress.
- If `avgSessionMinutes > 30` for a tap-from-notification: note the pattern in the report.

The suggestion text is generated from these logic branches. No LLM generation.

**Recommendation: Approach B for MVP.** The template produces reliable, accurate, readable
reports. The LLM generation adds 300-500 LOC of tensor management for an uncertain quality
improvement given a 0.6B model. The full generation loop belongs in a post-MVP iteration once
the classification quality is validated on real devices.

**`SuggestionGenerator.kt`:** Creates `Suggestion` and `Rule` (status = `proposed`) objects
from the analysis results. Persists them via `ReportRepository`.

### M4.3 — AiAnalysisWorker (Report Pass)

Extend `AiAnalysisWorker` with a second pass after classification:

1. Run `PatternAnalyzer` on the past 24h of classified records.
2. Run `ReportGenerator` to produce the report text.
3. Insert a `Report` row.
4. Run `SuggestionGenerator` to produce proposed rules.
5. Insert `Suggestion` rows linked to the report.
6. Insert `Rule` rows for each suggestion (status = `proposed`).

### M4.4 — BriefingScreen and BriefingViewModel

**Files:**

```
ui/briefing/BriefingScreen.kt
ui/briefing/BriefingViewModel.kt
ui/briefing/SuggestionCard.kt
```

`BriefingViewModel` observes `reportRepo.getLatestUnreviewed()` (a `Flow<Report?>`). When a
report exists, exposes the report text and the list of associated `Suggestion` objects. When
no report: exposes a "no report" state with the current queued notification count.

`BriefingScreen`:
- `LazyColumn` layout
- Header: report date and text (plain prose, not a table or chart)
- Each suggestion: a `SuggestionCard` with suggestion text, reasoning, and three buttons:
  **Yes** / **No** / **Comment**
- **Yes**: updates the associated `Rule` status to `approved`, marks `Suggestion.status = "approved"`
- **No**: marks `Suggestion.status = "rejected"`, marks associated `Rule.status = "rejected"`
- **Comment**: opens a bottom sheet with a `TextField`; saves input to `Suggestion.user_comment`
  and `Rule.user_comment`
- After all suggestions are actioned: marks `Report.reviewed = 1`

Design compliance per README:
- No red notification badges anywhere in the app
- Dark mode, high contrast text
- Minimum 48dp touch targets on Yes/No/Comment buttons
- No decorative animations

### M4 — Done Criteria

- [ ] After a worker run, a `Report` row exists with non-empty `report_text`
- [ ] `Suggestion` rows exist linked to the report
- [ ] Briefing screen displays report text and suggestion cards on device
- [ ] Yes/No/Comment actions update database correctly (verified via debug query)
- [ ] "No new report. Check back tomorrow." displays with queue count when no report exists
- [ ] Worker does not crash when stats produce zero suggestions — empty list is valid output

---

## Phase M5: Suggest and Act

**Goal:** Approved rules go live. `RuleEngine` evaluates every incoming notification against
approved rules in real time and suppresses or queues matching notifications.

### M5.1 — RuleEngine and RuleMatcher

**Files:**

```
engine/RuleEngine.kt
engine/RuleMatcher.kt
```

`RuleEngine` holds a reference to `RuleRepository`'s in-memory approved-rules cache
(`StateFlow<List<Rule>>`). It reads `flow.value` synchronously — no suspension.
`evaluate()` iterates the list and calls `RuleMatcher.matches(rule, notification)`.
First match wins. Returns `RuleAction` enum: `SUPPRESS`, `QUEUE`, `ALLOW`.

`RuleMatcher.matches()` deserializes `rule.condition_json` to `RuleCondition` using
`kotlinx.serialization`. This deserialization result must be **cached in `RuleEngine`** when
the approved-rules list is loaded — do not deserialize JSON on every single notification.
Cache as a `Map<Long, RuleCondition>` (rule ID to parsed condition), updated whenever the
`StateFlow` emits.

**Performance requirement:** `evaluate()` must complete in under 1ms for 50 rules on a
mid-range device. This is the hot path for every notification received. Benchmark it with a
realistic rule set before shipping M5.

`RuleEngine.evaluate()` must never throw. Wrap the entire body in `try/catch`. On any
exception: log the error (without notification content in release builds), return `RuleAction.ALLOW`
as the safe default.

### M5.2 — Wire RuleEngine into NotificationListenerService

`RuleEngine` is injected into `LithiumNotificationListener` via Hilt. In `onNotificationPosted`:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
    val record = buildRecord(sbn)
    serviceScope.launch { repo.insert(record) }

    when (ruleEngine.evaluate(record)) {
        RuleAction.SUPPRESS -> cancelNotification(sbn.key)
        RuleAction.QUEUE -> {
            cancelNotification(sbn.key)
            serviceScope.launch { repo.enqueue(record) }
        }
        RuleAction.ALLOW -> Unit
    }
}
```

`ruleEngine.evaluate()` is synchronous. `cancelNotification(sbn.key)` must be called
synchronously within the callback — if deferred to a coroutine, the notification may appear
briefly before cancellation. Do not move the evaluation or cancellation to a coroutine.

### M5.3 — Rule Cache Invalidation

`RuleRepository` collects `ruleDao.getApprovedRules()` (a `Flow` backed by Room's
observable queries). Every status change to any rule row causes the Flow to emit. When it
emits, `RuleRepository` updates the `StateFlow<List<Rule>>` and `RuleEngine` rebuilds its
`Map<Long, RuleCondition>` cache. The entire invalidation chain is reactive and requires no
manual signaling.

### M5 — Done Criteria

- [ ] Approve a suppression rule via the Briefing UI; subsequent notifications matching that
      rule do not appear in the notification shade
- [ ] Approve a queue rule; matching notifications are held in the database, visible in Queue
      screen, not shown in the shade
- [ ] Rejecting a suggestion does not suppress anything
- [ ] `ruleEngine.evaluate()` measured under 1ms for 50 rules (via benchmark test or manual
      timing in Logcat)
- [ ] `ruleEngine.evaluate()` does not crash on any input — confirmed by feeding edge cases
      (null title, null text, unknown package)

---

## Phase M6: Polish

**Goal:** Queue review screen. Rules management. Data retention and purge. Settings.
Diagnostics stub. Accessibility compliance. The app is releasable.

### M6.1 — Queue Screen

**Files:**

```
ui/queue/QueueScreen.kt
ui/queue/QueueViewModel.kt
```

`LazyColumn` of `QueuedNotification` records. Each row: app label (from `PackageManager`),
notification title, time queued. Swipe-to-dismiss removes the item from the queue
(`markReviewed(id, "dismissed")`). Tap opens the source app if the notification is still
actionable. Batch "Clear All" button. Empty state: "Nothing in your queue."

`FLAG_SECURE` is already set on the window (from Phase 0 `MainActivity`). Verify it covers
the Queue screen — notification titles are sensitive content.

### M6.2 — Rules Screen

**Files:**

```
ui/rules/RulesScreen.kt
ui/rules/RulesViewModel.kt
```

List of all approved rules, ordered by `created_at` descending. Each row: human-readable
`description`, source badge (`AI` or `Manual`), enable/disable toggle (updates `status`
between `approved` and `disabled`). Tap to expand: shows `condition_json` rendered as
readable text, delete button.

"Add rule manually" FAB: opens a bottom sheet with:
- Package selector (from `PackageManager.getInstalledApplications()` filtered to apps that
  appear in the `notifications` table)
- Channel selector (from channels recorded for that app in the `notifications` table)
- Action selector: Suppress / Queue / Allow
- Description text field (required)

Creates a `Rule` with `source = "user"`, `status = "approved"` — manually created rules
are immediately active, not proposed.

### M6.3 — Settings Screen

**Files:**

```
ui/settings/SettingsScreen.kt
ui/settings/SettingsViewModel.kt
```

Sections:

1. **Permissions** — Live status of notification access (`ListenerState`), usage access
   (`AppOpsManager`), contacts (`PermissionChecker`). Tap each to navigate to the relevant
   system settings panel.
2. **Data** — Retention period selector: 7 / 14 / 30 / 90 days (stored in
   `EncryptedSharedPreferences`). "Purge all data" button with a confirmation dialog that
   names exactly what will be deleted.
3. **Appearance** — Accent color selector (monochrome palette, ~8 options). Stored in prefs.
   Applied via `MaterialTheme.colorScheme` in `Theme.kt`.
4. **Diagnostics** — Toggle (default OFF). Shows a summary of what would be sent (from
   `DiagnosticsManager.buildPayload()`). Links to diagnostics module.
5. **About** — App version (`BuildConfig.VERSION_NAME`), open source licenses screen,
   link to talkingrock.ai.

**Data retention enforcement:** A `DataRetentionWorker` runs daily (no charging/idle
constraint — it is fast) and calls `notificationRepo.deleteOlderThan(thresholdMs)`.
Enqueue from `LithiumApp.onCreate()` with `ExistingPeriodicWorkPolicy.KEEP`.
The threshold is read from `EncryptedSharedPreferences` at worker execution time — not baked
into the work request — so a preference change takes effect at the next daily run.

**Purge all data:** An `@Transaction`-annotated `purgeAll()` method in a `PurgeRepository`
calls `deleteAll()` on each DAO. Also clears `EncryptedSharedPreferences`. Does not delete
or re-key the database file — empties it. Faster and avoids re-initialization complexity.

### M6.4 — Diagnostics Module Stub

**Files (in `:diagnostics` module):**

```
diagnostics/src/main/java/ai/talkingrock/lithium/diagnostics/DiagnosticsManager.kt
diagnostics/src/main/java/ai/talkingrock/lithium/diagnostics/PayloadBuilder.kt
diagnostics/src/main/java/ai/talkingrock/lithium/diagnostics/PayloadReviewer.kt
diagnostics/src/main/java/ai/talkingrock/lithium/diagnostics/DiagnosticsUploader.kt
diagnostics/src/main/AndroidManifest.xml
```

`diagnostics/AndroidManifest.xml` is the only place `INTERNET` permission appears in the
entire project. The main app manifest must not contain it under any circumstances.

For M6, `DiagnosticsManager` is a functional stub:
- `isEnabled: Boolean` backed by `EncryptedSharedPreferences`
- `buildPayload(): DiagnosticsPayload` constructs the schema (app version, Android version,
  model load time, worker durations, database row counts — nothing from notification content)
- `sendDiagnostics()` is a no-op that returns immediately

The Settings screen can already show the "review before sending" payload via `buildPayload()`.

Post-MVP: implement `DiagnosticsUploader` using `HttpURLConnection` — no OkHttp, no Retrofit,
consistent with the no-network-library constraint in the main app.

### M6.5 — Accessibility Pass

Before release:

- Set `contentDescription` on all interactive elements that do not have visible text labels
- Add `semantics` blocks to custom composables (e.g., `SuggestionCard` Yes/No buttons)
- Test full TalkBack navigation: complete a suggestion-approval flow without touch
- Verify WCAG AA contrast ratio (Material 3 dark theme handles this by default, but verify
  any custom colors added in `Color.kt`)
- Confirm no color-only information (rule source uses text badges, not just color)
- Verify all tap targets are at minimum 48dp (`Modifier.defaultMinSize(48.dp, 48.dp)`)

Set `android:isAccessibilityTool="true"` in the `<application>` tag after completing
this pass. Required for Google Play policy compliance for the Phase 2 `AccessibilityService`
work described in the README.

### M6.6 — ProGuard / R8 Release Configuration

`app/proguard-rules.pro` additions:

- Keep all `@Entity`, `@Dao`, `@Database` annotated classes (Room reflection)
- Keep Hilt-generated component classes
- Keep ONNX Runtime JNI entry point classes
- Keep `kotlinx.serialization` generated serializers
- Strip `android.util.Log` verbose/debug calls in release builds (or enforce with
  `BuildConfig.DEBUG` guards throughout — the latter is more explicit and auditable)

No notification `title` or `text` content may appear in any stack trace or log output in
release builds. Verify by triggering a deliberate crash in a release build and inspecting
the stack trace.

### M6 — Done Criteria

- [ ] Queue screen shows held notifications; swipe-to-dismiss works; batch clear empties the queue
- [ ] Rules screen shows all approved rules; enable/disable toggle takes effect on the next
      notification received
- [ ] Manual rule creation works end-to-end: create → immediately active → suppresses
      matching notification
- [ ] Data retention: `deleteOlderThan` removes correct rows from all affected tables
- [ ] Purge all data: all tables empty after purge; app launches normally after purge
- [ ] `./gradlew :app:assembleRelease -PexcludeDiagnostics=true` succeeds; release APK has
      no `INTERNET` permission (verified with `aapt dump permissions app-release.apk`)
- [ ] R8 release build: no notification content in stack traces (verified by deliberate crash test)
- [ ] TalkBack: full suggestion-approval flow completable without touch input

---

## Files Affected — Complete List

### Root

| File | Action | Phase |
|------|--------|-------|
| `settings.gradle.kts` | Create | P0 |
| `build.gradle.kts` | Create | P0 |
| `gradle/libs.versions.toml` | Create | P0 |
| `gradle/wrapper/gradle-wrapper.properties` | Create | P0 |
| `model/README.md` | Create | P0 |

### `:app` module

| File | Action | Phase |
|------|--------|-------|
| `app/build.gradle.kts` | Create | P0 |
| `app/src/main/AndroidManifest.xml` | Create | P0 |
| `app/src/main/res/xml/data_extraction_rules.xml` | Create | P0 |
| `app/src/main/res/xml/network_security_config.xml` | Create | P0 |
| `app/proguard-rules.pro` | Create | P0/M6 |
| `app/schemas/` | Auto-generated by Room KSP | P0 |
| `LithiumApp.kt` | Create | P0 |
| `MainActivity.kt` | Create | P0 |
| `di/AppModule.kt` | Create | P0 |
| `di/DatabaseModule.kt` | Create | P0 |
| `di/AiModule.kt` | Create stub P0, fill M3 | P0/M3 |
| `ui/theme/Theme.kt` | Create | P0 |
| `ui/theme/Color.kt` | Create | P0 |
| `ui/theme/Type.kt` | Create | P0 |
| `data/db/LithiumDatabase.kt` | Create | P0 |
| `data/model/NotificationRecord.kt` | Create | M1 |
| `data/model/SessionRecord.kt` | Create | M1 |
| `data/model/Rule.kt` | Create | M1 |
| `data/model/Report.kt` | Create | M1 |
| `data/model/Suggestion.kt` | Create | M1 |
| `data/model/QueuedNotification.kt` | Create | M1 |
| `data/model/RuleCondition.kt` | Create | M1 |
| `data/db/NotificationDao.kt` | Create | M1 |
| `data/db/SessionDao.kt` | Create | M1 |
| `data/db/RuleDao.kt` | Create | M1 |
| `data/db/ReportDao.kt` | Create | M1 |
| `data/db/QueueDao.kt` | Create | M1 |
| `data/db/SuggestionDao.kt` | Create | M1 |
| `data/repository/NotificationRepository.kt` | Create | M1 |
| `data/repository/RuleRepository.kt` | Create | M1 |
| `data/repository/ReportRepository.kt` | Create | M1 |
| `data/repository/SessionRepository.kt` | Create | M2 |
| `service/LithiumNotificationListener.kt` | Create | M1 |
| `service/BootReceiver.kt` | Create | M1 |
| `engine/ContactsResolver.kt` | Create | M2 |
| `engine/UsageTracker.kt` | Create | M2 |
| `engine/RuleEngine.kt` | Create | M5 |
| `engine/RuleMatcher.kt` | Create | M5 |
| `ai/AiAnalysisWorker.kt` | Create M3, extend M4 | M3/M4 |
| `ai/AiEngine.kt` | Create | M3 |
| `ai/NotificationClassifier.kt` | Create | M3 |
| `ai/Tokenizer.kt` | Create if needed | M3 |
| `ai/PatternAnalyzer.kt` | Create | M4 |
| `ai/ReportGenerator.kt` | Create | M4 |
| `ai/SuggestionGenerator.kt` | Create | M4 |
| `ui/setup/SetupScreen.kt` | Create | M1 |
| `ui/setup/ModelDownloadScreen.kt` | Create | M3 |
| `ui/briefing/BriefingScreen.kt` | Create | M4 |
| `ui/briefing/BriefingViewModel.kt` | Create | M4 |
| `ui/briefing/SuggestionCard.kt` | Create | M4 |
| `ui/queue/QueueScreen.kt` | Create | M6 |
| `ui/queue/QueueViewModel.kt` | Create | M6 |
| `ui/rules/RulesScreen.kt` | Create | M6 |
| `ui/rules/RulesViewModel.kt` | Create | M6 |
| `ui/settings/SettingsScreen.kt` | Create | M6 |
| `ui/settings/SettingsViewModel.kt` | Create | M6 |

### `:diagnostics` module

| File | Action | Phase |
|------|--------|-------|
| `diagnostics/build.gradle.kts` | Create | P0 |
| `diagnostics/src/main/AndroidManifest.xml` | Create | P0 |
| `diagnostics/src/main/java/.../DiagnosticsManager.kt` | Create stub | M6 |
| `diagnostics/src/main/java/.../PayloadBuilder.kt` | Create | M6 |
| `diagnostics/src/main/java/.../PayloadReviewer.kt` | Create | M6 |
| `diagnostics/src/main/java/.../DiagnosticsUploader.kt` | Create stub | M6 |

---

## Risks and Mitigations

### R1: OEM battery optimization killing the listener

**Risk:** Xiaomi MIUI, Huawei HarmonyOS, and Samsung with aggressive battery saving will kill
`NotificationListenerService`. This is deliberate OEM behavior, not a bug in the app.

**Mitigation:**
- Detect `onListenerDisconnected` and surface a persistent, low-priority notification:
  "Lithium stopped receiving notifications. Tap to restore." The tap deep-links to battery
  optimization settings.
- Add a Settings section with OEM-specific guidance. Maintain a lookup table of known OEM
  autostart/battery intent paths:

```kotlin
val oemBatteryIntents = mapOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.huawei.systemmanager"
        to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.samsung.android.lool"
        to "com.samsung.android.sm.ui.battery.BatteryActivity"
)
```

- Document this limitation in the Play Store description.

**Irreducible risk:** Some OEM configurations cannot be worked around without a foreground
service (Approach B above). Add the foreground service as an opt-in Settings toggle if field
data shows Approach A fails on a significant device segment.

### R2: Google Play policy — notification access permission

**Risk:** `BIND_NOTIFICATION_LISTENER_SERVICE` requires a declaration form and manual review
by Google. Initial rejection of notification-access apps is common.

**Mitigation:**
- Prepare the declaration form early — before the app is complete. The form asks for a video
  demonstration of the use case. Prepare this video during M1 testing.
- The README's Accessibility Classification section provides the exact policy language
  justifying the use case. Cite it verbatim in the form.
- The zero-network, encrypted-at-rest design is a strong argument. Include it.
- Plan for a rejection and appeal. The appeal process typically takes 5-15 business days.
  Do not schedule the launch date assuming first-submission approval.

### R3: SQLCipher write performance on low-end devices

**Risk:** SQLCipher adds decryption overhead. On devices with slow storage I/O (MediaTek
Helio G35, low-end Snapdragon 4xx series), database writes may be slow enough to cause
application-not-responding errors if called on the main thread.

**Mitigation:**
- All database writes from the listener are dispatched with `serviceScope.launch(Dispatchers.IO)`.
  The callback returns immediately; writes are non-blocking.
- Enable WAL mode (`.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)`) — this
  makes writes non-blocking for concurrent readers and dramatically reduces write latency.
- Benchmark `notificationRepo.insert()` on a low-end test device. Target < 10ms P95.
  If above that, batch writes (collect notifications for 100ms, insert in one transaction).
  Batching complicates the rule engine's need for the record to exist before evaluation,
  so evaluate the trade-off before implementing.

### R4: ONNX model memory on low-end devices

**Risk:** Qwen3-0.6B Q4_K_M is ~400MB on disk and requires roughly 500-600MB RAM during
inference. Devices with 2-3GB total RAM will be under pressure, especially if other apps
are in the background.

**Mitigations:**
- Check `ActivityManager.getMemoryClass()` at download time. Default to the 300MB model
  on devices reporting less than 4GB RAM.
- AI runs only when idle and charging — background memory pressure is at its lowest.
- Hold `OrtSession` as a `@Singleton`. Do not create/destroy per inference cycle.
- If `OutOfMemoryError` is thrown during inference: catch it, log the error (without content),
  return `Result.failure()`. WorkManager will retry on the next schedule.
- Post-MVP: evaluate whether ONNX Runtime's memory optimization options can reduce the
  footprint without unacceptable speed regression.

### R5: ONNX autoregressive generation complexity

**Risk:** If the project commits to full LLM generation for reports (not just classification),
`ReportGenerator` requires implementing an autoregressive decode loop with KV-cache tensor
management. This is non-trivial. A 0.6B model may produce inconsistent or malformed JSON
for the suggestions block, requiring extensive defensive parsing.

**Mitigation:** Use template-based report generation for MVP (Approach B in M4.2 above).
Classification is a single forward pass — no generation loop. The full generation loop is
a post-MVP feature with its own risk assessment and testing requirements. This decision must
be made explicitly before M4 begins, not discovered mid-implementation.

### R6: PACKAGE_USAGE_STATS discoverability

**Risk:** `PACKAGE_USAGE_STATS` is not a normal runtime permission. The user must find
Lithium in a buried settings menu and toggle it manually. On some devices this menu is not
labeled intuitively.

**Mitigation:**
- Deep-link directly to the Lithium entry: `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)`
  with `data = Uri.parse("package:$packageName")` — this takes the user to Lithium's entry
  in the list directly, skipping the full app list browse.
- Clear in-UI explanation: "To show how long notifications led to unplanned app sessions."
  One sentence. No jargon.
- The app works without this permission. Label it "Recommended" not "Required."

### R7: READ_CONTACTS and privacy perception

**Risk:** Users with strong privacy concerns may reject the contacts permission, interpreting
it as inconsistent with the zero-trust philosophy.

**Mitigation:**
- Show a rationale before the system dialog: "Lithium checks locally whether a notification
  sender is in your contacts. This check never leaves your phone. Only a yes/no result is stored."
- App functions fully without the permission (`is_from_contact` defaults to false).
- Contact data is never stored — only the boolean result is persisted.

---

## Testing Strategy

### Phase 0 Tests

- **Build verification:** `./gradlew :app:assembleDebug` and `:app:assembleRelease` pass cleanly.
- **Manifest verification:** Inspect merged manifest for `allowBackup="false"`, absence of
  `INTERNET` permission, presence of `FLAG_SECURE`-adjacent manifest flags.
- **Database smoke test:** Instrumented test opens `LithiumDatabase`, inserts one row, reads
  it back, verifies data matches.
- **SQLCipher key derivation stability test:** Instantiate database, close process, re-open.
  Verify the first inserted row is still readable. This catches IV/passphrase derivation bugs.

### M1 Tests

**Unit tests:**

- `NotificationRecordBuilderTest` — verify all fields are correctly extracted from a mock
  `StatusBarNotification` with known extras values.
- `NotificationDaoTest` — Room in-memory database (`Room.inMemoryDatabaseBuilder`) with the
  standard (non-cipher) factory for unit test speed. Test: insert, update removal, query
  recent, delete older than.
- `RuleDaoTest` — same in-memory approach. Test: insert, get approved, update status.

**Instrumented tests:**

- `LithiumDatabaseEncryptedTest` — open with the correct cipher factory. Insert a row.
  Verify readable. Then attempt to open the same file with a wrong passphrase — expect an
  exception. This proves the encryption is actually enforced.

**Manual test:**

Grant notification access. Receive 3-5 real notifications. Verify rows appear in the debug
log screen with correct package names, titles (if permission allows), and timestamps.

### M2 Tests

**Unit tests:**

- `ContactsResolverTest` — mock `ContentResolver`. Verify correct lookup path for each
  extraction method (MessagingStyle URI, email string). Verify returns false (not crash)
  when contacts permission absent.
- `UsageTrackerTest` — mock `UsageStatsManager` with a scripted event sequence (ACTIVITY_RESUMED
  at T+2s, ACTIVITY_PAUSED at T+47m). Verify `measureSessionAfterTap` returns a `SessionRecord`
  with `duration_ms == 45 * 60 * 1000`.

**Manual test:**

Tap a notification from a real app. Wait 5 seconds. Query the `sessions` table via the debug
screen or ADB shell. Verify a row exists with accurate `duration_ms`.

### M3 Tests

**Unit tests:**

- `NotificationClassifierTest` — mock `OrtSession` behind an interface. Verify input string
  construction. Verify output parsing for each of the 6 labels.
- `AiAnalysisWorkerTest` — `WorkManagerTestInitHelper` from `work-testing`. Trigger work
  synchronously. Mock the classifier to return fixed labels. Verify database updates.
  Verify worker handles null `OrtSession` gracefully.

**Instrumented tests:**

- `ModelLoadTest` — place a small (3-class) test ONNX model in `androidTest/assets/`. Verify
  `OrtSession` loads, runs inference on a fixed input tensor, and returns output of the
  correct shape. This validates the ONNX Runtime Android integration without the 400MB model.

**Manual test:**

Force-run the worker via ADB or the WorkManager test broadcast. Verify `ai_classification`
and `ai_confidence` populate for recent notifications in the debug screen.

### M4 Tests

**Unit tests:**

- `PatternAnalyzerTest` — feed a deterministic set of mock `NotificationRecord` objects.
  Verify `NotificationSummary` fields match expected values.
- `ReportGeneratorTest` (template approach): verify template output with known `NotificationSummary`
  inputs. Verify all variable substitutions complete without placeholder leakage.
- `SuggestionGeneratorTest` — verify correct suggestion types are proposed for known stat
  patterns (high engagement bait → suppress suggestion; high ignore rate → queue suggestion).

**Compose UI tests:**

- `BriefingScreenTest` — insert a `Report` and two `Suggestion` rows via repository.
  Navigate to briefing screen. Assert report text displays. Tap "Yes" on one suggestion.
  Query the database. Verify `rule.status == "approved"` and `suggestion.status == "approved"`.
  Tap "No" on the second. Verify `rule.status == "rejected"`.

### M5 Tests

**Unit tests:**

- `RuleMatcherTest` — table-driven tests for every `RuleCondition` subtype. Each subtype has
  at minimum: one record that matches, one that does not. Test `CompositeAnd` with two
  conditions, one of which fails.
- `RuleEngineTest` — mock `RuleRepository` `StateFlow`. Test first-match-wins order. Test
  default `ALLOW` when no rules match. Test `SUPPRESS` and `QUEUE` actions. Test that no
  exception propagates out of `evaluate()`.

**Performance test:**

- Benchmark `RuleEngine.evaluate()` with 50 rules loaded. Use Android's `BenchmarkRule` or
  manual `System.nanoTime()` timing in a loop of 10,000 iterations. Assert mean < 1ms.

**Integration test (manual):**

Approve a suppression rule for a specific package. Open that app and trigger a notification.
Verify the notification does not appear in the shade. Verify `removal_reason` in the database
shows `REASON_LISTENER_CANCEL`.

### M6 Tests

**Compose UI tests:**

- `QueueScreenTest` — insert queued notifications, verify they render, verify swipe-to-dismiss
  removes the item, verify "Clear All" empties the list.
- `RulesScreenTest` — insert an approved rule, verify it renders, toggle disabled, verify
  database status change, toggle back.
- `SettingsScreenTest` — change retention period, verify the new value persists after process
  restart (read from `EncryptedSharedPreferences`).

**End-to-end (manual):**

1. Full approval flow: receive notifications, trigger worker manually, approve a suggestion,
   receive a matching notification, verify suppressed.
2. Purge all data: Settings > Purge. Verify all tables empty. Verify app launches normally
   after purge. Verify no crash on navigating to Briefing (empty state handles gracefully).
3. Release build exclusion: `./gradlew :app:assembleRelease -PexcludeDiagnostics=true`.
   Install. Check Settings > App info > Permissions — `INTERNET` must not appear.

---

## Definition of Done (Overall)

- [ ] All Done Criteria for P0, M1, M2, M3, M4, M5, M6 are satisfied
- [ ] `./gradlew :app:assembleRelease -PexcludeDiagnostics=true` produces a release APK with
      no errors and no `INTERNET` permission
- [ ] `aapt dump permissions app-release.apk` shows no `android.permission.INTERNET`
- [ ] Database file requires a passphrase when opened externally (verified manually)
- [ ] `android:allowBackup="false"` and `android:fullBackupContent="false"` in merged manifest
- [ ] Room schema JSON committed to source control under `app/schemas/`
- [ ] No notification content (`title`, `text_content`, sender names) in Logcat for release
      variant (verified by triggering a crash and inspecting the trace)
- [ ] TalkBack navigation covers the complete suggestion-approval workflow without dead ends
- [ ] App is fully functional when all optional permissions are denied (contacts, usage stats)
- [ ] App is fully functional when the AI model has not been downloaded
- [ ] Data purge leaves the app in a clean, operational state
- [ ] Google Play declaration form prepared for `BIND_NOTIFICATION_LISTENER_SERVICE` and
      `PACKAGE_USAGE_STATS` (submit before the app reaches M6 — review timeline is independent
      of implementation timeline)

---

## Confidence Assessment

**High confidence:**
- Phase 0 scaffolding — standard Android + Hilt + Room + SQLCipher setup; well-documented
- M1 data models and DAOs — schema fully specified in the README
- M1 `NotificationListenerService` basics — standard Android API, well understood
- M2 `UsageTracker` and `ContactsResolver` — both shown in the README with working code outlines
- M5 `RuleEngine` — deterministic, well-bounded, performance-testable
- M6 UI screens — standard Compose patterns, well-precedented

**Medium confidence:**
- SQLCipher key derivation via Android Keystore — the pattern described above is correct and
  standard, but the exact IV handling in `derivePassphrase` must be implemented carefully.
  A subtle bug here (e.g., a non-deterministic IV) will cause the database to be permanently
  inaccessible after the first process restart. The Phase 0 stability test is essential.
- WorkManager + HiltWorkerFactory — requires disabling auto-init in the manifest (documented
  above). This step is easy to miss and produces a cryptic `IllegalStateException` at runtime.
  The Phase 0 done criteria explicitly verify it.

**Lower confidence (requires validation before committing to implementation):**
- ONNX Runtime tokenizer availability for Qwen3 — whether the published ONNX export of
  this model includes tokenizer ops determines whether `Tokenizer.kt` is needed. This is
  unknown until the model export is attempted. The M3 plan above handles both paths.
- OEM battery kill behavior — the `onListenerDisconnected` mitigation works on stock Android.
  Its effectiveness on MIUI and HarmonyOS requires testing on physical OEM hardware. Prioritize
  acquiring a Xiaomi test device before publishing to Google Play.

---

## Open Assumptions Requiring Validation Before Implementation

1. **Qwen3-0.6B ONNX export format** — Does the planned ONNX export include tokenizer ops?
   Does it export as a classification model (single forward pass returning logits) or a
   generation model (requiring decode loop)? This shapes M3 and M4 significantly. Validate
   before starting M3.

2. **Report generation strategy** — Is full LLM text generation a MVP requirement or a
   post-MVP goal? The plan above recommends template-based generation for MVP. This decision
   should be made explicitly before M4 begins. If generation is required for MVP, add the
   autoregressive decode loop to M4 scope and adjust the timeline accordingly.

3. **DownloadManager and app-private storage** — Verify that `DownloadManager` can download
   directly to `context.filesDir` or that a download-then-move pattern works correctly
   without exposing the model file to other apps during the download window. Test on API 29+.

4. **Google Play review timeline** — Submit the notification listener declaration form as
   soon as M1 is working (the video demonstration only needs to show the listener recording
   notifications). Do not wait for M6. Review can proceed in parallel with M2-M6.
