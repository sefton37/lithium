# LITHIUM

**by Talking Rock (talkingrock.ai)**

An Android app that helps people manage their relationship with their phone's notifications. Built for people with ADHD, autism spectrum conditions, and other mental health challenges where notification overload is a real problem.

Local-first. Zero-trust. No personal data leaves the device. Open source.

---

## What It Does

LITHIUM sits between you and your notifications. It watches what comes in, learns what matters to you based on what you actually do, and helps you take control.

**MVP scope:**

1. Observe all incoming notifications and collect metadata (source app, content, category, timestamp, notification channel, priority)
2. Track what you do with them (tapped, dismissed, ignored, how long until action)
3. Correlate notifications with app usage (did tapping that notification lead to 2 hours in TikTok?)
4. On-device AI reviews patterns and classifies notification types
5. AI generates triage reports and suggestions
6. You review suggestions in a chat-style interface — yes, no, or write your own comment on each item
7. Approved suggestions become active filter rules

**The goal:** Spam-like notifications go away. You never get sucked into TikTok or Instagram because someone added a story or an influencer was recommended. Either it's a message that relates to you (a DM from a contact, an email from someone in your contact list) or it's gone. Same logic for email — spam inbox notifications vs an email from someone who matters.

This is the program that helps you fight back against attention extraction and budget your time. All on device, always reviewable by you.

---

## Design Principles

**Light.** Small APK. Low memory footprint. No background battery drain outside scheduled AI work. The app should feel like it barely exists until you want it.

**Minimalist.** One-screen daily briefing. Chat interface for decisions. A settings page. That's it. No dashboards, no gamification, no engagement metrics trying to keep you in the app that's supposed to get you off your phone.

**Smart.** The tech choices serve the mission. Small quantized models, not big ones. SQLite, not a graph database. Kotlin and Compose, not a cross-platform framework. Every dependency earns its place.

**Secure.** Database encrypted at rest. No network permissions by default. Diagnostics are opt-in, reviewable before send, and contain zero personal data. The threat model assumes the user trusts nobody, including us.

---

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| Language | Kotlin | Native Android, no cross-platform overhead |
| UI | Jetpack Compose + Material 3 | Declarative, minimal, good accessibility support built in |
| Database | Room + SQLCipher | Local SQLite with AES-256 encryption at rest |
| Preferences | Jetpack Security EncryptedSharedPreferences | Settings and config encrypted |
| AI Runtime | ONNX Runtime Android | Lightweight, well-supported for small models on mobile |
| AI Model | Qwen3-0.6B (Q4_K_M quantized) | ~400MB, runs on mid-range phones, good at classification tasks |
| Background | WorkManager | Respects battery, runs AI only on charge + idle |
| DI | Hilt | Standard, minimal boilerplate |
| Build | Gradle KTS | Type-safe build config |

**No network libraries.** No Retrofit, no OkHttp in the main app. The optional diagnostics module is the only component with network access and it is a separate Gradle module that can be excluded from the build entirely.

**Target:** Android 10+ (API 29+). Covers ~90% of active devices and gives us UsageStatsManager, notification channels, and modern privacy APIs.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                       LITHIUM App                          │
│                                                          │
│  ┌────────────────┐ ┌──────────────┐ ┌───────────────┐  │
│  │  Notification   │ │    Usage     │ │   Contacts    │  │
│  │  Listener       │ │    Tracker   │ │   Resolver    │  │
│  │  Service        │ │              │ │               │  │
│  └───────┬────────┘ └──────┬───────┘ └───────┬───────┘  │
│          │                 │                  │          │
│          ▼                 ▼                  ▼          │
│  ┌──────────────────────────────────────────────────┐   │
│  │         Encrypted Local Database (Room+SQLCipher) │   │
│  │  notifications | sessions | rules | reports | queue│  │
│  └─────────────────────┬────────────────────────────┘   │
│                        │                                 │
│              ┌─────────┴──────────┐                      │
│              ▼                    ▼                       │
│  ┌──────────────────┐  ┌──────────────────┐             │
│  │  Rule Engine      │  │  AI Engine       │             │
│  │  (real-time)      │  │  (WorkManager)   │             │
│  │  applies approved │  │  runs on charge  │             │
│  │  rules to inbound │  │  classifies,     │             │
│  │  notifications    │  │  reports,        │             │
│  └──────────────────┘  │  suggests         │             │
│                        └────────┬─────────┘             │
│                                 │                        │
│                                 ▼                        │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Chat UI (Jetpack Compose)             │   │
│  │  Morning briefing → suggestion cards → yes/no/edit │   │
│  │  Active rules list → manual overrides              │   │
│  │  Queued notifications → batch review               │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │        Diagnostics Module (OPTIONAL, OPT-IN)      │   │
│  │  Separate Gradle module. No personal data.         │   │
│  │  Crash reports, ANRs, performance metrics only.    │   │
│  │  All payloads reviewable in-app before send.       │   │
│  │  Default: OFF. Must explicitly opt in.             │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘

Network access: NONE by default.
Only the diagnostics module can reach the network, only if opted in.
```

---

## Component Details

### 1. Notification Listener Service

Android's `NotificationListenerService`. Requires user to explicitly grant notification access in Settings.

**What it captures per notification:**
- Package name (which app)
- Notification channel ID and category
- Title and text content
- Priority level
- Timestamp posted and removed
- Removal reason (dismissed, tapped, app-cancelled, timeout)
- Notification extras (people, messages, conversation status)
- Group key (bundled notifications)
- Whether sender is in contacts (via Contacts Resolver)

**What it can do:**
- Cancel (suppress) individual notifications
- Snooze notifications
- Read all active notifications and their rankings

```kotlin
// Manifest
<service
    android:name=".service.LithiumNotificationListener"
    android:label="@string/notification_listener_label"
    android:exported="false"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

```kotlin
class LithiumNotificationListener : NotificationListenerService() {

    @Inject lateinit var notificationRepo: NotificationRepository
    @Inject lateinit var ruleEngine: RuleEngine
    @Inject lateinit var contactsResolver: ContactsResolver

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val record = NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            postedAt = sbn.postTime,
            title = sbn.notification.extras.getString(Notification.EXTRA_TITLE),
            text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            category = sbn.notification.category,
            channelId = sbn.notification.channelId,
            priority = sbn.notification.priority,
            isOngoing = sbn.isOngoing,
            groupKey = sbn.groupKey,
            isFromContact = contactsResolver.isSenderInContacts(sbn)
        )

        notificationRepo.insert(record)

        when (ruleEngine.evaluate(record)) {
            RuleAction.SUPPRESS -> cancelNotification(sbn.key)
            RuleAction.QUEUE -> {
                cancelNotification(sbn.key)
                notificationRepo.enqueue(record)
            }
            RuleAction.ALLOW -> { /* pass through */ }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        notificationRepo.recordRemoval(
            key = sbn.key,
            removedAt = System.currentTimeMillis(),
            reason = reason // REASON_CLICK, REASON_CANCEL, REASON_LISTENER_CANCEL, etc.
        )
    }
}
```

### 2. Usage Tracker

`UsageStatsManager` correlates notification taps with app sessions.

**Permission:** `PACKAGE_USAGE_STATS` — user grants in Settings > Security > Apps with usage access.

**Core logic:** When a notification is tapped (removal reason = REASON_CLICK), query usage events to find if the source app moved to foreground within a short window. Record session duration. This answers: "That Instagram notification led to a 47-minute session."

```kotlin
class UsageTracker(context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun measureSessionAfterTap(packageName: String, tapTime: Long): SessionRecord? {
        val events = usm.queryEvents(tapTime, tapTime + TimeUnit.HOURS.toMillis(4))
        val event = UsageEvents.Event()
        var start: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && start == null) {
                if (event.timeStamp - tapTime < 5000) start = event.timeStamp
            }
            if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED && start != null) {
                return SessionRecord(packageName, start, event.timeStamp)
            }
        }
        return null
    }
}
```

### 3. Contacts Resolver

Checks if a notification's sender is someone in your contacts.

**Permission:** `READ_CONTACTS`

Extracts sender info from notification extras (MessagingStyle, email headers) and looks it up against the device contact list. Returns a boolean: known person or not.

This is one of the core signal dimensions. A DM from a friend and a push from an algorithm are fundamentally different.

### 4. Accessibility Service (Phase 2 — not MVP)

For deeper interaction tracking (which notifications were tapped from heads-up vs notification shade, what was in foreground when the notification arrived).

**Google Play classification:** This app qualifies as a cognitive accessibility tool. Google's policy explicitly states tools for "cognitive impairments or multiple disabilities" qualify for `isAccessibilityTool="true"`. That designation exempts the app from the prohibition on autonomous AI actions through the Accessibility API.

**Not needed for MVP.** NotificationListenerService + UsageStatsManager gives enough signal to start.

### 5. Local Database

Room + SQLCipher. All data encrypted at rest with AES-256. Database key derived from Android Keystore. The database file is meaningless without the key.

**Schema:**

```sql
CREATE TABLE notifications (
    key TEXT PRIMARY KEY,
    package_name TEXT NOT NULL,
    posted_at INTEGER NOT NULL,
    removed_at INTEGER,
    removal_reason INTEGER,
    title TEXT,
    text_content TEXT,
    category TEXT,
    channel_id TEXT,
    priority INTEGER,
    is_ongoing INTEGER NOT NULL DEFAULT 0,
    group_key TEXT,
    is_from_contact INTEGER NOT NULL DEFAULT 0,
    ai_classification TEXT,
    ai_confidence REAL
);

CREATE TABLE sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notification_key TEXT REFERENCES notifications(key),
    package_name TEXT NOT NULL,
    session_start INTEGER NOT NULL,
    session_end INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL
);

CREATE TABLE rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    source TEXT NOT NULL,           -- 'ai' or 'user'
    status TEXT NOT NULL,           -- 'proposed', 'approved', 'rejected', 'disabled'
    rule_type TEXT NOT NULL,        -- 'suppress', 'queue', 'allow'
    target_package TEXT,            -- null = all
    target_channel TEXT,            -- null = all
    condition_json TEXT NOT NULL,
    description TEXT NOT NULL,      -- human-readable
    user_comment TEXT,
    approved_at INTEGER
);

CREATE TABLE reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    generated_at INTEGER NOT NULL,
    report_text TEXT NOT NULL,
    reviewed INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE suggestions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER REFERENCES reports(id),
    suggestion_text TEXT NOT NULL,
    proposed_rule_json TEXT,
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected'
    user_comment TEXT
);

CREATE TABLE queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notification_key TEXT REFERENCES notifications(key),
    queued_at INTEGER NOT NULL,
    reviewed INTEGER NOT NULL DEFAULT 0,
    review_action TEXT
);
```

**Data retention:** User-configurable. Default 30 days rolling. User can purge all data at any time from settings.

### 6. Rule Engine

Lightweight, deterministic. Runs synchronously in the notification listener callback. Must be fast — this is on the critical path of every notification.

No AI in the hot path. The rule engine only applies rules the user has already approved.

```kotlin
class RuleEngine(private val ruleRepo: RuleRepository) {

    fun evaluate(notification: NotificationRecord): RuleAction {
        val rules = ruleRepo.getApprovedRules() // cached in memory, refreshed on change

        for (rule in rules) {
            if (rule.matches(notification)) {
                return rule.action
            }
        }
        return RuleAction.ALLOW // default: let everything through
    }
}
```

First match wins. Default is ALLOW. The app never suppresses anything the user hasn't explicitly approved.

### 7. On-Device AI Engine

**Model:** Qwen3-0.6B, quantized Q4_K_M (~400MB). Runs on any phone made in the last 3-4 years.

**Runtime:** ONNX Runtime for Android.

**Schedule:** WorkManager, once per day:

```kotlin
val aiWork = PeriodicWorkRequestBuilder<AiAnalysisWorker>(24, TimeUnit.HOURS)
    .setConstraints(
        Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()
    )
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "lithium_ai_analysis",
    ExistingPeriodicWorkPolicy.KEEP,
    aiWork
)
```

Runs overnight while the phone charges. Not during active use.

**What the AI does each cycle:**

1. **Classify** — Tag each notification from the past 24h:
   - `personal` — from a known contact, direct message
   - `engagement_bait` — algorithmic (suggested content, stories, recommendations)
   - `promotional` — marketing, sales, deals
   - `transactional` — delivery, 2FA, ride status, payment confirmations
   - `system` — OS updates, battery, storage
   - `social_signal` — likes, comments, follows (from contacts vs strangers)

2. **Analyze** — Patterns:
   - Notifications per app, how many ignored vs acted on
   - Which types led to long unplanned app sessions
   - Time-of-day patterns
   - Contact vs algorithmic ratio per app

3. **Report** — Plain-language summary. Short. Written for someone who might be overwhelmed, not for a data analyst.

4. **Suggest** — Proposed rules, each one specific and actionable. Each becomes a card in the chat UI.

**AI prompt template:**

```
You are a notification analyst running locally on a phone. You help the user
manage notification overload. No data leaves the device.

Below is a summary of the user's notification data for the past 24 hours.

Produce:
1. A brief report (3-5 sentences, plain language)
2. A list of suggestions as JSON:
   {
     "text": "human-readable suggestion",
     "rule": {
       "type": "suppress|queue|allow",
       "package": "com.example.app or null",
       "channel": "channel_id or null",
       "condition": { ... }
     },
     "reasoning": "one sentence why"
   }

Be conservative. Only suggest suppressing things the user clearly doesn't engage with.
Never suppress transactional or personal messages unless data is overwhelming.
When in doubt, suggest 'queue' instead of 'suppress'.

DATA:
{notification_summary_json}
```

### 8. Chat UI

Jetpack Compose. Material 3. Minimal.

**Screens:**

1. **Briefing** (home) — Morning report. AI summary text. Suggestion cards with Yes / No / Comment buttons. If no new report: "No new report. Check back tomorrow." and the queue count.

2. **Queue** — Held notifications. Swipe to dismiss or tap to open. Batch clear option.

3. **Rules** — Active rules. Toggle on/off. Tap to edit or delete. Add manual rule.

4. **Settings** — Permissions status, data retention, purge all data, diagnostics opt-in, about/licenses.

**Design language:**
- Dark mode default (sensory sensitivity)
- High contrast text
- No animations except functional transitions
- Large tap targets
- No red notification badges anywhere in the app
- Monochrome accent, user-configurable
- System font, no custom typefaces
- No onboarding wizard — first launch is a single screen with permission buttons

### 9. Security

**Threat model:** The user trusts nobody. Not other apps, not us, not the network.

| Threat | Mitigation |
|--------|------------|
| Data at rest | SQLCipher AES-256. Key in Android Keystore. |
| Other apps reading our data | App-private storage. No exported content providers. No exported activities except launcher. |
| Network exfiltration | No INTERNET permission in main app. Diagnostics is separate module, opt-in. |
| Backup leaking data | `android:allowBackup="false"` and `android:fullBackupContent="false"` |
| Screen capture / overlay | `FLAG_SECURE` on windows with notification content |
| Debug leaks | ProGuard/R8 in release. No notification content in logs for release builds. |
| Compromised diagnostics | Payloads are schema-enforced. No personal data fields exist in the schema. Viewable in-app before send. |

**Manifest hardening:**

```xml
<application
    android:allowBackup="false"
    android:fullBackupContent="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:usesCleartextTraffic="false">
</application>
```

```xml
<!-- data_extraction_rules.xml -->
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

### 10. Diagnostics Module

Separate Gradle feature module: `:diagnostics`

**Default state:** OFF. Not active. No network calls. Nothing sent until you open the app, go to Settings, and explicitly opt in.

**Opt-in flow:** Settings > Diagnostics > toggle on. Shows full explanation of what will be sent. "Review before sending" option lets you inspect the exact JSON payload.

**What it sends:**
- App version, Android version, device model
- Crash stack traces (notification content is never in stack traces)
- ANR reports
- AI inference duration and model load time
- WorkManager job completion status and duration
- Database size (row counts only, not content)
- Rule engine evaluation time (p50/p95)

**What it never sends:**
- Notification content (titles, text, sender info)
- Package names of apps on the device
- Contact information
- Usage patterns or session data
- Anything from the notifications, sessions, queue, or suggestions tables

**Reviewable:** Every payload can be inspected in-app before transmission. What you see is what gets sent. No fields added after review.

**Destination:** `https://diagnostics.talkingrock.ai` — minimal endpoint, structured JSON, open source server.

**Build exclusion:** The diagnostics module is a dynamic feature module. Build without it and the app has zero network capability:

```bash
./gradlew :app:assembleRelease -PexcludeDiagnostics=true
```

---

## Permissions Summary

| Permission | Required | Why | How granted |
|------------|----------|-----|-------------|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Yes | Read and manage notifications | Settings > Notification access |
| `PACKAGE_USAGE_STATS` | Yes | Correlate notification taps with app sessions | Settings > Usage access |
| `READ_CONTACTS` | Recommended | Distinguish messages from contacts vs strangers | Runtime permission dialog |
| `FOREGROUND_SERVICE` | Yes | Keep notification listener alive | Auto-granted |
| `RECEIVE_BOOT_COMPLETED` | Yes | Restart listener after reboot | Auto-granted |
| `INTERNET` | No | Only in diagnostics module, only if opted in | Auto-granted but module inactive by default |

---

## Project Structure

```
lithium/
├── app/                           # Main application module
│   ├── src/main/
│   │   ├── java/ai/talkingrock/lithium/
│   │   │   ├── LithiumApp.kt
│   │   │   ├── service/
│   │   │   │   ├── LithiumNotificationListener.kt
│   │   │   │   └── BootReceiver.kt
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── LithiumDatabase.kt
│   │   │   │   │   ├── NotificationDao.kt
│   │   │   │   │   ├── RuleDao.kt
│   │   │   │   │   ├── ReportDao.kt
│   │   │   │   │   ├── QueueDao.kt
│   │   │   │   │   └── SessionDao.kt
│   │   │   │   ├── model/
│   │   │   │   │   ├── NotificationRecord.kt
│   │   │   │   │   ├── SessionRecord.kt
│   │   │   │   │   ├── Rule.kt
│   │   │   │   │   ├── Report.kt
│   │   │   │   │   ├── Suggestion.kt
│   │   │   │   │   └── QueuedNotification.kt
│   │   │   │   └── repository/
│   │   │   │       ├── NotificationRepository.kt
│   │   │   │       ├── RuleRepository.kt
│   │   │   │       └── ReportRepository.kt
│   │   │   ├── engine/
│   │   │   │   ├── RuleEngine.kt
│   │   │   │   ├── RuleMatcher.kt
│   │   │   │   ├── ContactsResolver.kt
│   │   │   │   └── UsageTracker.kt
│   │   │   ├── ai/
│   │   │   │   ├── AiAnalysisWorker.kt
│   │   │   │   ├── AiEngine.kt
│   │   │   │   ├── NotificationClassifier.kt
│   │   │   │   ├── PatternAnalyzer.kt
│   │   │   │   ├── ReportGenerator.kt
│   │   │   │   └── SuggestionGenerator.kt
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   │   ├── Theme.kt
│   │   │   │   │   ├── Color.kt
│   │   │   │   │   └── Type.kt
│   │   │   │   ├── briefing/
│   │   │   │   │   ├── BriefingScreen.kt
│   │   │   │   │   ├── BriefingViewModel.kt
│   │   │   │   │   └── SuggestionCard.kt
│   │   │   │   ├── queue/
│   │   │   │   │   ├── QueueScreen.kt
│   │   │   │   │   └── QueueViewModel.kt
│   │   │   │   ├── rules/
│   │   │   │   │   ├── RulesScreen.kt
│   │   │   │   │   └── RulesViewModel.kt
│   │   │   │   ├── settings/
│   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   └── SettingsViewModel.kt
│   │   │   │   └── setup/
│   │   │   │       └── SetupScreen.kt
│   │   │   └── di/
│   │   │       ├── AppModule.kt
│   │   │       ├── DatabaseModule.kt
│   │   │       └── AiModule.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── diagnostics/                    # Optional feature module
│   ├── src/main/
│   │   ├── java/ai/talkingrock/lithium/diagnostics/
│   │   │   ├── DiagnosticsManager.kt
│   │   │   ├── PayloadBuilder.kt
│   │   │   ├── PayloadReviewer.kt
│   │   │   └── DiagnosticsUploader.kt
│   │   └── AndroidManifest.xml     # Only module with INTERNET permission
│   └── build.gradle.kts
├── model/
│   └── README.md                   # Instructions for model download
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

---

## AI Model Setup

The model is not bundled in the APK. On first launch, the app prompts to download to device-local storage.

**Options:**
- `qwen3-0.6b-q4_k_m.onnx` — ~400MB, recommended for most phones
- `qwen2.5-0.5b-q4_k_m.onnx` — ~300MB, lighter alternative

Downloaded from a static file host (no API, no auth, no tracking). Verified via SHA-256 checksum before use. Stored in app-private internal storage, not accessible to other apps, not backed up.

---

## Build & Run

```bash
git clone https://github.com/talkingrock/lithium-android.git
cd lithium-android

# Build
./gradlew :app:assembleDebug

# Install
./gradlew :app:installDebug

# Build without diagnostics module
./gradlew :app:assembleRelease -PexcludeDiagnostics=true
```

**Requirements:**
- Android Studio Hedgehog+ or JDK 17+
- Android SDK 34+
- Physical device for testing (notification listener doesn't work reliably in emulators)

---

## Accessibility Classification

This app qualifies as a cognitive accessibility tool under Google Play policy. Google's policy explicitly states tools for "cognitive impairments or multiple disabilities" qualify for `isAccessibilityTool="true"`.

**Target users:** Adults with ADHD, autism spectrum conditions, and executive function challenges.

**Challenge addressed:** Notification overload creates sensory overwhelm and attentional disruption that disproportionately impacts neurodivergent users. The default Android notification system does not differentiate between a DM from a friend and algorithmic engagement bait.

**Phase 2:** AccessibilityService for deeper interaction tracking, submitted with video demonstration for Google Play review.

---

## MVP Milestones

**M1: Observe** — NotificationListenerService logging to encrypted DB. Basic notification log UI. Permission setup flow.

**M2: Correlate** — UsageStatsManager integration. Session tracking. Contacts resolver.

**M3: Classify** — ONNX Runtime integrated. AI classification on WorkManager schedule. Classifications in DB.

**M4: Report** — AI daily reports. Briefing screen.

**M5: Suggest & Act** — AI rule suggestions. Chat review interface. Rule engine suppresses/queues in real time.

**M6: Polish** — Queue review screen. Rules management. Data retention/purge. Settings. Diagnostics opt-in.

---

## License

Open source. License TBD (likely Apache 2.0 or GPLv3).

---

## Contact

- **Talking Rock:** talkingrock.ai
- **Author:** Kel Brengel
