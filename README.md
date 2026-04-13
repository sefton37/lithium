# LITHIUM

**by Talking Rock (talkingrock.ai)**

An Android app that helps people manage their relationship with their phone's notifications.
Built for people with ADHD, autism spectrum conditions, and other mental health challenges
where notification overload is a real problem.

Local-first. Zero-trust. No personal data leaves the device.

---

## What It Does

Lithium sits between you and your notifications. It watches what comes in, learns what
matters to you based on what you actually do, and helps you take control.

**Current capabilities:**

1. Observe all incoming notifications — source app, content, category, channel, priority,
   timestamp.
2. Tier-classify each notification at capture time (Invisible / Noise / Worth seeing /
   Interrupt) using a rule-based classifier (`TierClassifier`) with heuristics for known
   packages.
3. Track what you do with them (tapped, dismissed, how long until action; correlates taps
   with app session duration via `UsageStatsManager`).
4. On-device AI (`LlamaEngine` / llama.cpp) classifies notifications into six categories
   (personal, engagement_bait, promotional, transactional, system, social_signal) on a
   24-hour WorkManager schedule.
5. Capture implicit pairwise preference signals from notification-shade behavior (taps and
   dismissals) and feed them into the scoring model via `ScoringRefit`.
6. Explicit training mode: pairwise notification comparisons and app-vs-app battles that
   accumulate `TrainingJudgment` rows and update `AppRanking` and `ChannelRanking` Elo scores.
7. Hierarchical scoring (`Scorer`): per-(pkg, channel) importance score `s ∈ [0,1]` from
   app Elo + channel Elo shrinkage + category bias + behavioral prior.
8. Tier reassignment via user-quantile thresholds recomputed nightly (`TierMapper` +
   `ScoreQuantiles`).
9. AI generates triage reports and rule suggestions; the user reviews them in the Briefing
   tab and promotes suggestions to active rules.
10. Chat tab: interactive rule creation via on-device LLM per-field extraction
    (`RuleExtractor`). **Note: the Chat tab exists in code but is not yet wired into
    navigation. It is unreachable in the current build.** See `PLAN_CHAT_TAB_RESTRUCTURE.md`.
11. Local API server (`LithiumApiServer`, Ktor CIO on port 8400) exposes notification data
    over Tailscale for integration with Cairn. Requires the INTERNET permission.
12. Onboarding flow: six-page `HorizontalPager` (Welcome → Privacy → Notification Access →
    Usage Access → Contacts → Learning Period).

---

## Design Principles

**Light.** Small APK. Low memory footprint. No background battery drain outside scheduled
AI work. The app should feel like it barely exists until you want it.

**Minimalist.** Daily briefing. Chat interface for decisions. Training tab for preference
feedback. A settings page. No dashboards, no gamification, no engagement metrics trying
to keep you in the app that's supposed to get you off your phone.

**Smart.** Small quantized GGUF models, not big ones. SQLite, not a graph database.
Kotlin and Compose, not a cross-platform framework. Every dependency earns its place.

**Secure.** Database encrypted at rest (Room + SQLCipher AES-256). The API server is
Tailscale-only. No auth on the API server yet — see Phase 2 of the API roadmap.
Diagnostics are opt-in and contain zero personal data.

---

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| Language | Kotlin | Native Android, no cross-platform overhead |
| UI | Jetpack Compose + Material 3 | Declarative, minimal, good accessibility support |
| Database | Room + SQLCipher v12 | Local SQLite with AES-256 encryption at rest |
| Preferences | Jetpack Security EncryptedSharedPreferences | Settings and config encrypted |
| AI Runtime | llama.cpp (JNI via `LlamaCpp.kt`) | GGUF models, runs on-device, no cloud |
| AI Model | GGUF Q4_K_M (sideloaded; SmolLM-135M to Qwen2.5-1.5B evaluated) | See MODEL_EVAL.md |
| Background | WorkManager (24h, charging+idle) | AI analysis, scoring refit, tier backfill |
| API Server | Ktor CIO embedded (port 8400) | Tailscale-only; exposes DB over local network |
| DI | Hilt | Standard, minimal boilerplate |
| Build | Gradle KTS, compileSdk 35, minSdk 29, targetSdk 35 | |

**INTERNET permission is present** (added for the Phase 1 API server). The app is no
longer zero-network in the main process. Traffic is restricted to Tailscale by network
policy, not by manifest.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                       LITHIUM App                          │
│                                                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │  Notification    │  │    Usage     │  │  Contacts  │  │
│  │  Listener        │  │    Tracker   │  │  Resolver  │  │
│  │  Service         │  │              │  │            │  │
│  └────────┬─────────┘  └──────┬───────┘  └──────┬─────┘  │
│           │ capture+tier      │ session          │ contact│
│           ▼                   ▼                  ▼        │
│  ┌──────────────────────────────────────────────────┐    │
│  │    Encrypted Local Database (Room+SQLCipher v12)  │    │
│  │  notifications | sessions | rules | reports       │    │
│  │  training_judgments | app_rankings | channel_     │    │
│  │  rankings | implicit_judgments | score_quantiles  │    │
│  │  app_behavior_profiles | notification_channels*   │    │
│  └──────────────────┬──────────────────────────────┘    │
│                     │                                     │
│          ┌──────────┴─────────────┐                      │
│          ▼                        ▼                       │
│  ┌────────────────┐   ┌────────────────────────────┐     │
│  │  Rule Engine    │   │  AI Engine (WorkManager)   │     │
│  │  (real-time)    │   │  - NotificationClassifier  │     │
│  │  Heuristic tier │   │  - PatternAnalyzer         │     │
│  │  + learned score│   │  - ReportGenerator         │     │
│  │  + approved rules│  │  - SuggestionGenerator     │     │
│  └────────────────┘   │  - ScoringRefit             │     │
│                        └──────────┬─────────────────┘     │
│                                   │                        │
│                                   ▼                        │
│  ┌──────────────────────────────────────────────────┐    │
│  │            UI (Jetpack Compose)                    │    │
│  │  Briefing (home) | Training | Queue | Rules       │    │
│  │  Settings | Setup | Chat (built, not yet wired)   │    │
│  └──────────────────────────────────────────────────┘    │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │   API Server (Ktor CIO :8400, Tailscale-only)    │    │
│  │   LithiumApiServer + LithiumApiService           │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘

* notification_channels entity exists but is not registered in LithiumDatabase.kt (bug).
```

---

## Database Schema (v12)

| Table | Purpose |
|-------|---------|
| `notifications` | Every notification captured by the listener. Tier + AI classification columns. |
| `sessions` | App sessions correlated to notification taps via UsageStatsManager. |
| `rules` | Active filter rules. `source` = user or ai. `status` = pending_review / approved. |
| `reports` | AI-generated daily briefing reports. |
| `suggestions` | Rule suggestions linked to reports. |
| `queue` | Notifications held for batch review. |
| `app_behavior_profiles` | Per-(pkg, channel) behavioral stats: tap rate, dismiss rate, session data, category votes. |
| `training_judgments` | Explicit pairwise comparisons from the Training tab. Includes channel columns (v10). |
| `app_rankings` | Per-package Elo ratings from app-battle mode. |
| `app_battle_judgments` | Raw app-vs-app battle outcomes. |
| `channel_rankings` | Per-(pkg, channel) Elo ratings from channel-pair training. |
| `implicit_judgments` | Weak pairwise signals from notification-shade taps and dismissals (v12). |
| `score_quantiles` | Nightly user-quantile thresholds for tier assignment (v12). |
| `notification_channels` | Channel display-name cache. **Entity exists but not registered in DB — bug.** |

Migration history: v1 (scaffold) → v2 (contacts) → v3 (behavior profiles) → v4 (tiers) →
v5 (training) → v6 (XP) → v7 (quests) → v8 (app battles) → v9 (disposition column) →
v10 (channel rankings + channel training columns) → v11 (implicit judgments) → v12 (score quantiles).

---

## Permissions

| Permission | Purpose | How granted |
|------------|---------|-------------|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read all notifications | Settings > Notification access |
| `PACKAGE_USAGE_STATS` | Correlate taps with app sessions | Settings > Usage access |
| `READ_CONTACTS` | Distinguish contact vs. algorithmic notifications | Runtime dialog |
| `INTERNET` | Ktor API server on port 8400 (Tailscale-only) | Auto-granted |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep listener + API service alive | Auto-granted |
| `RECEIVE_BOOT_COMPLETED` | Restart listener after reboot | Auto-granted |
| `POST_NOTIFICATIONS` | Data-readiness notification + reconnect nudge | Runtime dialog (onboarding) |

---

## AI Model Setup

The model is not bundled in the APK. Sideload via ADB into the app's private storage:

```bash
# Push to staging area
adb push ~/lithium-models/smollm2-135m-instruct-q4_k_m.gguf /sdcard/Download/current.gguf

# Copy to app-private storage
adb shell "run-as ai.talkingrock.lithium.debug sh -c \
  'mkdir -p files/models && cat /sdcard/Download/current.gguf > files/models/llm_v1.gguf'"

adb shell "rm /sdcard/Download/current.gguf"
adb shell am force-stop ai.talkingrock.lithium.debug
```

`LlamaEngine` loads the alphabetically-first `.gguf` file in `filesDir/models/`.

See `MODEL_EVAL.md` for candidate models and the eval harness. The eval dataset
(`app/src/main/assets/eval/`) has not been created yet.

---

## Build and Run

```bash
git clone <repo-url>
cd Lithium

# Build debug APK
./gradlew :app:assembleDebug

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or via Gradle
./gradlew :app:installDebug
```

**Requirements:**
- Android Studio Hedgehog+ or JDK 17+
- Android SDK 35+
- Physical device for testing (notification listener does not work reliably in emulators)
- ADB access for model sideloading

---

## Project Structure

```
Lithium/
├── app/src/main/java/ai/talkingrock/lithium/
│   ├── LithiumApp.kt               # Hilt root, starts API service
│   ├── MainActivity.kt             # Single-Activity, Compose nav host
│   ├── api/                        # LithiumApiServer (Ktor), LithiumApiService
│   ├── ai/
│   │   ├── AiAnalysisWorker.kt     # 24h WorkManager job (classify → report → score refit)
│   │   ├── LlamaEngine.kt          # llama.cpp wrapper; classify() + generate()
│   │   ├── LlamaCpp.kt             # JNI bridge to native llama.cpp
│   │   ├── NotificationClassifier.kt  # 6-category classification + profile adjustment
│   │   ├── BriefingService.kt      # On-demand report generation (used by Chat tab)
│   │   ├── RuleExtractor.kt        # Per-field LLM extraction for rule creation
│   │   ├── scoring/
│   │   │   ├── Scorer.kt           # s(x) pipeline: Elo shrinkage + category bias
│   │   │   ├── ScoringRefit.kt     # Elo replay + category weight fit + quantile recompute
│   │   │   └── TierMapper.kt       # Score → tier via user quantiles
│   │   └── eval/
│   │       ├── ModelEvalHarness.kt # Eval runner (dataset not yet created)
│   │       └── EvalDataset.kt
│   ├── classification/
│   │   └── TierClassifier.kt       # Rule-based tier assignment (cold-start fallback)
│   ├── data/
│   │   ├── Prefs.kt                # SharedPreferences key constants
│   │   ├── db/                     # LithiumDatabase (v12), all DAOs
│   │   ├── model/                  # Room entities
│   │   └── repository/             # Repository layer
│   ├── di/                         # Hilt modules (App, Database, AI)
│   ├── engine/
│   │   ├── RuleEngine.kt           # Deterministic rule evaluation (hot path)
│   │   ├── ContactsResolver.kt
│   │   └── UsageTracker.kt
│   ├── service/
│   │   ├── LithiumNotificationListener.kt  # Core notification capture + implicit signals
│   │   ├── BootReceiver.kt
│   │   └── DataReadinessNotifier.kt
│   └── ui/
│       ├── briefing/               # Briefing tab (home, still active — Chat not wired yet)
│       ├── chat/                   # Chat tab (built, not wired into nav)
│       ├── training/               # Training tab (pairwise comparisons + app battles)
│       ├── queue/                  # Queued notifications review
│       ├── rules/                  # Active rules + add rule
│       ├── settings/               # Settings, data purge, diagnostics
│       ├── setup/                  # 6-page onboarding pager
│       └── eval/                   # Model eval screen (debug only)
├── diagnostics/                    # Optional Gradle module; INTERNET for dev tooling
├── app/schemas/                    # Room schema exports (v1–v12 JSON)
└── app/src/main/assets/
    └── eval/                       # DOES NOT EXIST YET — needed for MODEL_EVAL.md
```

---

## Known Issues / Active Gaps

1. **`notification_channels` table missing from DB.** `NotificationChannel.kt` entity and
   `NotificationChannelDao.kt` exist but `NotificationChannel::class` is not in
   `LithiumDatabase.kt`'s entities list. The table does not exist in the running database.
   Channel display names are not being cached. Fix: add the entity + migration.

2. **Chat tab not wired into navigation.** `ChatScreen.kt`, `ChatViewModel.kt`, `BriefingService.kt`,
   and `RuleExtractor.kt` all exist. Step 7 of `PLAN_CHAT_TAB_RESTRUCTURE.md` (add `Screen.Chat`
   to `MainActivity.kt`) has not been done.

3. **`ChannelPair` Challenge variant missing.** The Training UI only has `NotificationPair`
   and `AppBattle`. The channel-level training UI (Scope 3 of `PLAN_CHANNEL_TRAINING.md`)
   has not been built.

4. **Implicit signal `screenWasOn` hardcoded to `true`.** All `implicit_judgments` rows are
   written with `screen_was_on = 1`. `ScoringRefit`'s screen-off exclusion filter has no
   effect. `PowerManager.isInteractive` integration is missing.

5. **API server has no authentication.** Any device on the Tailscale network can read and
   write to the API. Phase 2 of the API plan adds a shared-secret token (`X-Lithium-Token`).

6. **Eval dataset not created.** `app/src/main/assets/eval/` does not exist. The model eval
   harness will fail to load test cases. See `MODEL_EVAL.md`.

7. **`ScoringRefit` gated on explicit judgments only.** Implicit signals accumulate but
   `ScoringRefit.refit()` only triggers when 10+ new explicit `TrainingJudgment` rows exist.
   A device with no Training tab usage will never run a refit regardless of implicit data.

---

## Security

| Threat | Mitigation |
|--------|------------|
| Data at rest | SQLCipher AES-256. Key in Android Keystore. |
| Other apps reading data | App-private storage. No exported content providers. |
| Network exfiltration | API server binds to 0.0.0.0:8400; rely on Tailscale ACLs. No auth yet. |
| Backup leaking data | `android:allowBackup="false"`, full backup excluded via `data_extraction_rules.xml` |
| Screen capture | `FLAG_SECURE` on all windows |
| Debug leaks | ProGuard/R8 in release. No notification content in release logs. |

---

## Accessibility Classification

This app qualifies as a cognitive accessibility tool under Google Play policy. Target
users: adults with ADHD, autism spectrum conditions, and executive function challenges.

Challenge addressed: notification overload creates sensory overwhelm and attentional
disruption that disproportionately impacts neurodivergent users.

---

## Contact

- **Talking Rock:** talkingrock.ai
- **Author:** Kel Brengel
