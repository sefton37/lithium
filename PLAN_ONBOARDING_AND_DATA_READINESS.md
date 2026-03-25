# Plan: Onboarding Flow and Data Readiness Notification

## Context

Lithium currently presents a single-screen permission list as its entire first-run
experience (`SetupScreen.kt` / `SetupViewModel.kt`). The screen offers no explanation
of WHY permissions are needed, no privacy commitment, no learning-period framing, and
no acknowledgment that the app is useless until it has observed enough notifications to
make meaningful recommendations.

The result: a user who grants Notification Access lands on BriefingScreen's bare
"No new report." empty state with no guidance on when anything useful will happen.

This plan replaces that with:

1. A multi-page guided onboarding flow (paged intro screens + per-permission rationale
   screens + a learning-period expectation screen).
2. An `onboarding_complete` SharedPreferences flag that prevents re-showing the intro
   pages on subsequent launches while still blocking on Notification Access if revoked.
3. A data-readiness threshold check in `AiAnalysisWorker` that fires a local
   notification ("Lithium is ready") when enough data has been collected.
4. A context-aware BriefingScreen empty state that distinguishes "still learning" from
   "nothing new yet" based on the same threshold.

---

## Approach (Recommended): HorizontalPager Inside the Existing Setup Route

The onboarding UI is implemented as a `HorizontalPager` composable living entirely
within the `Screen.Setup` route. `SetupViewModel` gains an `onboardingComplete: Boolean`
flag read from SharedPreferences at VM construction; `MainActivity` reads the same flag
to decide whether to skip straight to `Screen.Briefing` as the start destination.

Key decisions:

**No new navigation routes.** The pager pages are not nav destinations. This keeps
the nav graph small and keeps back-button behavior simple: back within the pager
advances to the previous page; back on page 0 exits the app, which is correct behavior
for an onboarding flow.

**`onboarding_complete` flag drives start destination selection.** `MainActivity`
reads the flag synchronously at composition time. SharedPreferences is synchronous;
EncryptedSharedPreferences is acceptable here because it is pre-warmed by the time
`setContent` runs. The start destination is either `Screen.Setup` (first launch or
revoked access) or `Screen.Briefing` (returning user with access still active).

**Notification Access revocation re-enters setup at the permissions page**, not at
the welcome page, because `onboarding_complete=true` skips the intro pages.

**`HorizontalPager` is available without a new dependency.** The BOM `2024.09.00`
maps to Compose `1.7.x`; `HorizontalPager` lives in `androidx.compose.foundation`
which is already a transitive dependency of `compose-ui` (declared in
`build.gradle.kts`).

### Why not add new Screen routes for each onboarding page?

Adding five or more new routes would balloon `MainActivity`'s nav graph, add
`popUpTo` bookkeeping for every inter-page transition, and make the back-stack
semantics harder to reason about. A pager keeps all the onboarding within one
route/ViewModel pair and is the standard Android pattern for horizontal swipe-through
onboarding.

---

## Alternatives Considered

### Alternative A: Separate Activity for Onboarding

A dedicated `OnboardingActivity` (common in older apps) would isolate onboarding
entirely from the main nav graph. Rejected because: (1) Lithium is a single-Activity
Compose app; introducing a second activity adds an `<activity>` manifest entry, an
intent-based handoff, and state-sharing complexity; (2) `FLAG_SECURE` would need to be
duplicated; (3) it contradicts the existing pattern with no benefit at the current scale.

### Alternative B: A Separate `Screen.Onboarding` Nav Route with Child Routes

Nested navigation (a nested nav graph for onboarding) is clean but over-engineered for
five pages. Compose nested navigation requires a `NavHost` within a `NavHost`, which
adds complexity and makes the outer back stack harder to manage. The pager approach
achieves identical UX with less indirection.

---

## Implementation Steps

### Step 1 — Add SharedPreferences Keys Constant Object

Create a dedicated file for SharedPreferences key constants so they are accessible
from the ViewModel, the Worker, and the notification helper without circular imports.

**File to create:** `ui/setup/` sibling or `data/` package — the package choice is
`ai.talkingrock.lithium.data`, filename `Prefs.kt`.

Contents (key names only — no long values that could trigger false-positive pattern
matches):

```kotlin
object Prefs {
    const val ONBOARDING_COMPLETE   = "onboarding_complete"
    const val DATA_READY_NOTIFIED   = "data_ready_notified"
    const val DATA_READY_MIN_COUNT  = 50   // minimum classified notifications
    const val DATA_READY_MIN_APPS   = 3    // minimum distinct apps observed
}
```

The Worker already defines `PREF_RETENTION_DAYS` and `DEFAULT_RETENTION_DAYS` in its
own `companion object`. Those stay there; this file adds only the new keys.

---

### Step 2 — Extend SetupViewModel

**File to modify:** `ui/setup/SetupViewModel.kt`

Changes:
- Add `private val sharedPreferences: SharedPreferences` to the `@Inject` constructor
  (already provided as a singleton by `AppModule`).
- Add a computed property `val onboardingComplete: Boolean` that reads
  `Prefs.ONBOARDING_COMPLETE` synchronously on ViewModel construction.
- Add `fun markOnboardingComplete()` that writes `true` to that key.
- `SetupUiState` gains no new fields. The onboarding-complete flag is not reactive
  state — it is read once at ViewModel construction and written once at completion.

The existing `uiState` flow, `refresh()`, and permission-check helpers are unchanged.

---

### Step 3 — Extend BriefingViewModel and BriefingUiState

**File to modify:** `ui/briefing/BriefingViewModel.kt`

Changes:
- Add `private val sharedPreferences: SharedPreferences` and
  `private val notificationDao: NotificationDao` to the `@Inject` constructor.
- Add a `val dataReady: Boolean` computed from `Prefs.DATA_READY_NOTIFIED`. If the
  worker has ever fired the data-ready notification, data is ready by definition. This
  avoids a second DB query on every BriefingScreen launch.
- `BriefingUiState` gains `val dataReady: Boolean = false`.
- The `uiState` stateIn block includes `dataReady` using the flag value.

---

### Step 4 — Rewrite SetupScreen as Paged Onboarding

**File to modify:** `ui/setup/SetupScreen.kt`

This is the largest UI change. The existing composable becomes a pager host. The
existing `PermissionRow` private composable can be retained and reused inside
individual permission pages.

**Page structure (zero-indexed):**

| Index | Page | Skipped when `onboarding_complete`? |
|-------|------|--------------------------------------|
| 0 | WelcomePage | Yes — pager initialPage = 2 |
| 1 | PrivacyPromisePage | Yes |
| 2 | NotificationAccessPage | No |
| 3 | UsageAccessPage | No |
| 4 | ContactsPage | No |
| 5 | LearningPeriodPage | No |

When `onboarding_complete = true`, the pager starts at page 2. When all required
permissions are satisfied and the user taps "Start" on LearningPeriodPage,
`markOnboardingComplete()` is called and `onSetupComplete()` is invoked.

The existing `LaunchedEffect(uiState.notificationAccessGranted)` auto-navigation is
**removed**. In the pager context, automatically jumping to Briefing the moment
Notification Access is granted would skip the remaining pages. Instead, the user
explicitly taps through to the end.

**Structural sketch:**

```kotlin
@Composable
fun SetupScreen(onSetupComplete: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val startPage = if (viewModel.onboardingComplete) 2 else 0
    val pagerState = rememberPagerState(initialPage = startPage) { 6 }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> WelcomePage(
                    onNext = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> PrivacyPromisePage(
                    onNext = { scope.launch { pagerState.animateScrollToPage(2) } }
                )
                2 -> NotificationAccessPage(
                    uiState = uiState,
                    onGrant = { /* open Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS */ },
                    onNext = { scope.launch { pagerState.animateScrollToPage(3) } }
                )
                3 -> UsageAccessPage(
                    uiState = uiState,
                    onGrant = { /* open Settings.ACTION_USAGE_ACCESS_SETTINGS */ },
                    onNext = { scope.launch { pagerState.animateScrollToPage(4) } }
                )
                4 -> ContactsPage(
                    uiState = uiState,
                    onGrant = { contactsLauncher.launch(...) },
                    onNext = { scope.launch { pagerState.animateScrollToPage(5) } }
                )
                5 -> LearningPeriodPage(
                    canComplete = uiState.notificationAccessGranted,
                    onComplete = {
                        viewModel.markOnboardingComplete()
                        onSetupComplete()
                    }
                )
            }
        }

        // Dot indicator anchored to bottom of screen
        PageIndicator(
            pageCount = 6,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}
```

**Page content (contract for the implementer):**

- `WelcomePage`: App name. One-sentence description: "A private notification manager
  that learns which notifications actually matter to you." Single "Get started" button.

- `PrivacyPromisePage`: Title: "Your data stays on your device." Bulleted list: no
  internet permission, no analytics, no cloud sync, encrypted local storage, you can
  delete everything from Settings at any time. "Next" button.

- `NotificationAccessPage`: Title "Notification Access." Body: "Lithium reads the
  notifications that arrive on your device. This is how it learns which apps and senders
  matter to you — and which ones you routinely ignore." Grant button (opens system
  settings). Check icon when granted. "Next" button enabled only when granted.

- `UsageAccessPage`: Title "Usage Access." Body paragraph 1: "When you tap a
  notification and open an app, Lithium measures how long you stay in that app."
  Paragraph 2: "This is what tells Lithium whether a notification was genuinely useful
  or just pulled you away for a moment." Grant button. Check icon when granted. "Next"
  button enabled when granted (soft gate — not absolutely required, but the design
  treats it as required given its importance to the AI model).

- `ContactsPage`: Title "Contacts (optional)." Body: "Lithium can recognize
  notifications from people in your contacts and prioritize them. This is optional — you
  can grant it later in Settings." Two buttons: "Grant access" and "Skip for now". Both
  advance to the next page.

- `LearningPeriodPage`: Title "Lithium is getting started." Body paragraph 1: "It
  takes a few days of normal phone use before Lithium can make good recommendations —
  usually 3 to 7 days." Paragraph 2: "You will get a notification when your first
  briefing is ready. Until then, everything is being collected and analyzed quietly in
  the background." Single "Start" button. Disabled when `notificationAccessGranted` is
  false (because completing setup without the core permission is meaningless).

**PageIndicator:** A `Row` of small `Box` composables. Use `AccentPrimary` (0xFF7EAFC4)
for the current page dot (8dp), `OnDarkMuted` (0xFF9E9E9E) for inactive dots (6dp).
No third-party library needed.

---

### Step 5 — Update MainActivity Start Destination Logic

**File to modify:** `MainActivity.kt`

The current start destination is always `Screen.Setup.route`. After this change, the
start destination depends on two conditions:

1. Has the user completed onboarding? (`Prefs.ONBOARDING_COMPLETE`)
2. Is Notification Access currently active? (`listenerState.isConnected.value`)

If either condition is false, start on Setup. Otherwise, start on Briefing.

**Implementation:** Field-inject `SharedPreferences` and `ListenerState` into
`MainActivity` (both are Hilt singletons; `@AndroidEntryPoint` supports field
injection). Compute `val startOnSetup: Boolean` before `setContent`, pass it as a
parameter to `LithiumNavHost`.

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var listenerState: ListenerState

    override fun onCreate(...) {
        ...
        val onboardingDone = sharedPreferences.getBoolean(Prefs.ONBOARDING_COMPLETE, false)
        val listenerActive = listenerState.isConnected.value
        val startOnSetup = !onboardingDone || !listenerActive

        setContent {
            LithiumTheme {
                Surface(...) {
                    LithiumNavHost(startOnSetup = startOnSetup)
                }
            }
        }
    }
}
```

`LithiumNavHost` changes its `startDestination` parameter:

```kotlin
startDestination = if (startOnSetup) Screen.Setup.route else Screen.Briefing.route
```

---

### Step 6 — Add DAO Queries for Data-Readiness Check

**File to modify:** `data/db/NotificationDao.kt`

Two new suspend queries. These are pure reads and require no schema migration:

```kotlin
@Query("SELECT COUNT(*) FROM notifications WHERE ai_classification IS NOT NULL")
suspend fun countClassified(): Int

@Query("SELECT COUNT(DISTINCT package_name) FROM notifications WHERE ai_classification IS NOT NULL")
suspend fun countDistinctClassifiedApps(): Int
```

---

### Step 7 — Data-Readiness Check in AiAnalysisWorker

**File to modify:** `ai/AiAnalysisWorker.kt`

Insert a data-readiness check after Step 2 (classification). At that point, the latest
batch has just been classified, making it the earliest moment the threshold could newly
be crossed.

```kotlin
// After Step 2 classification loop completes:
if (!sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false)) {
    val totalClassified = notificationDao.countClassified()
    val distinctApps    = notificationDao.countDistinctClassifiedApps()
    if (totalClassified >= Prefs.DATA_READY_MIN_COUNT
            && distinctApps >= Prefs.DATA_READY_MIN_APPS) {
        DataReadinessNotifier.notify(applicationContext)
        sharedPreferences.edit()
            .putBoolean(Prefs.DATA_READY_NOTIFIED, true)
            .apply()
        Log.d(TAG, "doWork: data-readiness threshold crossed, notification sent")
    }
}
```

`sharedPreferences` is already injected into the Worker (used for `PREF_RETENTION_DAYS`
at line 189 of the current file). No new injection needed.

---

### Step 8 — Create DataReadinessNotifier

**File to create:** `service/DataReadinessNotifier.kt`

A plain `object` (not injectable — it is called from a Worker, not from the DI graph).
Pattern follows `postReconnectNudge()` in `LithiumNotificationListener`, which already
demonstrates channel-create-then-notify within this codebase.

Key implementation details:
- Channel ID: `"lithium_readiness"`, importance `IMPORTANCE_DEFAULT`.
- Channel name: "Lithium Readiness". Description: "Notifies you when Lithium is ready
  to make recommendations."
- Notification ID: `2001` (distinct from the reconnect nudge which uses `1001`).
- Content title: "Lithium is ready"
- Content text: "Your first briefing is waiting."
- `PendingIntent` opens `MainActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`
  and `FLAG_IMMUTABLE`.
- `setAutoCancel(true)` so the notification dismisses on tap.
- Priority: `PRIORITY_DEFAULT`.

---

### Step 9 — Add POST_NOTIFICATIONS to AndroidManifest

**File to modify:** `AndroidManifest.xml`

Add before `<application>`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Also request this permission at runtime. The best place is on the `LearningPeriodPage`
or as a 7th pager page. The implementer should request it using
`rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` with
`Manifest.permission.POST_NOTIFICATIONS`. Denial is graceful: `notify()` will silently
no-op on Android 13+.

---

### Step 10 — Update BriefingScreen Empty State

**File to modify:** `ui/briefing/BriefingScreen.kt`

Change the `EmptyState` composable to accept a `dataReady: Boolean` parameter and
branch on it:

```kotlin
@Composable
private fun EmptyState(dataReady: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dataReady) {
                Text("No new report.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Your next briefing will appear after the nightly analysis runs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Lithium is learning.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "It typically takes a few days of normal phone use before Lithium " +
                    "can make good recommendations. Keep using your phone normally — " +
                    "the learning happens in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

Pass `uiState.dataReady` from the `BriefingScreen` top-level composable.

---

### Step 11 — Clear DATA_READY_NOTIFIED on Data Purge

**File to modify:** `ui/settings/SettingsViewModel.kt`

In `purgeAllData()`, after the DB tables are cleared, also clear the readiness flag:

```kotlin
sharedPreferences.edit().remove(Prefs.DATA_READY_NOTIFIED).apply()
```

This ensures the Worker re-checks the threshold and re-fires the notification if data
re-accumulates after a purge.

---

## Files Affected

| File | Action |
|------|--------|
| `data/Prefs.kt` | Create |
| `ui/setup/SetupViewModel.kt` | Modify — add prefs injection, flag read/write |
| `ui/setup/SetupScreen.kt` | Rewrite — single screen becomes 6-page pager |
| `ui/briefing/BriefingViewModel.kt` | Modify — add dataReady flag |
| `ui/briefing/BriefingScreen.kt` | Modify — context-aware empty state |
| `MainActivity.kt` | Modify — dynamic start destination |
| `ai/AiAnalysisWorker.kt` | Modify — data-readiness check after Step 2 |
| `data/db/NotificationDao.kt` | Modify — two new query methods |
| `service/DataReadinessNotifier.kt` | Create |
| `AndroidManifest.xml` | Modify — POST_NOTIFICATIONS permission |
| `ui/settings/SettingsViewModel.kt` | Modify — clear flag on purge |

No new Gradle dependencies. No Room schema migrations.

---

## Risks and Mitigations

### Risk 1 — POST_NOTIFICATIONS on Android 13+

`targetSdk = 35`, `minSdk = 29`. On Android 13+ (API 33+), posting any notification
requires `POST_NOTIFICATIONS` declared in the manifest AND granted at runtime.
Without it, `NotificationManager.notify()` silently does nothing.

**Mitigation:** Step 9 above covers the manifest declaration. The runtime request
should be placed on the LearningPeriodPage or as a dedicated 7th pager page. Denial is
graceful — the notifier call is a no-op. The implementer must make a UX call: inline on
the LearningPeriodPage ("To get notified when your briefing is ready, allow notifications")
or a full dedicated page (cleaner but adds page count).

The reconnect nudge in `LithiumNotificationListener.postReconnectNudge()` is affected
by the same missing permission but was presumably working before — this suggests the
permission may already be declared somewhere, or the reconnect nudge silently fails too.
The implementer should verify this.

### Risk 2 — HorizontalPager Swipe-Past Without Granting

A user can swipe past permission pages before granting permissions. The "Next" button
can be disabled, but swipe gestures on the pager cannot be conditionally blocked per
page without custom gesture filtering.

**Mitigation:** Accept this behavior. Notification Access is the hard gate at the
LearningPeriodPage "Start" button — the user cannot complete onboarding without it.
Usage Access and Contacts being skippable via swipe is consistent with their "strongly
recommended but not absolutely required" design intent. The Settings screen already
shows their status for retroactive granting.

### Risk 3 — EncryptedSharedPreferences Read Before setContent

The `onboarding_complete` flag is read synchronously in `MainActivity.onCreate()` to
determine start destination. EncryptedSharedPreferences initializes its Keystore key
on first access. On a very slow device, this could add a few hundred milliseconds of
delay on the very first launch.

**Mitigation:** This is a known EncryptedSharedPreferences characteristic. Lithium
already reads from the same prefs instance (for retention days, diagnostics) in
SettingsViewModel, which is constructed synchronously on first navigation. The Pixel 7
target device is fast enough for this to be imperceptible. If profiling reveals a
problem, the fix is to show a brief loading state and defer the decision into a
`LaunchedEffect` — a targeted change that does not invalidate the rest of the plan.

### Risk 4 — DATA_READY_NOTIFIED Persists Across Data Purge

If the user purges all data and `DATA_READY_NOTIFIED = true` persists in SharedPreferences,
the Worker will never re-check the threshold. The BriefingScreen empty state will show
"No new report" (the post-readiness message) immediately after purge, even though there
is no data. This is confusing.

**Mitigation:** Step 11 clears the flag in `SettingsViewModel.purgeAllData()`. This is
covered in the plan but must not be forgotten in implementation.

### Risk 5 — Threshold Calibration (50 notifications / 3 apps)

These values are not based on measured data. Too high: the notification never fires
on light phone users. Too low: the first briefing is generated from too small a sample
to be meaningful.

**Mitigation:** Define the values as named constants in `Prefs.kt` (not inline magic
numbers) so they can be changed with a single-line edit. A debug build override that
sets the threshold to 1 notification / 1 app would make manual testing practical without
waiting days for real data to accumulate — this is a recommended addition to
`SimulationActivity.kt` in the debug variant, though it is out of scope for this plan.

### Risk 6 — listenerState.isConnected.value is False at App Start

`ListenerState.isConnected` is initialized to `false` and becomes `true` when the
`NotificationListenerService` calls `onListenerConnected()`. On app startup, this call
may not have happened yet when `MainActivity.onCreate()` reads the value — causing a
returning user with valid Notification Access to be routed to Setup on every cold start.

**Mitigation:** Do not read `listenerState.isConnected.value` directly at startup to
make the start-destination decision. Instead, determine start destination purely from
the `onboarding_complete` flag and check Notification Access via `NotificationManagerCompat
.getEnabledListenerPackages()` (a synchronous system call that returns the current
grant status regardless of whether the service has connected yet). This is the same
approach used in `SetupViewModel.checkUsageAccess()` — a direct system API query
rather than the reactive `ListenerState` flow. The reactive flow is appropriate for
UI updates within the Setup screen; the one-shot decision at startup needs a synchronous
check. This is a refinement to Step 5: replace the `listenerState.isConnected.value`
check in `MainActivity` with a direct `NotificationManagerCompat` query.

---

## Testing Strategy

### Unit Tests

**SetupViewModelTest:**
- `onboardingComplete` returns `false` when the preference is absent.
- `onboardingComplete` returns `true` after `markOnboardingComplete()` is called.
- `markOnboardingComplete()` writes the correct key with value `true`.
- Use MockK to mock `SharedPreferences`.

**AiAnalysisWorkerTest** (WorkManager `work-testing` artifact is already declared):
- When `countClassified() >= 50` and `countDistinctClassifiedApps() >= 3` and
  `DATA_READY_NOTIFIED` is absent: Worker sets the flag and calls the notifier.
- When `DATA_READY_NOTIFIED = true`: notifier is not called again.
- When threshold is not yet crossed: neither flag nor notifier is triggered.
- Use an in-memory Room database and a mocked `DataReadinessNotifier` (or verify the
  flag using a fake SharedPreferences).

**BriefingViewModelTest:**
- `uiState.dataReady = true` when `DATA_READY_NOTIFIED = true` in prefs.
- `uiState.dataReady = false` when the flag is absent.

**NotificationDaoTest** (in-memory Room):
- `countClassified()` returns only rows where `ai_classification IS NOT NULL`.
- `countDistinctClassifiedApps()` counts distinct `package_name` values among classified
  rows, not all rows.
- Verify with seeded data: 3 apps, 60 classified rows across them, 10 unclassified rows.

### Compose UI Tests

**SetupScreenTest:**
- When `onboarding_complete = false`: Welcome page text is visible at launch.
- When `onboarding_complete = true`: Welcome page text is not visible; Notification
  Access page text is visible.
- LearningPeriodPage "Start" button is disabled when `notificationAccessGranted = false`.
- LearningPeriodPage "Start" button is enabled when `notificationAccessGranted = true`.
- Tapping "Start" when enabled calls `markOnboardingComplete()` and `onSetupComplete`.

**BriefingScreenTest:**
- `EmptyState(dataReady = false)` shows text containing "learning".
- `EmptyState(dataReady = true)` shows text containing "No new report".

### Manual Verification Checklist

- [ ] Fresh install: Welcome page (page 0) is shown first.
- [ ] Grant Notification Access on page 2: check icon appears; "Next" button enables.
- [ ] Swipe past UsageAccess without granting: pager allows it (expected behavior).
- [ ] "Start" button on LearningPeriodPage is disabled until Notification Access is granted.
- [ ] Tapping "Start" dismisses setup, shows Briefing.
- [ ] Re-launch after completing onboarding: goes directly to Briefing (no welcome page).
- [ ] Revoke Notification Access in system settings, re-launch: setup shown starting at
  page 2, not page 0.
- [ ] Data purge in Settings clears `DATA_READY_NOTIFIED` and `BriefingScreen` shows
  "Lithium is learning" on next launch.
- [ ] After enough data accumulates in a debug build: notification fires exactly once.
- [ ] Subsequent worker runs after the notification has fired: notifier not called again.
- [ ] BriefingScreen empty state shows "learning" variant before threshold.
- [ ] BriefingScreen empty state shows "No new report" variant after threshold.

---

## Definition of Done

- [ ] `Prefs.kt` created with `ONBOARDING_COMPLETE`, `DATA_READY_NOTIFIED`,
  `DATA_READY_MIN_COUNT`, `DATA_READY_MIN_APPS` constants.
- [ ] `SetupViewModel` accepts `SharedPreferences`, exposes `onboardingComplete: Boolean`,
  implements `markOnboardingComplete()`.
- [ ] `SetupScreen` is a 6-page `HorizontalPager`. Each permission page explains WHY
  the permission is needed. Privacy Promise page contains all privacy commitments.
  Learning Period page sets realistic expectations and is the completion gate.
- [ ] Page indicator (dot row) renders correctly for all 6 pages.
- [ ] `MainActivity` uses a dynamic start destination: Setup when `!onboarding_complete`
  or Notification Access not granted (via direct system API check, not `ListenerState`
  flow); Briefing otherwise.
- [ ] `NotificationDao` has `countClassified()` and `countDistinctClassifiedApps()`.
- [ ] `AiAnalysisWorker` performs the data-readiness check after Step 2 and calls
  `DataReadinessNotifier.notify()` exactly once per app lifetime (guarded by flag).
- [ ] `DataReadinessNotifier` creates its notification channel, posts the notification
  with a `PendingIntent` that opens `MainActivity`, and is distinct from the reconnect
  nudge channel and ID.
- [ ] `AndroidManifest.xml` declares `POST_NOTIFICATIONS`.
- [ ] `POST_NOTIFICATIONS` is requested at runtime (on LearningPeriodPage or a
  dedicated page). Denial is handled gracefully.
- [ ] `SettingsViewModel.purgeAllData()` clears `DATA_READY_NOTIFIED`.
- [ ] `BriefingUiState` carries `dataReady: Boolean`.
- [ ] `BriefingScreen` empty state shows contextually appropriate message based on
  `dataReady`.
- [ ] All unit tests listed above pass.
- [ ] Manual checklist above passes on device.
- [ ] No new Gradle dependencies introduced.
- [ ] No internet permission added to the main app manifest.

---

## Confidence Assessment

**High confidence:** The pager approach, SharedPreferences flag scheme, Worker
modification pattern (prefs already injected), notification channel pattern (already
demonstrated in `LithiumNotificationListener`), and DAO query additions (no migration
needed).

**Moderate confidence:** Threshold values (50 notifications, 3 apps). These are
reasonable starting points based on the requirement framing, but they are not
calibrated against real user data from the Pixel trial. They should be treated as
adjustable constants, not commitments.

**Known unknowns:**

1. Whether `POST_NOTIFICATIONS` should be requested inline on LearningPeriodPage or as
   a dedicated 7th page. This is a UX judgment call. The inline approach is simpler;
   the dedicated page is cleaner. Recommendation: start with inline; promote to its own
   page if user research suggests the permission context is confusing.

2. Whether the reconnect nudge in `LithiumNotificationListener.postReconnectNudge()`
   already has `POST_NOTIFICATIONS` declared somewhere, or silently fails. The
   implementer should verify this before implementing Step 9.

3. Whether `listenerState.isConnected.value = false` on cold start is consistently
   observable on the Pixel 7 target, or whether the service connects before `setContent`
   runs. Risk 6 above addresses this, but the exact timing is device-dependent. The
   mitigation (direct `NotificationManagerCompat` query) is the safe approach regardless.
