# Testing Strategy: Lithium Android

**Version:** April 2026  
**DB version:** 12 (was 8 when this document was written; update tests accordingly)
**Scope:** All 13 subsystems, full lifecycle from notification ingestion through training feedback

---

## 0. Orientation — What Exists Today

### Tests that already exist

| File | Layer | What it covers |
|---|---|---|
| `TierClassifierTest.kt` | JVM unit | 14 cases: self/media/ongoing/security/OTP/contact-whitelist/linkedin/marketing/default. Good but not exhaustive. |
| `NotificationClassifierTest.kt` | JVM unit | ONNX+llama stubs, heuristic path against 7 synthetic profiles. Good coverage of classifier categories. |
| `ReportGeneratorTest.kt` | JVM unit | 8 report-text cases against the same profiles. Background-vs-alert separation, JSON fields. |
| `SyntheticNotifications.kt` | Fixture | 7 profiles (heavy social, business, gamer, minimal, spam victim, media heavy, contact heavy). |
| `SimulationRunner.kt` | Debug/runtime | 9 profiles run against the real on-device DB. Not a test; a manual exploration tool. Exists only in debug build. |
| `.maestro/` | E2E | 12 flows + run-tests.sh. Covers setup, briefing, navigation, create-rule, settings, analysis, suggestion, purge, stress, cold-start, permission revoke. |

### Gaps (what this plan fills)

Note: some gaps below were already addressed after this document was written. Verified
status is noted per item.

- **Zero Room/DB tests** — PARTIALLY ADDRESSED: `NotificationDaoTest`, `TrainingJudgmentDaoTest`,
  `AppRankingDaoTest`, `MigrationTest` now exist in `androidTest/`. DAO query tests for
  `ImplicitJudgmentDao`, `ChannelRankingDao`, `ScoreQuantilesDao` are still missing.
  Migration coverage now needed through v12 (was v8 when this was written).
- **No worker tests** — ADDRESSED: `TierBackfillWorkerTest` and `AiAnalysisWorkerTest` exist
  in `androidTest/`.
- **No ViewModel tests** — PARTIALLY ADDRESSED: `BriefingViewModelTest`, `SetupViewModelTest`,
  `QueueViewModelTest`, `RulesViewModelTest` exist in `test/`. `TrainingViewModel` tests
  are still missing.
- **No RuleEngine tests** — PARTIALLY ADDRESSED: `RuleEngineIntegrationTest` exists in
  `androidTest/integration/`. The `RuleEngineTest.kt` JVM unit test described in section 2.1
  may or may not exist — verify.
- **No Elo/TrainerLevel tests** — ADDRESSED: `EloTest.kt` and `TrainerLevelTest.kt` exist.
- **No Ktor API tests** — ADDRESSED: `LithiumApiServerTest.kt` exists in `test/`.
- **No migration regression tests** — PARTIALLY ADDRESSED: `MigrationTest.kt` exists. Coverage
  must be extended through v12. Current document was written for v8.
- **Maestro flows 05/07/08 are shallow** — STATUS UNKNOWN; verify `.maestro/` flows.
- **Training → classification feedback is stubbed** — `NotFromContact` rule condition stub
  status is unverified. Check `RuleEngine.kt` current state.
- **NEW GAP: No tests for implicit signal capture** — `captureImplicit` in
  `LithiumNotificationListener` is untested. The pure row-generation function extraction
  described in the Testing Strategy section for PLAN_IMPLICIT_SIGNALS.md has not been done.
- **NEW GAP: No tests for `Scorer`, `ScoringRefit`, `TierMapper`** — the scoring pipeline
  added in PLAN_SCORING_MODEL.md has no test coverage.

---

## 1. Testing Architecture

### 1.1 Module Boundary Map

```
┌─────────────────────────────────────────────────────────┐
│  JVM Unit Tests  (src/test/)                            │
│  No Android runtime. No Room. No Hilt.                  │
│  Target: TierClassifier, Elo, TrainerLevel,             │
│          computeXpForJudgment, patternKey, SuggestionGenerator logic,│
│          ReportGenerator templates, Quest matching       │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Robolectric / In-memory Room  (src/test/ with @RunWith) │
│  Android runtime without device. No SQLCipher.          │
│  Target: DAO queries, migrations 1→8, NotificationRepository,│
│          RuleRepository, RuleEngine.evaluate(),         │
│          HealthCheckWorker, TierBackfillWorker,         │
│          AiAnalysisWorker (AI engines stubbed),         │
│          ViewModel tests with TestDispatcher             │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Instrumented tests  (src/androidTest/)                 │
│  Real device, real SQLCipher, Hilt TestComponent.       │
│  Target: Full DB migration with SQLCipher passphrase,   │
│          Compose UI tests (Training tab, Briefing),     │
│          WorkManager integration (TestInitHelper)       │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Maestro E2E  (.maestro/)                               │
│  Real device, real APK, real ADB.                       │
│  Target: Permission flows, full user journeys,          │
│          notification injection → screen state,         │
│          suggestion approval → rule suppression         │
└─────────────────────────────────────────────────────────┘
```

### 1.2 SQLCipher in Tests

**The problem:** `DatabaseModule.provideDatabase` calls `System.loadLibrary("sqlcipher")` and derives a passphrase from the Android Keystore. Neither works in JVM/Robolectric.

**Strategy for JVM/Robolectric tests:**
Use `Room.inMemoryDatabaseBuilder` with a plain `SQLiteOpenHelperFactory` (no passphrase, no native library). This is correct because Room's query logic and migration SQL are SQLite-compatible — SQLCipher is just SQLite with encryption on top. The only behavioral difference is the key derivation, which is tested once in instrumented tests.

In test code, build the DB directly:

```kotlin
// In test setUp()
val db = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    LithiumDatabase::class.java
).allowMainThreadQueries().build()
```

No `SupportOpenHelperFactory`. No Keystore. No native library load.

**For migration tests specifically:** Use `MigrationTestHelper` with the schema JSON files already exported to `app/schemas/`. These tests must run as instrumented (on device or emulator) because `MigrationTestHelper` requires a real SQLite file. They do NOT need SQLCipher — the migrations are raw SQL and the passphrase is irrelevant to whether `ALTER TABLE` succeeds.

**For the one test that does need SQLCipher** (verifying the full stack cold-start on a real device), that lives in instrumented tests and only verifies the DB opens without crashing, not migration logic.

### 1.3 Hilt in Tests

- JVM unit tests: no Hilt, pass fakes/mocks via constructor
- Robolectric ViewModel tests: use `@HiltAndroidTest` + `@UninstallModules` to swap DAOs with fakes
- Instrumented tests: `HiltAndroidRule`, replace `DatabaseModule` with a `TestDatabaseModule` that builds an in-memory (but still SQLCipher-keyed for the real passphrase path test) database

### 1.4 Test Infrastructure Already on the Classpath

From `build.gradle.kts` (verified):
- `junit` — JUnit 4
- `mockk` — MockK for Kotlin mocking
- `coroutines.test` — `runTest`, `TestCoroutineDispatcher`, `UnconfinedTestDispatcher`
- `turbine` — Flow testing (`awaitItem`, `expectNoEvents`)
- `robolectric` — Android framework on JVM
- `room.testing` — `MigrationTestHelper`, in-memory DB
- `work.testing` — `WorkManagerTestInitHelper`, `TestDriver`
- `espresso` — not yet used but present
- `mockk.android` — MockK instrumented variant
- `junit.android` (androidx.test.ext:junit)

**Missing (need to add to build.gradle.kts for full plan):**
- `compose.ui.test.junit4` — for Compose UI tests
- `hilt.android.testing` — for `@HiltAndroidTest` in instrumented tests
- `androidx.test:core-ktx` — for `ApplicationProvider` in Robolectric tests

---

## 2. File-by-File Deliverables

### 2.1 JVM Unit Tests (`app/src/test/`)

#### `classification/TierClassifierTest.kt` (EXTEND existing file)

The existing 14 tests are correct but missing:

**Cases to add:**
- `ongoing OTP stays tier 0 — security check happens BEFORE ongoing check` — currently security fires before ongoing (line 44-58 in TierClassifier.kt), but this is not tested. A notification with `isOngoing=true` AND `text.contains("verification code")` should return `3 to "security_2fa"` not `0 to "ongoing_persistent"`. This is load-bearing: the ordering is explicitly commented in the source.
- `transport category is tier 0 regardless of package` — `category = "transport"` on a messaging package
- `transport category checked before ongoing check` — a transport+ongoing notification: should be `0 to "media_transport"` not `0 to "ongoing_persistent"`
- `dialer with category=call and isFromContact=false returns tier 2` (call_unknown)
- `school package via pikmykid substring` — `pkg = "com.pikmykid.school"` → `2 to "school"`
- `school package via donges substring` — `pkg = "com.donges.something"` → `2 to "school"`
- `financial via chase prefix` — `com.chase.bank` → `2 to "financial"`
- `financial via optum substring` — `com.optum.rx` → `2 to "financial"`
- `linkedin subdomain` — `com.linkedin.sales.navigator` → `1 to "linkedin"` (prefix match)
- `amazon subdomain` — `com.amazon.mShop.android.shopping` → `1 to "amazon_shopping"`
- `null title and null text → no NPE, classifies by package` (edge case: `fullText = ""`)
- `very long text containing security keyword is still tier 3` (500+ char text)
- `github is tier 2` — `com.github.android` → `2 to "github"`
- `two-factor keyword triggers tier 3` — text containing "two-factor"
- `otp keyword (short)` — text = "Your OTP is 123456"
- `sign-in code keyword` — text = "Use your sign-in code: 887721"
- `marketing keyword case insensitive — unsubscribe` — text = "UNSUBSCRIBE here"
- `self-package with any content stays tier 0` — own package regardless of text content

**Total in extended file: ~32 cases.**

---

#### `ui/training/TrainerLevelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/training/TrainerLevelTest.kt`

Tests for `TrainerLevels.snapshot()`, `computeXpForJudgment()`, `patternKey()`, `TrainerLevels.ladder()`.

**Test cases (18):**
1. `snapshot with 0 mapped, 0 total → Novice level, progress 0`
2. `snapshot with 3 mapped, 20 total → Trainee level`
3. `snapshot with 10 mapped, 20 total → Trainer level`
4. `snapshot with 25 mapped, 50 total → Expert level`
5. `snapshot with 50 mapped, 60 total → Master level`
6. `Master floor is capped at min(50, 80% of total)` — with total=10, master floor = max(5, 8)=8
7. `ladder with small dataset caps master floor below 50` — total=5, masterFloor = max(5, 4)=5
8. `ladder levels are contiguous — ceiling[n] == floor[n+1]`
9. `progressWithinLevel interpolates correctly at midpoint` — mapped=6, floor=3, ceiling=10 → 3/7 ≈ 0.43f
10. `progressWithinLevel is 1.0 at Master (no ceiling)`
11. `computeXpForJudgment skip → 0 XP`
12. `computeXpForJudgment both patterns unseen → 10 XP`
13. `computeXpForJudgment one unseen, one seen once → 10 XP (min prior = 0)`
14. `computeXpForJudgment both seen once → 7 XP`
15. `computeXpForJudgment both seen twice → 4 XP`
16. `computeXpForJudgment both seen 3+ times → 1 XP`
17. `patternKey uses pipe separator with tierReason`
18. `patternKey with null tierReason produces "none" sentinel`

---

#### `ui/training/EloTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/training/EloTest.kt`

**Test cases (12):**
1. `updateElo equal scores, left wins → left gains, right loses symmetrically`
2. `updateElo equal scores, right wins → right gains, left loses symmetrically`
3. `updateElo equal scores, tie → no change (equal rating)`
4. `updateElo favourite wins → gains less than underdog win`
5. `updateElo underdog wins → gains more than favourite win`
6. `updateElo K=32 equal scores left wins → delta is exactly 16` (0.5 expected, 1 actual, 32*0.5=16)
7. `pickAppBattlePair with < 2 entries → null`
8. `pickAppBattlePair picks least-judged as anchor`
9. `pickAppBattlePair pairs anchor with closest Elo opponent`
10. `pickAppBattlePair respects exclude set`
11. `APP_BATTLE_XP constant is 3`
12. `updateElo scores are rounded to int` — verify no fractional drift

---

#### `engine/RuleEngineTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/engine/RuleEngineTest.kt`

RuleEngine is pure logic after DI — mock `RuleRepository` with a `StateFlow`.

**Test cases (20):**
1. `empty rule list → ALLOW for any record`
2. `PackageMatch condition matches exact package → SUPPRESS`
3. `PackageMatch condition does not match different package → ALLOW`
4. `ChannelMatch with package scope matches both package and channel → QUEUE`
5. `ChannelMatch with package scope ignores record with wrong package → ALLOW`
6. `ChannelMatch with null package matches channel regardless of package`
7. `CategoryMatch matches exact category string → SUPPRESS`
8. `CategoryMatch does not match different category → ALLOW`
9. `NotFromContact always returns ALLOW (M1 stub — isFromContact field not implemented)` — this is a regression guard for the known stub
10. `CompositeAnd matches only when ALL conditions match`
11. `CompositeAnd fails when any single condition fails`
12. `first-match-wins: SUPPRESS rule before QUEUE rule → SUPPRESS`
13. `first-match-wins: QUEUE rule before SUPPRESS rule → QUEUE`
14. `malformed condition JSON is silently skipped, next rule evaluated`
15. `malformed condition JSON does not crash the hot path`
16. `rules list change triggers cache rebuild` — change the StateFlow value, verify new rule fires
17. `case-insensitive action match: "Suppress" (capital) parses to SUPPRESS`
18. `action string "allow" returns ALLOW explicitly`
19. `action string "unknown_action" falls through to ALLOW`
20. `evaluate performance — 50 rules, sub-1ms` (micro-benchmark, soft assertion)

**Setup approach:**
```kotlin
val fakeRules = MutableStateFlow<List<Rule>>(emptyList())
val mockRepo = mockk<RuleRepository> { every { approvedRules } returns fakeRules }
val engine = RuleEngine(mockRepo)
```

---

#### `ai/SuggestionGeneratorTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ai/SuggestionGeneratorTest.kt`

SuggestionGenerator depends on `AppLabelResolver` (injected) which calls `PackageManager`. Mock it.

**Test cases (22):**
1. `app with < 5 notifications → no suggestion generated`
2. `engagement_bait, volume >= 10, tap rate < 5% → SUPPRESS suggestion`
3. `engagement_bait, volume >= 10, tap rate >= 5% → no suppress suggestion`
4. `engagement_bait, volume < 10, tap rate < 20% → QUEUE suggestion`
5. `promotional, volume >= 10, tap rate < 5% → SUPPRESS suggestion`
6. `promotional, volume >= 5, tap rate < 20% → QUEUE suggestion`
7. `social_signal from non-contacts, low tap rate → QUEUE suggestion`
8. `social_signal with any contact notification → NOT queued (noContactNotifications=false)`
9. `PERSONAL category → never suggested (safety guard)`
10. `TRANSACTIONAL category → never suggested`
11. `BACKGROUND category → never suggested`
12. `suggestions capped at MAX_SUGGESTIONS_PER_REPORT (3)`
13. `profile-aware: high lifetime tap rate blends to raise effective rate above threshold → no suppress`
14. `profile-aware: evidence < MINIMUM_PROFILE_EVIDENCE → uses recent rate only`
15. `generateFromTierReasons: linkedin reason, count >= 20, tap rate < 5% → suppress suggestion`
16. `generateFromTierReasons: amazon_shopping reason → suppress suggestion`
17. `generateFromTierReasons: play_store_update reason → suppress suggestion`
18. `generateFromTierReasons: marketing_text reason → suppress suggestion`
19. `generateFromTierReasons: unknown tier_reason → skipped (null from rationaleForTierReason)`
20. `generateFromTierReasons: package already in ML suggestions → skipped`
21. `generateFromTierReasons: tap rate >= TIER_SUGGEST_TAP_CEILING (0.05) → skipped`
22. `conditionJson for PackageMatch is valid JSON with correct type discriminator`

---

#### `ai/TierClassificationPipelineTest.kt` (NEW — integration of classifier + tier)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ai/TierClassificationPipelineTest.kt`

Tests that `TierClassifier.classify()` and `NotificationClassifier.classify()` agree for the cases where they should — specifically that the tier-path and the ML-path produce consistent signals for known patterns. These are pure-JVM (both classifiers are JVM-clean).

**Test cases (8):**
1. `ongoing notification: TierClassifier→tier 0; NotificationClassifier→"background"` — both agree it's invisible
2. `LinkedIn notification: TierClassifier→tier 1 "linkedin"; NotificationClassifier→"engagement_bait"` — consistent signal
3. `OTP: TierClassifier→tier 3 "security_2fa"; NotificationClassifier→"transactional"` — both elevate it
4. `WhatsApp from contact: TierClassifier→tier 3 "sms_known"; NotificationClassifier→"personal"` — aligned
5. `Amazon promotional: TierClassifier→tier 1 "amazon_shopping"; NotificationClassifier→"promotional"` — aligned
6. `Unknown app: TierClassifier→tier 2 "default"; NotificationClassifier→"unknown"` — default fallthrough
7. `Gmail unknown: TierClassifier→tier 2 "gmail"; NotificationClassifier→"personal" or "promotional"` — document actual behavior
8. `Transport category: TierClassifier→tier 0 "media_transport"; NotificationClassifier→"background"` — both invisible

---

### 2.2 Room Integration Tests — In-Memory (Robolectric or plain JVM with Android runtime)

These require `@RunWith(RobolectricTestRunner::class)` because `Room.inMemoryDatabaseBuilder` needs a `Context` from `ApplicationProvider`.

#### `data/db/NotificationDaoTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/data/db/NotificationDaoTest.kt`

**Test cases (28):**
1. `insertOrReplace → row readable by getById`
2. `insertOrReplace same id → replaces row`
3. `updateRemoval sets removed_at_ms and removal_reason`
4. `getRecent filters by sinceMs and orders newest-first`
5. `getUnclassified returns only rows where ai_classification IS NULL`
6. `getUnclassified respects limit`
7. `getUnclassified ordered ASC by posted_at_ms`
8. `getOngoingMisclassified returns only is_ongoing=1 AND ai_classification='unknown'`
9. `updateClassification sets ai_classification and ai_confidence`
10. `deleteOlderThan removes rows before threshold, leaves rows after`
11. `countClassified returns count of non-null ai_classification rows`
12. `countDistinctClassifiedApps counts package_name DISTINCT`
13. `getTierBreakdown groups by tier correctly`
14. `getTierBreakdownSince filters by sinceMs`
15. `getTierBackfillBatch returns only tier_reason IS NULL rows in ASC id order`
16. `getTierBackfillBatch respects limit`
17. `countTierBackfillRemaining returns correct count`
18. `updateTier sets tier and tier_reason for correct row`
19. `getAmbiguousCandidates excludes tier=0 rows`
20. `getAmbiguousCandidates excludes is_ongoing=1 rows`
21. `getAmbiguousCandidates excludes rows in excludeIds`
22. `getAmbiguousCandidates puts ai_classification=NULL rows first`
23. `getAmbiguousCandidates orders classified rows by |confidence - 0.5| ascending`
24. `getUnclassifiedCandidates only returns ai_classification IS NULL rows`
25. `getPatternStatsFlow emits correct (total, judged) per pattern`
26. `getPatternStatsFlow: judged count increments when training_judgments row inserted`
27. `getTierReasonStats: filters by tier<=maxTier, since, minCount, counts tapped from removal_reason='click'`
28. `getAllSinceWithTiers: filters by tier list and sinceMs`

---

#### `data/db/TrainingJudgmentDaoTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/data/db/TrainingJudgmentDaoTest.kt`

**Test cases (12):**
1. `insert → row persisted with all fields`
2. `countFlow reflects inserted row count`
3. `getJudgedNotificationIds returns both left and right notification IDs`
4. `getJudgedNotificationIds deduplicates IDs appearing on both sides`
5. `totalXpFlow sums xp_awarded + set_bonus_xp`
6. `totalXpFlow excludes skip rows (xp=0 for skip, but confirm exclusion logic)`
7. `xpByQuestFlow groups by quest_id correctly`
8. `xpByQuestFlow returns 0 for quest with no judgments`
9. `countSinceFlow filters by created_at_ms >= sinceMs AND choice != 'skip'`
10. `getChoiceBreakdown groups by choice, counts correctly`
11. `skip judgment: xp_awarded=0 not included in totalXpFlow` — double-check the `choice != 'skip'` filter
12. `insert with quest_id tagging → xpByQuestFlow shows it under the right quest`

---

#### `data/db/AppRankingDaoTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/data/db/AppRankingDaoTest.kt`

**Test cases (10):**
1. `upsert creates row if not exists`
2. `upsert replaces existing row`
3. `get returns row by package_name`
4. `get returns null for unknown package`
5. `getAll returns all rows ordered by elo_score DESC`
6. `getEligiblePackages: only packages with tier > 0, is_ongoing=0, non-null title or text`
7. `getEligiblePackages: excludes packages with only tier=0 notifications`
8. `getEligiblePackages: excludes packages with only ongoing notifications`
9. `count returns correct row count`
10. `default elo_score is 1200`

---

#### `data/db/MigrationTest.kt` (NEW — instrumented OR Robolectric with schema files)

**Location:** `app/src/androidTest/java/ai/talkingrock/lithium/data/db/MigrationTest.kt`  
(Must be instrumented — `MigrationTestHelper` requires a file-backed database.)

**Test cases (14):**

One `@Rule val helper = MigrationTestHelper(...)` referencing the schema JSON files at `app/schemas/`.

1. `migrate_1_to_2: notifications table gains is_from_contact with default 0`
2. `migrate_1_to_2: sessions table gains package_name and duration_ms`
3. `migrate_1_to_2: existing notification rows survive with is_from_contact=0`
4. `migrate_2_to_3: app_behavior_profiles table created with correct schema`
5. `migrate_2_to_3: unique index on (package_name, channel_id)`
6. `migrate_3_to_4: notifications gains tier INTEGER NOT NULL DEFAULT 2`
7. `migrate_3_to_4: notifications gains tier_reason TEXT (nullable)`
8. `migrate_3_to_4: existing rows get tier=2 and tier_reason=NULL`
9. `migrate_4_to_5: training_judgments table created`
10. `migrate_5_to_6: training_judgments gains xp_awarded, set_complete, set_bonus_xp`
11. `migrate_6_to_7: training_judgments gains quest_id with default 'free_play'`
12. `migrate_7_to_8: app_rankings and app_battle_judgments tables created`
13. `full_chain_1_to_8: create DB at v1 with representative data, apply all migrations, verify final schema and data survive`
14. `full_chain_4_to_8: DB at v4 (most common upgrade path from initial production) with tier=2/tier_reason=NULL rows; migrate to 8; confirm rows still present and tier_reason still NULL`

**The chain test (case 13) is the most important migration test.** It inserts:
- 1 notification row at v1
- Verifies the row is readable at v8 with default values for all added columns

---

#### `engine/RuleEngineIntegrationTest.kt` (NEW — in-memory Room)

**Location:** `app/src/test/java/ai/talkingrock/lithium/engine/RuleEngineIntegrationTest.kt`

Tests the full loop: insert Rule via RuleDao → RuleRepository's StateFlow updates → RuleEngine.evaluate() returns correct action.

**Test cases (8):**
1. `no approved rules → ALLOW`
2. `insert approved PackageMatch rule → SUPPRESS fires for matching package`
3. `insert approved PackageMatch rule → does NOT fire for different package`
4. `delete rule → cache rebuilds → ALLOW thereafter`
5. `rule with status='disabled' → not in approvedRules StateFlow → ALLOW`
6. `two rules, first matches → first-match-wins, second not evaluated`
7. `createFromSuggestion inserts approved rule → fires immediately`
8. `CompositeAnd with two conditions → both must match`

---

### 2.3 Worker Tests (Robolectric + WorkManagerTestInitHelper)

Workers require a Context. Use Robolectric for the Robolectric-compatible workers and instrumented for Hilt-injected workers.

#### `classification/TierBackfillWorkerTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/classification/TierBackfillWorkerTest.kt`

Use in-memory Room + `WorkManagerTestInitHelper`.

**Test cases (10):**
1. `empty DB → worker returns SUCCESS immediately`
2. `all rows already have tier_reason → nothing processed, SUCCESS`
3. `10 rows with tier_reason=NULL → all get correct tier and reason after worker runs`
4. `batch size boundary: 501 rows → worker processes all in multiple batches`
5. `rows added after first batch start are not re-processed (idempotent filter)`
6. `row with isOngoing=true gets tier=0 "ongoing_persistent"`
7. `row with OTP text gets tier=3 "security_2fa" even though pre-migration`
8. `row with isFromContact=true and messaging package gets "sms_known"`
9. `resumable: simulate kill after first batch — second run completes remaining rows`
10. `WORK_NAME matches WorkScheduler.scheduleTierBackfill — same name prevents duplicate enqueue`

**Setup:**
```kotlin
val notificationDao = db.notificationDao()
val worker = TestListenableWorkerBuilder<TierBackfillWorker>(context)
    .setWorkerFactory(/* Hilt worker factory or direct construction */ ...)
    .build()
val result = worker.doWork()
```

For direct construction (avoiding Hilt in unit tests):
```kotlin
// Since TierBackfillWorker's only injected dep is NotificationDao, construct directly:
val worker = TierBackfillWorker(context, workerParams, notificationDao)
```
This works because `TierBackfillWorker` takes `NotificationDao` directly — no complex dep graph.

---

#### `ai/AiAnalysisWorkerTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ai/AiAnalysisWorkerTest.kt`

AI engines (AiEngine, LlamaEngine) must be stubbed. Use MockK. Focus on the orchestration logic, not the AI inference.

**Test cases (16):**
1. `no unclassified rows → step 2 skipped, still proceeds to report generation`
2. `500 unclassified rows → classifies all MAX_BATCH_SIZE rows`
3. `classifier throws for one record → logs failure, continues, returns SUCCESS`
4. `patternAnalyzer failure → returns FAILURE`
5. `reportRepository.insertReport failure → returns FAILURE`
6. `insertSuggestions failure → non-fatal, still returns SUCCESS`
7. `retention cleanup: deleteOlderThan called with correct threshold ms`
8. `step 2.5 readiness check: below threshold → DATA_READY_NOTIFIED stays false`
9. `step 2.5 readiness check: above both thresholds → DATA_READY_NOTIFIED set true`
10. `step 2.5: already notified → check skipped (prefs already true)`
11. `tier-reason suggestions: stats with linkedin tier_reason, count >= 20, tap rate < 5% → suggestion generated`
12. `tier-reason suggestions: deduplication against ML suggestions by package`
13. `ML path + tier path: combined suggestion count does not exceed MAX_SUGGESTIONS_PER_REPORT per path`
14. `completion notification posted with correct suggestion count`
15. `completion notification: 0 suggestions → body contains "No new suggestions"`
16. `completion notification: 1 suggestion → body contains "1 suggestion"`

**Important:** AiAnalysisWorker has two work names: `AiAnalysisWorker.WORK_NAME` (periodic) and `WorkScheduler.MANUAL_WORK_NAME` (one-shot). Test 15–16 verify the notification is posted from `doWork()` not from the enqueue path.

---

#### `ai/HealthCheckWorkerTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ai/HealthCheckWorkerTest.kt`

**Test cases (4):**
1. `listener package in enabled set → returns SUCCESS, no notification posted`
2. `listener package NOT in enabled set → returns SUCCESS, notification posted to "lithium_health" channel`
3. `WORK_NAME constant matches WorkScheduler.scheduleHealthCheck` — regression guard
4. `notification has PRIORITY_HIGH when listener disconnected`

---

### 2.4 ViewModel Tests (Robolectric + coroutines-test)

All ViewModel tests use `MainDispatcherRule` to replace `Dispatchers.Main` with `UnconfinedTestDispatcher`. Use Turbine for Flow assertions.

#### `ui/training/TrainingViewModelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/training/TrainingViewModelTest.kt`

This is the most complex ViewModel. Use fake DAOs backed by in-memory lists (not MockK for Flow-returning DAOs — Flows are easier to fake than mock).

**Test cases (32):**

*Initialization*
1. `init loads first challenge on creation`
2. `init with empty DB → uiState.exhausted = true`
3. `init emits isLoading=true then false`

*Notification pair submission*
4. `submit("left") → TrainingJudgment inserted with choice="left"`
5. `submit("right") → judgment with choice="right"`
6. `submit("tie") → judgment with choice="tie"`
7. `submit("skip") → judgment with choice="skip", xpAwarded=0`
8. `submit with no active challenge → no-op (guard against race condition)`
9. `submit real judgment → xpEvents emits XpEvent.Judgment with correct xp`
10. `submit skip → xpEvents does NOT emit XpEvent.Judgment`
11. `5 real judgments → xpEvents emits XpEvent.SetComplete with bonus`
12. `set accumulator resets after set completes`
13. `patternStats reactive update → isMapped() changes when judged count crosses MIN_JUDGMENTS_TO_MAP=3`

*App battle submission*
14. `submitAppBattle("left") → AppBattleJudgment inserted with correct elo snapshots`
15. `submitAppBattle("right") → Elo updates correctly for right-wins`
16. `submitAppBattle("tie") → both scores move toward center`
17. `submitAppBattle("skip") → Elo NOT updated, AppRanking rows unchanged`
18. `submitAppBattle("skip") → xpAwarded=0`
19. `submitAppBattle non-skip → AppRanking rows upserted with new Elo scores`
20. `submitAppBattle: existing ranking row updated (wins/losses/judgments incremented)`
21. `submitAppBattle: new package gets DEFAULT_ELO=1200 as starting Elo`

*Quest selection*
22. `selectQuest changes activeQuest StateFlow`
23. `selectQuest persists to SharedPreferences`
24. `selectQuest resets setXpAccumulator`
25. `quest with onlyUnclassified=true → loadNextPair uses getUnclassifiedCandidates`
26. `quest with packagePrefixes → candidate pool filtered to matching packages`
27. `FREE_PLAY quest allows app battles`
28. `non-FREE_PLAY quest never produces AppBattle challenge`

*Level progression*
29. `level-up: crossing threshold → XpEvent.LevelUp emitted with new level`
30. `level-up not re-emitted if level stays same on next emission`

*Quest completion*
31. `quest with goalXp: XP crosses goalXp boundary → XpEvent.QuestComplete emitted`
32. `quest complete not re-emitted if XP already past goalXp before new judgment`

---

#### `ui/briefing/BriefingViewModelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/briefing/BriefingViewModelTest.kt`

**Test cases (18):**
1. `no report in DB → uiState.report = null, suggestions = empty`
2. `report exists, no suggestions → uiState.report populated, suggestions empty`
3. `report with 2 pending suggestions → suggestions list has 2 entries`
4. `approveSuggestion → Rule created via RuleRepository.createFromSuggestion`
5. `approveSuggestion → suggestion status updated to "approved"`
6. `approveSuggestion last pending → report marked reviewed → uiState.report becomes null`
7. `rejectSuggestion → suggestion status "rejected", no rule created`
8. `rejectSuggestion last pending → report marked reviewed`
9. `updateCommentDraft → commentDrafts map updated, not yet persisted`
10. `approveSuggestion with comment draft → comment passed to updateSuggestionStatus`
11. `rejectSuggestion with comment draft → comment passed to updateSuggestionStatus`
12. `approving a suggestion clears its comment draft`
13. `toggleCommentExpanded: first toggle → expandedCommentId set`
14. `toggleCommentExpanded: second toggle on same id → expandedCommentId = null`
15. `analysisRunning: periodic RUNNING → analysisRunning=true`
16. `analysisRunning: periodic ENQUEUED → analysisRunning=false (periodic stays ENQUEUED between runs)`
17. `analysisRunning: manual ENQUEUED → analysisRunning=true`
18. `dataReady reflects DATA_READY_NOTIFIED pref`

---

#### `ui/setup/SetupViewModelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/setup/SetupViewModelTest.kt`

**Note:** `SetupViewModel` calls `NotificationManagerCompat.getEnabledListenerPackages()` and PowerManager — these need Robolectric.

**Test cases (10):**
1. `onboardingComplete: pref false → returns false`
2. `onboardingComplete: pref true → returns true`
3. `markOnboardingComplete sets pref to true`
4. `refresh updates _polledState`
5. `uiState combines ListenerState.isConnected with polled notificationAccess`
6. `ListenerState connected + polled=false → notificationAccessGranted=true (OR logic)`
7. `ListenerState disconnected + polled=true → notificationAccessGranted=true`
8. `contactsGranted false when permission not granted (Robolectric default)`
9. `uiState emits new values when refresh() called`
10. `batteryOptimizationExempt: PowerManager.isIgnoringBatteryOptimizations returns true → exempt=true`

---

#### `ui/queue/QueueViewModelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/queue/QueueViewModelTest.kt`

**Test cases (6):**
1. `empty queue → uiState has empty list`
2. `QueuedNotification in DB → appears in UI state`
3. `dismiss action removes row from queue`
4. `dismiss all removes all rows`
5. `queue flow is reactive — new row added to DB appears without refresh`
6. `QueuedNotification created from QUEUE rule action has correct fields`

---

#### `ui/rules/RulesViewModelTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/ui/rules/RulesViewModelTest.kt`

**Test cases (6):**
1. `rules list populated from DB`
2. `deleteRule removes row from DB`
3. `toggleStatus: approved→disabled`
4. `toggleStatus: disabled→approved`
5. `approvedRules StateFlow updates after toggle`
6. `createFromSuggestion in BriefingVM creates rule that appears in RulesVM list`

---

### 2.5 Compose UI Tests (Instrumented)

**Location:** `app/src/androidTest/java/ai/talkingrock/lithium/ui/`

These require a device or emulator. Use `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>`.

**Brittleness warning:** Animation-heavy components (battle outcome animation, level-up animation, XP pop) are the most flake-prone. Use `composeRule.mainClock.advanceTimeBy(BATTLE_DURATION_MS + 100)` to skip animations deterministically, or set `animationsDisabled=true` in test manifest.

#### `ui/training/TrainingScreenTest.kt` (NEW)

**Test cases (12):**
1. `loading state shows progress indicator`
2. `exhausted state shows "No more pairs to judge"`
3. `notification pair challenge renders left and right cards`
4. `tapping left card submits choice="left"`
5. `tapping right card submits choice="right"`
6. `tapping tie button submits choice="tie"`
7. `tapping skip button submits choice="skip"`
8. `XP badge updates after judgment`
9. `quest chip row renders all quests`
10. `tapping quest chip selects it (highlighted state)`
11. `app battle renders two package name cards`
12. `battle outcome animation plays after submit (advance clock, verify next challenge loads)`

---

#### `ui/briefing/BriefingScreenTest.kt` (NEW)

**Test cases (8):**
1. `no data state: shows "Your Briefing" header`
2. `report present: report text rendered`
3. `suggestion card with "Approve" and "Reject" buttons visible`
4. `approving suggestion removes it from list`
5. `analysis running spinner visible when analysisRunning=true`
6. `tier breakdown 24h card shows correct tier counts`
7. `empty tier breakdown: zero-count card not shown`
8. `dataReady=false: "collecting data" placeholder shown`

---

### 2.6 Full-Simulation Harness Tests (Robolectric, in-memory Room)

These tests use `SyntheticNotifications` + in-memory DB to verify end-to-end pipeline KPIs.

#### `simulation/FullPipelineTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/simulation/FullPipelineTest.kt`

This is the most valuable single test file in the plan. It seeds the DB, runs TierBackfill, then AiAnalysis, then asserts KPIs.

**Test cases (15):**
1. `seed heavySocialMedia profile → backfill assigns tier to all rows → zero tier_reason=NULL after`
2. `seed heavySocialMedia → analysis run → report inserted with non-null summaryJson`
3. `seed heavySocialMedia → suggestions include at least one QUEUE for social_signal package`
4. `seed heavySocialMedia → API tier breakdown sums to total row count`
5. `seed mediaHeavy → analysis report: alert_count < 100, background_count > 2000`
6. `seed spamVictim → tier-reason stats have amazon_shopping with count >= 60`
7. `seed spamVictim → tier-path suggestions include SUPPRESS for amazon`
8. `approve first suggestion → Rule created with status="approved", action from suggestion`
9. `approved PackageMatch suppress rule → next notification from that package → RuleEngine.evaluate() returns SUPPRESS`
10. `QueuedNotification: QUEUE rule → notification from matching package → QUEUE action returned`
11. `judgmentCount reactive flow: 5 judgments inserted → judgmentCount=5`
12. `XP reactive flow: 10 XP judgment → totalXpFlow emits 10`
13. `pattern stats reactive: judgment on notification from pattern P → P.judged increments`
14. `Elo update round-trip: AppBattleJudgment left-wins → AppRanking.eloScore increases for left`
15. `130K row simulation: insert 130K synthetic rows, run backfill — completes in < 60 seconds, zero tier_reason=NULL at end` (performance threshold, flaky if machine is loaded — mark as `@LargeTest`)

**Note on test 15:** 130K rows in an in-memory Room on JVM is plausible (~5-10 seconds typically). The 60-second threshold is conservative. This test MUST NOT run on CI by default — gate behind `@Category(LargeTest::class)` and run explicitly.

---

### 2.7 Ktor API Tests (JVM, no Android)

The API routes are pure Ktor CIO logic. Use `testApplication {}` from `ktor-server-test-host`.

**Dependency to add:**
```kotlin
testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
testImplementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
```

#### `api/LithiumApiServerTest.kt` (NEW)

**Location:** `app/src/test/java/ai/talkingrock/lithium/api/LithiumApiServerTest.kt`

Use a fake `NotificationDao` and `AppBehaviorProfileDao`.

**Test cases (18):**
1. `GET /api/health → 200, status="ok", uptimeMs >= 0`
2. `GET /api/notifications → 200, returns list of NotificationDto`
3. `GET /api/notifications?since=X → only rows with postedAtMs >= X`
4. `GET /api/notifications?tier=1 → only tier=1 rows`
5. `GET /api/notifications?tier=1&tier=3 → tier 1 and tier 3 rows`
6. `GET /api/notifications?limit=5 → at most 5 rows`
7. `GET /api/notifications/unresolved → only ai_classification=NULL rows`
8. `GET /api/stats → totalNotifications = row count`
9. `GET /api/stats → tierBreakdown sums to totalNotifications`
10. `GET /api/stats → classifiedNotifications + unclassifiedNotifications = total`
11. `GET /api/stats with no rows → tierBreakdown is empty map`
12. `GET /api/contacts → returns per-app engagement sorted by totalNotifications DESC`
13. `POST /api/classifications → updateClassification called for each entry`
14. `POST /api/classifications → returns AckResponse with correct updated count`
15. `POST /api/dismiss → updateRemoval called with "api_dismiss" reason`
16. `POST /api/dismiss → returns AckResponse with correct updated count`
17. `GET /api/stats → noiseRatio = classifiedNotifications / total (when total > 0)`
18. `GET /api/stats → noiseRatio = 0 when total = 0`

**Consistency check (ties to KPI matrix):** Test 9 (`tierBreakdown sums to total`) is the single most important API test because BriefingViewModel's 24h tier breakdown card must match DB state. This test prevents the API from reporting a partial breakdown.

---

## 3. Fixtures and Harness

### 3.1 Synthetic Notification Generator

`SyntheticNotifications.kt` already exists with 7 profiles covering ~5,000 notifications total. It is solid. The following enhancements are needed:

**Add to `SyntheticNotifications.kt`:**

```
fun edgeCaseNotifications(): List<NotificationRecord>
```

Returns a list covering:
- `title=null, text=null` (both null — should not crash TierClassifier; fullText becomes "")
- `title=""` (empty string)
- `text` containing 1,000 characters (very long text — UI truncation edge case)
- Package name that is itself a Lithium package (should tier 0)
- `category = "transport"` on a messaging package
- `category = "call"` with isFromContact=true (call_known)
- `category = "reminder"` on an unknown package (→ calendar)
- isOngoing=true AND security keyword in text (security wins per ordering)
- Package from an "uninstalled" app — label lookup fails, falls back gracefully
- Duplicate SBN key — same packageName+channelId combination, posted twice
- Very recent post time (within 1ms of sinceMs threshold)
- Post time of 0 (epoch)

**Add: `fun largeSyntheticDataset(count: Int = 5000, seed: Int): List<NotificationRecord>`**

Generates `count` rows with realistic package/tier distribution:
- 40% tier 0 (ongoing, media)
- 20% tier 1 (marketing, LinkedIn, Amazon)
- 30% tier 2 (Gmail, calendar, financial, GitHub, default)
- 10% tier 3 (SMS, OTP, calls)

Uses a seeded `Random` for reproducibility. Each row has:
- Realistic `postedAtMs` spread over the last 30 days
- `isOngoing` consistent with tier
- `isFromContact` at ~15% for eligible packages
- `tierReason` set (no NULL — simulating post-backfill state)

### 3.2 Real-Data Snapshot/Replay Approach

The SQLCipher key is derived from the Android Keystore and cannot be extracted without root. Two practical approaches:

**Option A (recommended): Decrypted export via SimulationActivity (already exists)**

The debug `SimulationActivity` already has a DB handle. Add a `exportDecryptedSnapshot()` function that:
1. Opens the encrypted DB (already open — just use the injected DAO)
2. Writes a subset of rows (e.g., last 10,000 notifications) to a plain SQLite file in `/sdcard/Download/lithium_snapshot.db` using `Room.databaseBuilder` without SQLCipher
3. Pull with `adb pull /sdcard/Download/lithium_snapshot.db`
4. Use as a fixture file in instrumented tests via `MigrationTestHelper.createDatabase()`

This snapshot contains real notification content — **do not commit to git**. Add `*_snapshot.db` to `.gitignore`.

**Option B: Synthetic regression dataset**

Instead of real data, generate a large reproducible synthetic dataset with `largeSyntheticDataset(count=130000)`, prepopulate an in-memory DB, run the full pipeline, and assert KPIs. This is what `FullPipelineTest.kt` test case 15 does.

Option B is the test-clean path. Option A is for verifying behavior on real patterns.

### 3.3 Test Injection Bypass for Notification Ingestion

`LithiumNotificationListener.onNotificationPosted` cannot be called from tests because `StatusBarNotification` is a final Android class. The bypass is at the `NotificationRepository.insert()` layer:

In test code, call `notificationRepository.insert(record)` directly with a synthetic `NotificationRecord`. This correctly tests everything downstream of the listener (tier classification, rule evaluation, DB persistence).

For tests that need to verify `buildRecord()` logic (title/text extraction, ContactsResolver, TierClassifier call), test those collaborators in isolation:
- `TierClassifier` has direct unit tests
- `ContactsResolver` needs Robolectric to stub `ContentResolver`

**Add to `FullPipelineTest.kt`: a `fun injectSyntheticNotifications(n: Int)` helper** that bulk-inserts `NotificationRecord` objects with correct tier fields set (simulating post-listener state). This is the harness for large-scale tests.

---

## 4. KPI Verification Matrix

| User Action | Expected State Changes | Assertion Location |
|---|---|---|
| **Ingestion** | | |
| SBN arrives, listener active | `notifications` row inserted; `tier` and `tier_reason` populated; `isFromContact` set | `NotificationDaoTest`, `FullPipelineTest` |
| SBN from contact | `tier=3`, `tier_reason="sms_known"` or `"gmail_known"` or `"call_known"` | `TierClassifierTest`, `FullPipelineTest` |
| SBN with OTP text | `tier=3`, `tier_reason="security_2fa"` regardless of isFromContact or isOngoing | `TierClassifierTest` |
| SBN with isOngoing=true (no security text) | `tier=0`, `tier_reason="ongoing_persistent"` | `TierClassifierTest` |
| SBN matches SUPPRESS rule | `cancelNotification(sbn.key)` called; row still in DB | `RuleEngineTest`, `FullPipelineTest` test 9 |
| SBN matches QUEUE rule | `cancelNotification(sbn.key)` called; `QueuedNotification` row inserted | `QueueViewModelTest`, `FullPipelineTest` test 10 |
| **Backfill** | | |
| TierBackfillWorker runs | All `tier_reason IS NULL` rows updated; zero remaining after run | `TierBackfillWorkerTest` test 3-4, `FullPipelineTest` test 1 |
| Worker killed mid-run | Second run picks up exactly from where it stopped (filter is `tier_reason IS NULL`) | `TierBackfillWorkerTest` test 9 |
| **AI Analysis** | | |
| AiAnalysisWorker.doWork() | Report row inserted in `reports`; suggestions linked via `report_id` | `AiAnalysisWorkerTest` test 2, `FullPipelineTest` test 2-3 |
| Analysis with 0 unclassified rows | Still generates report from PatternAnalyzer output | `AiAnalysisWorkerTest` test 1 |
| Data readiness threshold crossed | `DATA_READY_NOTIFIED` pref set to true | `AiAnalysisWorkerTest` test 9 |
| Tier-reason path | `TierReasonStat` with `linkedin`, count >= 20, tap < 5% → SUPPRESS suggestion | `AiAnalysisWorkerTest` test 11, `SuggestionGeneratorTest` test 15 |
| Manual "Run Now" tap | WorkManager enqueues MANUAL_WORK_NAME, `analysisRunning=true` in BriefingVM | `BriefingViewModelTest` test 17 |
| **Rule Engine** | | |
| Approve suggestion → rule created | `Rule` row with `status="approved"`, `action` and `conditionJson` copied from suggestion | `BriefingViewModelTest` test 4, `RuleEngineIntegrationTest` test 7 |
| New approved rule | `RuleRepository.approvedRules` StateFlow emits updated list | `RuleEngineIntegrationTest` test 2 |
| Next matching SBN | `RuleEngine.evaluate()` returns SUPPRESS/QUEUE | `RuleEngineTest` test 2, `FullPipelineTest` test 9 |
| **Queue** | | |
| QUEUE action fires | `QueuedNotification` row inserted | `QueueViewModelTest` test 2 |
| Queue screen loads | `QueueViewModel.uiState` shows queued rows | `QueueViewModelTest` test 2 |
| Dismiss from queue | Row removed; flow emits updated list | `QueueViewModelTest` test 3 |
| **Training Signal** | | |
| `submit("left")` | `TrainingJudgment` row with `choice="left"`, `leftNotificationId/rightNotificationId`, snapshot fields | `TrainingViewModelTest` test 4 |
| `submit("skip")` | Row with `choice="skip"`, `xpAwarded=0`; does NOT appear in `totalXpFlow` | `TrainingViewModelTest` test 7, `TrainingJudgmentDaoTest` test 11 |
| Judgment emitted | `xpEvents` emits `XpEvent.Judgment(xp, newlyMapped)` | `TrainingViewModelTest` test 9 |
| 5 real judgments in sequence | `xpEvents` emits `XpEvent.SetComplete` | `TrainingViewModelTest` test 11 |
| Judgment increases `judged` in pattern stats | `getPatternStatsFlow` emits updated counts | `NotificationDaoTest` test 26 |
| Level-up threshold crossed | `xpEvents` emits `XpEvent.LevelUp` | `TrainingViewModelTest` test 29 |
| Quest XP crosses goalXp | `xpEvents` emits `XpEvent.QuestComplete` | `TrainingViewModelTest` test 31 |
| Quest tagged on judgment | `TrainingJudgment.questId` matches active quest | `TrainingViewModelTest` test 4 |
| **App Battles** | | |
| `submitAppBattle("left")` non-skip | `AppBattleJudgment` inserted; `AppRanking` upserted with new Elo | `TrainingViewModelTest` test 19 |
| `submitAppBattle("skip")` | `AppBattleJudgment` inserted with choice="skip"; Elo NOT changed | `TrainingViewModelTest` test 17 |
| New package (first battle) | `AppRanking` created with `eloScore=1200`, `wins/losses/ties=0` | `TrainingViewModelTest` test 21 |
| Left-wins: equal ratings | Left gains 16 Elo, right loses 16 | `EloTest` test 6 |
| Eligible packages | Only packages with tier>0, non-ongoing, non-null title/text | `AppRankingDaoTest` test 6 |
| **Ktor API** | | |
| `GET /api/stats` | `tierBreakdown` sums to `totalNotifications` | `LithiumApiServerTest` test 9 |
| Backfill running concurrently | `GET /api/stats` returns consistent (non-crashing) snapshot | `FullPipelineTest` (concurrent read during backfill) |
| `GET /api/notifications?tier=3` | Only tier=3 rows returned | `LithiumApiServerTest` test 4 |
| `POST /api/classifications` | `ai_classification` and `ai_confidence` updated in DB | `LithiumApiServerTest` test 13 |
| **Briefing Screen** | | |
| Report inserted by worker | `BriefingViewModel.uiState.report` non-null | `BriefingViewModelTest` test 2 |
| All suggestions actioned | `report` auto-marked reviewed → `uiState.report = null` | `BriefingViewModelTest` test 6 |
| Worker RUNNING (periodic) | `analysisRunning=true` | `BriefingViewModelTest` test 15 |
| Worker ENQUEUED (periodic) | `analysisRunning=false` (periodic stays ENQUEUED) | `BriefingViewModelTest` test 16 |
| Worker ENQUEUED (manual) | `analysisRunning=true` | `BriefingViewModelTest` test 17 |
| **Setup / Onboarding** | | |
| First cold start | `onboardingComplete=false` → Setup screen routed | `SetupViewModelTest` test 1 |
| Notification access granted | `notificationAccessGranted=true` via ListenerState OR polled check | `SetupViewModelTest` test 6 |
| Contacts permission revoked | `isFromContact` always returns false | `TierClassifierTest` (via isFromContact=false parameter) |
| **Migrations** | | |
| Install v1 app, upgrade to v8 | All tables exist; existing rows readable | `MigrationTest` test 13 |
| v4→v8 upgrade | tier_reason=NULL rows survive; TierBackfillWorker can process them | `MigrationTest` test 14 |

### Training → Classification Feedback (Stubbed Downstream)

**Current state:** `RuleCondition.NotFromContact.matches()` always returns `false` (line 99, `RuleEngine.kt`). The comment reads: "In M1 this field does not exist on NotificationRecord — always returns false so the rule never fires until M2 populates the field."

`TrainingJudgment` rows are correctly persisted with tier/classification snapshots, but they do not currently feed back into `TierClassifier`, `SuggestionGenerator`, or rule-suggestion ranking.

**What to test NOW (pre-wiring):**

```kotlin
// In RuleEngineTest.kt
@Test fun `NotFromContact condition always returns ALLOW (M1 stub — regression guard)`() {
    // This test deliberately asserts the STUBBED behavior.
    // When the stub is removed, this test will fail — that's the signal to replace
    // it with the real contract test.
    val rule = Rule(conditionJson = """{"type":"not_from_contact"}""", action = "suppress", status = "approved")
    fakeRules.value = listOf(rule)
    val record = NotificationRecord(packageName = "any.package", isFromContact = false, ...)
    assertEquals(RuleAction.ALLOW, engine.evaluate(record))
}
```

**Contract spec for when the wiring is built:**

When `NotFromContact` is fully implemented:
1. `NotFromContact` condition with `isFromContact=false` record → SUPPRESS
2. `NotFromContact` condition with `isFromContact=true` record → ALLOW (is from contact, rule doesn't match)
3. Training judgment choices flow into `SuggestionGenerator` ranking: packages with more "right loses" judgments should score lower in suggestion ranking
4. Elo scores are exposed via `AppRankingDao.getAll()` — sorted by score, available for downstream consumption

The contract test for (3) should be written as a pending/skipped test with a clear TODO comment that documents the expected behavior:

```kotlin
@Ignore("Downstream wiring not yet implemented — see GitHub issue for spec")
@Test fun `training judgment choices influence suggestion ranking`() {
    // Given: 10 judgments where LinkedIn notifications always lose
    // When: SuggestionGenerator runs
    // Then: LinkedIn appears at top of suppress suggestions with higher confidence
}
```

**Decision required:** The user must decide whether to implement this as a failing test (red in CI until wired) or an ignored test. Failing test is strongly recommended — it makes the gap visible.

---

## 5. Maestro Flow Layout

### Existing flows — status and gaps

| Flow | Current state | Gap |
|---|---|---|
| `01_setup_screen.yaml` | Solid — verifies permission tiles with all permissions revoked | None |
| `02_setup_to_briefing.yaml` | Solid — grants permissions, completes onboarding | None |
| `03_briefing_screen.yaml` | Moderate — checks tab bar and "Your Briefing" header | Does not verify tier breakdown card contents or suggestion list |
| `04_navigate_all_tabs.yaml` | Solid — smoke tests all 5 tabs | None |
| `05_create_rule.yaml` | Needs verification — does it assert the rule appears in list? | Likely just taps through, does not verify DB state |
| `06_settings_interactions.yaml` | Unknown — needs inspection | Should verify constraint toggles persist |
| `07_run_analysis.yaml` | Shallow — waits for "Your Briefing" but does not verify content | Does not check suggestion count or tier breakdown |
| `08_suggestion_flow.yaml` | **Stub** — only checks tab bar, no suggestion interaction | Entire suggestion approval flow untested |
| `09_purge_data.yaml` | Needs verification | Should verify row count drops to 0 |
| `10_stress_navigation.yaml` | Smoke test | Fine as-is |
| `11_cold_start.yaml` | Smoke test | Fine as-is |
| `12_permission_revoke.yaml` | Verifies setup tiles go red | Fine as-is |

### New flows to create

#### `13_training_tab.yaml` (NEW)

```yaml
# Flow: Training tab — judge pairs, verify XP and judgment count update
appId: ai.talkingrock.lithium.debug
---
- stopApp
- launchApp
- tapOn: "Training"
- extendedWaitUntil:
    visible: { id: "training_challenge_left" }
    timeout: 10000
# Record judgment count before
# Tap left card
- tapOn: { id: "training_challenge_left" }
# Wait for animation
- waitForAnimationToEnd
# Verify judgment counter incremented (will need a testTag on the counter)
- assertVisible: { id: "training_judgment_count" }
# Tap through 4 more to complete a set
# ... (repeat tapOn left + waitForAnimation 4 more times)
# Verify set complete toast/event
```

**Brittleness note:** The battle animation runs for `BATTLE_DURATION_MS = 650ms`. Maestro's `waitForAnimationToEnd` should handle this, but it may be slow. Use a 2-second wait explicitly if `waitForAnimationToEnd` proves unreliable on the Pixel device.

#### `14_suggestion_approve.yaml` (NEW — REPLACES the stub in 08)

```yaml
# Flow: Full suggestion approval → rule created → next matching notification suppressed
# Prerequisites: analysis must have run and produced a suggestion.
# This flow requires real data or pre-seeded data via SimulationActivity.
appId: ai.talkingrock.lithium.debug
---
- stopApp
- launchApp
- tapOn: "Briefing"
- extendedWaitUntil:
    visible: { text: "Your Briefing" }
    timeout: 20000
# Locate first suggestion card
- extendedWaitUntil:
    visible: { id: "suggestion_card_0" }
    timeout: 5000
# Approve it
- tapOn: { id: "suggestion_approve_button_0" }
- waitForAnimationToEnd
# Verify suggestion disappears
- assertNotVisible: { id: "suggestion_card_0" }
# Navigate to Rules to verify rule was created
- tapOn: "Rules"
- extendedWaitUntil:
    visible: { id: "rule_list_item_0" }
    timeout: 5000
```

**Brittleness note:** This flow requires a suggestion to exist. It MUST run after flow 07 (which triggers analysis). The `run-tests.sh` ordering already ensures 07 before 08. Update the run order to use 14 as the replacement.

#### `15_queue_screen.yaml` (NEW)

```yaml
# Flow: Queue screen — verify queued notifications appear and dismiss works
appId: ai.talkingrock.lithium.debug
---
# Inject a notification that will be queued by an existing QUEUE rule
# (Requires a QUEUE rule to exist from flow 14 above)
- evalScript:
    script: |
      const result = await $native.runShellCommand(
        `adb shell cmd notification post -t "Test queued notification" "q1" "com.target.package"`
      );
- sleep: 2000
- tapOn: "Queue"
- extendedWaitUntil:
    visible: { id: "queue_item_0" }
    timeout: 5000
- tapOn: { id: "queue_dismiss_button_0" }
- waitForAnimationToEnd
- assertNotVisible: { id: "queue_item_0" }
```

### Permission grant/revoke reliability on CI

The existing `grant-permissions.sh` uses `adb shell cmd notification allow_listener` and `adb shell appops set`. These are reliable on Pixel devices running Android 13-15. The one known failure mode is when the device is in a locked state — the existing `run-tests.sh` already wakes the device and presses menu key.

**Addition for CI:** Add a `grant-battery-exemption.sh`:

```bash
adb shell dumpsys battery unplug
adb shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
adb shell cmd deviceidle whitelist +$APP_ID 2>/dev/null || true
```

Doze whitelist inclusion prevents the OS from killing WorkManager tests mid-run.

**For Woodpecker CI (if running on a connected Pixel):** Add a pre-step to verify device connectivity:

```yaml
- name: verify-device
  commands:
    - adb devices | grep -q "device$" || (echo "No device attached"; exit 1)
```

---

## 6. Runbook

### 6.1 One-Command Runners

```bash
# JVM unit tests (no device needed, fastest, runs on CI in < 30s)
./gradlew :app:testDebugUnitTest

# Specific test class (faster iteration)
./gradlew :app:testDebugUnitTest --tests "ai.talkingrock.lithium.classification.TierClassifierTest"

# All instrumented tests (requires connected device/emulator)
./gradlew :app:connectedDebugAndroidTest

# Migration tests only (instrumented, device required)
./gradlew :app:connectedDebugAndroidTest --tests "*.MigrationTest"

# Maestro full suite (device required, ~10 minutes)
cd /home/kellogg/dev/Lithium && ./.maestro/run-tests.sh

# Maestro single flow
./.maestro/run-tests.sh .maestro/07_run_analysis.yaml

# Large simulation test (device or emulator, slow — ~60s for 130K rows)
./gradlew :app:testDebugUnitTest --tests "*.FullPipelineTest.130K*" -PrunLargeTests=true
```

### 6.2 Running Against Real vs Synthetic Data

**Synthetic data (default):** All JVM tests use `SyntheticNotifications` fixtures. Fast, reproducible, CI-safe.

**Real data (manual, on-device):**
1. Run `SimulationActivity` from Android Studio debug menu or via `adb shell am start -n ai.talkingrock.lithium.debug/ai.talkingrock.lithium.debug.SimulationActivity`
2. Profiles 1-9 are already wired — the simulation clears DB and reseeds
3. For export of real-device snapshot: add `exportDecryptedSnapshot()` to `SimulationActivity` (see Section 3.2)
4. Pull snapshot: `adb pull /sdcard/Download/lithium_snapshot.db ./fixtures/`
5. Load in instrumented test: `Room.databaseBuilder(context, ..., "lithium_snapshot.db").build()`

### 6.3 CI Gating

**Gate on unit tests** (fast, always run): `testDebugUnitTest` runs in ~30 seconds on any CI agent. Block merges on failure.

**Gate on instrumented tests** (slow, device-dependent): Run nightly on a dedicated Pixel-connected CI agent. Do not block merges on transient instrumented test failures without a human review.

**Maestro on CI:** Maestro tests require a real device or emulator with Google Play Services. Current infrastructure (Woodpecker + Corellia) does not include an Android emulator. Options:
- Attach a real Pixel to the CI server via USB
- Use GitHub Actions with `reactivecircus/android-emulator-runner@v2` (requires GitHub Actions, not Forgejo)
- Run Maestro manually on pre-release builds only

**Recommended CI tiers:**

| Tier | Tests | Trigger | Target time |
|---|---|---|---|
| Fast (every push) | JVM unit tests | git push | < 60s |
| Nightly | Instrumented + migration | cron | < 20min |
| Pre-release | Full Maestro suite | manual tag | < 30min |

### 6.4 Flake Patterns to Watch

1. **Battle animation tests (Compose UI):** The `BATTLE_DURATION_MS=650ms` delay in `TrainingViewModel.submit()` creates a timing window. Always advance the test clock or add explicit waits.
2. **WorkManager in tests:** `WorkManagerTestInitHelper.initializeTestWorkManager(context)` must be called before any Worker test. Forgetting this causes the worker to be scheduled against the real WorkManager and the test hangs.
3. **Flow tests with `stateIn(SharingStarted.WhileSubscribed)`:** The subscription drops after 5 seconds. In tests using `runTest`, the coroutine context advances time. Use `Turbine.test {}` rather than `first()` to avoid timing races.
4. `getPatternStatsFlow` in TrainingViewModel depends on a correlated subquery against `training_judgments`. In tests, ensure both tables are populated before emitting the flow value.
5. **Maestro `waitForAnimationToEnd`** is unreliable for animations that are triggered by state change rather than user interaction. Use explicit `sleep` with a known duration as fallback.

---

## 7. Risks and Open Questions

### 7.1 Known Risks

**R1: NotFromContact rule condition is permanently ALLOW (M1 stub)**  
`RuleEngine.matches()` line 99: `is RuleCondition.NotFromContact -> false`. Any rule with this condition silently never fires. There is no test guarding this. A test must be added that asserts the stub behavior — so when the stub is removed, the test fails and forces the implementer to write the real contract test.

**R2: Migration 1→8 chain test requires schema JSON v1 through v8**  
All 8 schema JSON files exist in `app/schemas/`. Verified: `1.json` through `8.json`. The `MigrationTestHelper` needs these. Do not delete or rename them.

**R3: SQLCipher + JVM incompatibility**  
No JVM test can use `SupportOpenHelperFactory`. Any test that tries to build a Room DB with SQLCipher in a Robolectric context will fail with `UnsatisfiedLinkError: sqlcipher`. The architecture in Section 1.2 avoids this by using plain in-memory Room for unit/Robolectric tests.

**R4: AiAnalysisWorker has two work names that the BriefingViewModel observes separately**  
`AiAnalysisWorker.WORK_NAME` (periodic) and `WorkScheduler.MANUAL_WORK_NAME` (manual one-shot). `BriefingViewModel` observes both. If a test only enqueues the periodic name, `analysisRunning` for the manual path will not be tested. `BriefingViewModelTest` must explicitly test both paths.

**R5: `getPatternStatsFlow` is a correlated subquery — performance at scale**  
The query (`SELECT ... SUM(CASE WHEN n.id IN (SELECT ... UNION SELECT ...))`) does a full scan of `training_judgments` for each notification row. At 130K notifications and 10K judgments, this could be slow. The `FullPipelineTest` large test will surface this. If it's slow, the fix is to add an index on `training_judgments(left_notification_id)` and `(right_notification_id)`.

**R6: `pickAppBattlePair` uses `kotlin.random.Random.nextDouble()` without a seed in `TrainingViewModel.shouldDoAppBattle()`**  
The 25%/50% probability split means tests that call `loadNextPair()` may non-deterministically get either a notification pair or an app battle. In tests, mock `appRankingDao.getEligiblePackages()` to return 0 or 1 apps to force the notification pair path, or return 2+ apps with a fake DAO that controls the random outcome.

**R7: `@HiltAndroidTest` ViewModel tests require Hilt in the test variant**  
`hilt.android.testing` is not currently in `build.gradle.kts`. Add it before attempting Hilt-injected instrumented tests. See Section 1.3.

**R8: Ktor `testApplication {}` requires `ktor-server-test-host`**  
Not currently in `build.gradle.kts`. Must be added. See Section 2.7.

### 7.2 Open Questions for the User

**Q1: NotFromContact stub — failing test or ignored test?**  
Should `RuleEngineTest` include a failing test that documents the stub, or an `@Ignore` with a TODO? Recommendation: add the test but mark it as passing (asserting the current stub behavior = ALLOW). It will break when the stub is removed, which is the desired signal. If you want CI to break explicitly, use a failing-by-contract approach.

**Q2: Real-data snapshot export — is it acceptable to ship an export function in the debug build?**  
The `exportDecryptedSnapshot()` function in `SimulationActivity` would write decrypted notification content to external storage. This is only in the debug build (already gated), but the data is real personal notifications. Confirm this is acceptable before implementing.

**Q3: 130K row simulation test — should it run on CI?**  
The 60-second threshold is fine on a developer machine but may be too slow for CI. If the Woodpecker CI Pixel is the nightly test runner, this test should run there. If CI uses a slower emulator, the threshold may need to be raised to 300 seconds or the test should be CI-excluded.

**Q4: Maestro CI — emulator vs real device?**  
Maestro flows 07 (run analysis) and 14 (suggestion approval) require real notification data in the DB. A freshly-installed emulator has no notification history. For CI, you must either: (a) seed the DB via `SimulationActivity` before running these flows, or (b) accept that flows 07/14 are manual-only. The `run-tests.sh` Phase 2 sends 5 synthetic notifications via `adb shell cmd notification post` — this is enough to seed one analysis run.

**Q5: What happens to training judgments when retention cleanup runs?**  
`AiAnalysisWorker.doWork()` calls `notificationDao.deleteOlderThan(threshold)`. This hard-deletes notification rows. But `TrainingJudgment` rows reference `left_notification_id` and `right_notification_id` as foreign keys — there are **no FOREIGN KEY constraints** in the schema. The judgment rows reference deleted notification rows without error, but the deleted notification's content (title, text, tier) is captured as a snapshot in the `TrainingJudgment` itself (that's what the snapshot fields are for). This is intentional and correct. A test should verify this explicitly: delete a notification, verify its judgment row still exists and snapshot fields are intact.

---

## 8. Phase Breakdown

### Phase 0 (2-4 days) — Foundation and Highest Regression Risk

**Goal:** Catch the bugs that would be hardest to find manually.

1. Extend `TierClassifierTest.kt` with 18 new cases (transport-before-ongoing ordering, security-before-ongoing, null text, edge keywords). The ordering bug (security vs ongoing priority) would be silent in production.
2. Create `RuleEngineTest.kt` (20 cases). The hot path has zero coverage.
3. Create `EloTest.kt` (12 cases). Score calculation bugs silently corrupt the ranking.
4. Create `TrainerLevelTest.kt` (18 cases). Level progression logic is non-trivial.
5. Add the `NotFromContact` regression guard test to `RuleEngineTest`.
6. Add `MigrationTest.kt` cases 13-14 (the chain tests). Running migrations on real user data is the highest-stakes operation in the app.

**Deliverable:** ~70 new passing tests. CI catches tier-ordering regressions, rule engine bugs, and migration failures before they hit the Pixel.

---

### Phase 1 (1 week) — DAO and Worker Coverage

1. `NotificationDaoTest.kt` (28 cases) — especially `getPatternStatsFlow` correlated query
2. `TrainingJudgmentDaoTest.kt` (12 cases)
3. `AppRankingDaoTest.kt` (10 cases)
4. `TierBackfillWorkerTest.kt` (10 cases) — resumability test is critical
5. `AiAnalysisWorkerTest.kt` (16 cases) — both work names, completion notification

**Deliverable:** DB layer has full test coverage. Backfill worker resumability is verified.

---

### Phase 2 (1 week) — ViewModel and API

1. `TrainingViewModelTest.kt` (32 cases) — the largest single file
2. `BriefingViewModelTest.kt` (18 cases)
3. `SetupViewModelTest.kt` (10 cases)
4. `QueueViewModelTest.kt` (6 cases)
5. `RulesViewModelTest.kt` (6 cases)
6. `LithiumApiServerTest.kt` (18 cases) — requires adding `ktor-server-test-host`
7. `SuggestionGeneratorTest.kt` (22 cases)

**Deliverable:** All ViewModels verified. API contract locked down. Suggestion generation logic covered.

---

### Phase 3 (3-4 days) — Integration Simulation

1. Add `largeSyntheticDataset()` and `edgeCaseNotifications()` to `SyntheticNotifications.kt`
2. Create `FullPipelineTest.kt` (15 cases)
3. Create `TierClassificationPipelineTest.kt` (8 cases — classifier+tier consistency)
4. Create `RuleEngineIntegrationTest.kt` (8 cases — full DB round trip)

**Deliverable:** End-to-end pipeline verifiable without a device. 130K row simulation establishes performance baseline.

---

### Phase 4 (2-3 days) — Compose UI and Maestro Hardening

1. `TrainingScreenTest.kt` (12 cases — instrumented)
2. `BriefingScreenTest.kt` (8 cases — instrumented)
3. Replace `08_suggestion_flow.yaml` with `14_suggestion_approve.yaml`
4. Add `13_training_tab.yaml`
5. Add `15_queue_screen.yaml`
6. Fix Maestro flows 05/06 shallow assertions

**Deliverable:** UI layer has basic Compose test coverage. Maestro suite is no longer stub-heavy.

---

### Phase 5 (ongoing) — Training → Classification Wiring

When the `NotFromContact` rule condition is implemented:
1. Replace the regression-guard test with real contract tests
2. Add `@Ignore`-to-enabled tests for judgment-to-suggestion-ranking feedback
3. Add DAO query for "judgment choice breakdown by package" to support the downstream signal

---

## 9. Summary Statistics

| Phase | Files created | New test cases | Effort |
|---|---|---|---|
| 0 (foundation) | 4 | ~70 | 2-4 days |
| 1 (DAO + workers) | 5 | ~76 | 1 week |
| 2 (viewmodels + API) | 7 | ~112 | 1 week |
| 3 (integration) | 4 | ~39 | 3-4 days |
| 4 (UI + Maestro) | 5 | ~23 | 2-3 days |
| **Total** | **25** | **~320** | **~30 days** |

---

## 10. Single Most Important Test to Write First

**`TierClassifierTest` — the security-before-ongoing ordering test.**

```kotlin
@Test fun `security keyword beats ongoing flag — OTP from ongoing notification is tier 3`() {
    assertEquals(
        3 to "security_2fa",
        classify(
            pkg = "com.google.android.apps.messaging",
            text = "Your verification code is 482910",
            isOngoing = true  // This would return tier 0 if ordering were wrong
        )
    )
}
```

This test is pure JVM, takes 1 second to write, and guards the most security-sensitive ordering decision in the entire codebase. If `isOngoing` were checked before `containsSecurityKeyword` (the wrong order), OTP codes embedded in ongoing notifications would be silently classified as invisible. That ordering is correct today, but a future refactor could break it — and without this test, the failure would be silent.
