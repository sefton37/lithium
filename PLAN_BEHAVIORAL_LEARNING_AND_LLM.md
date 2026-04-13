# Plan: Behavioral Learning Profile + Dual LLM Backend

**Status: Phase 1 implemented. Phase 2 partially implemented.**

Phase 1 (`AppBehaviorProfile` entity, DAO, profile accumulation in `AiAnalysisWorker`
Step 6, confidence adjustment in `NotificationClassifier`, suggestion calibration in
`SuggestionGenerator`) is fully in the codebase.

Phase 2 (dual LLM backend): `LlamaEngine` and `LlamaCpp` (JNI) are implemented and
used by the Chat tab's `RuleExtractor`. ONNX Tier 1 classification is stubbed — the
`NotificationClassifier` falls through to the heuristic path when ONNX is not loaded.
The 3-tier cascade described in §2.2 (ONNX → llama.cpp → heuristic) is not active in
production; the heuristic path handles all classification currently.

---

## Context

Lithium's AI pipeline runs every 24 hours via `AiAnalysisWorker`. It classifies
notifications with a heuristic fallback (ONNX Tier 1 is stubbed), then aggregates
patterns over the past 24-hour window only, generates a report, and generates
suggestions using static thresholds (`MIN_NOTIFICATIONS_TO_SUGGEST = 5`,
`SUPPRESS_TAP_RATE_THRESHOLD = 0.05f`, `HIGH_VOLUME_THRESHOLD = 10`).

Two problems with that design:

1. **No memory across cycles.** Every 24 hours the suggestion engine starts cold.
   An app that consistently generates 4 notifications never crosses the threshold of 5
   even after 30 days. An app with a stable 3% tap rate never crosses the suppress
   threshold even after demonstrating it consistently for a month. The user's actual
   behaviour is unknowable.

2. **No model inference.** ONNX Tier 1 is a named stub. The heuristic classifier
   handles easy cases (contacts, 2FA, known social packages) well, but is brittle for
   ambiguous apps and cannot learn from user actions.

This plan addresses both. Phase 1 adds a behavioral profile table and threads it into
classification, confidence scoring, and suggestion generation. Phase 2 wires up real
inference: ONNX (DistilBERT/MobileBERT) and llama.cpp (GGUF), with a selection and
fallback strategy.

---

## Phase 1: Behavioral Learning Table + Confidence Adjustment

### 1.1 - AppBehaviorProfile Entity Design

**New table:** `app_behavior_profiles`

**Rationale for granularity:** The profile is keyed on `(packageName, channelId)`, not
just `packageName`. Channel is the correct grain: Instagram's "direct messages" channel
is genuinely personal; its "suggested posts" channel is ENGAGEMENT_BAIT. A single
per-package profile would collapse these into noise and produce worse suggestions than
the current 24h window.

`channelId` is nullable on `NotificationRecord` (some apps omit it). The profile uses
`""` (empty string) as a sentinel for "no channel declared", which is a valid and
common case.

**Table schema:**

```
app_behavior_profiles
id                        INTEGER PRIMARY KEY AUTOINCREMENT
package_name              TEXT NOT NULL
channel_id                TEXT NOT NULL DEFAULT ''
dominant_category         TEXT NOT NULL DEFAULT 'unknown'
total_received            INTEGER NOT NULL DEFAULT 0
total_tapped              INTEGER NOT NULL DEFAULT 0
total_dismissed           INTEGER NOT NULL DEFAULT 0
total_auto_removed        INTEGER NOT NULL DEFAULT 0
total_sessions            INTEGER NOT NULL DEFAULT 0
total_session_ms          INTEGER NOT NULL DEFAULT 0
category_vote_personal          INTEGER NOT NULL DEFAULT 0
category_vote_engagement_bait   INTEGER NOT NULL DEFAULT 0
category_vote_promotional       INTEGER NOT NULL DEFAULT 0
category_vote_transactional     INTEGER NOT NULL DEFAULT 0
category_vote_system            INTEGER NOT NULL DEFAULT 0
category_vote_social_signal     INTEGER NOT NULL DEFAULT 0
user_reclassified         TEXT                  (nullable)
user_reclassified_at_ms   INTEGER               (nullable)
first_seen_ms             INTEGER NOT NULL
last_seen_ms              INTEGER NOT NULL
last_updated_ms           INTEGER NOT NULL
profile_version           INTEGER NOT NULL DEFAULT 1

UNIQUE(package_name, channel_id)
```

**Field rationale:**

- `total_received / total_tapped / total_dismissed` accumulate across all time. The
  suggestion engine can compute a lifetime tap rate rather than a 24h window rate.
- `total_auto_removed` is tracked separately because auto-cancel (REASON_APP_CANCEL)
  is meaningless for engagement measurement. Merging it into `total_dismissed` would
  inflate the dismiss count with signals the user never generated.
- `category_vote_*` is a denormalised count of how often the classifier assigned each
  label to this (pkg, channel). After N votes, if one category holds more than 60% of
  votes and N exceeds `CATEGORY_LOCK_THRESHOLD` (proposed: 20), the profile's
  `dominant_category` is updated automatically and future confidence is boosted.
- `user_reclassified` is nullable and future-facing. It gates a user-correction UI
  not in scope for this phase, but its presence here avoids a schema migration later.
- `total_sessions / total_session_ms` mirror what `SessionRecord` provides for 24h,
  but retained forever. Used to calibrate suggestion thresholds across the full lifetime.

### 1.2 - When and How the Profile Is Updated

**Decision: update inside `AiAnalysisWorker`, not in the listener service.**

The listener service (`LithiumNotificationListener`) runs on its own `serviceScope`
and must remain fast. Adding a profile read-modify-write to the hot path would add
two DB round trips per notification arrival. More critically: removal reason is
available in the listener, but AI classification is not — classification happens in
the worker. Updating the profile in the listener would require storing removal reasons
separately and joining to classification later, creating a circular dependency.

The profile is updated in a new Step 6 of `AiAnalysisWorker.doWork()`, after
suggestions are inserted:

```
Step 6: Profile Accumulation
  For each NotificationRecord in the current cycle:
    - Upsert the (packageName, channelId) profile row via a single SQL upsert.
    - Increment total_received.
    - If removalReason == "click": increment total_tapped.
    - If removalReason == "cancel": increment total_dismissed.
    - If removalReason starts with "app_cancel": increment total_auto_removed.
    - Increment the matching category_vote_* column.
    - Update dominant_category if vote lock threshold is met (>60% share, N >= 20).
    - Set last_seen_ms and last_updated_ms.

  Then: for each session in the current window, add session count and duration to
  total_sessions / total_session_ms on the matching profile row.
```

**UPSERT pattern:**

Room 2.6.x supports `@Upsert` but it performs a full row replace, not an increment.
Use a raw `@Query` with SQLite's `INSERT ... ON CONFLICT DO UPDATE` (available from
SQLite 3.24, which the SQLCipher 4.5.x line ships as SQLite 3.39+):

```sql
INSERT INTO app_behavior_profiles (
    package_name, channel_id, total_received, total_tapped, total_dismissed,
    total_auto_removed, category_vote_X, dominant_category,
    first_seen_ms, last_seen_ms, last_updated_ms, profile_version
) VALUES (
    :pkg, :ch, 1, :tapped, :dismissed, :autoRemoved, :voteX, :category,
    :now, :now, :now, 1
)
ON CONFLICT(package_name, channel_id) DO UPDATE SET
    total_received     = total_received + 1,
    total_tapped       = total_tapped + excluded.total_tapped,
    total_dismissed    = total_dismissed + excluded.total_dismissed,
    total_auto_removed = total_auto_removed + excluded.total_auto_removed,
    category_vote_X    = category_vote_X + excluded.category_vote_X,
    last_seen_ms       = excluded.last_seen_ms,
    last_updated_ms    = excluded.last_updated_ms
```

Because each category vote column is different, there will be one DAO method per
category, or one method that takes the column name as a dynamic string (requires a
raw query and careful escaping). The cleanest approach is seven typed DAO methods
(`incrementPersonal`, `incrementEngagementBait`, etc.) — verbose but safe with Room's
annotation processor.

### 1.3 - Confidence Adjustment Logic

When the heuristic classifier returns a result, `NotificationClassifier` consults
the profile to adjust confidence or override the category.

**New overload on `NotificationClassifier`:**

```
fun classify(record, profile: AppBehaviorProfile?): ClassificationResult
```

The existing `fun classify(record)` becomes a convenience overload that passes
`profile = null`, so all existing callers compile without change. The worker passes
the loaded profile.

**Adjustment rules (applied after heuristic Tier 2 or ONNX Tier 1 returns):**

```
Given: heuristicResult, profile (nullable)

If profile == null:
    return heuristicResult   // no adjustment

totalVotes = sum of all category_vote_* columns
If totalVotes < MINIMUM_VOTE_THRESHOLD (10):
    return heuristicResult   // not enough data

// Rule A: User override — absolute priority
If profile.user_reclassified != null:
    return ClassificationResult(profile.user_reclassified, 0.99f)

// Rule B: Category lock — strong behavioral evidence overrides heuristic
If dominant_category holds > CATEGORY_LOCK_PERCENT (60%) of votes
   AND totalVotes >= CATEGORY_LOCK_THRESHOLD (20):
    If heuristicResult.label != profile.dominant_category:
        If totalVotes >= STRONG_EVIDENCE_THRESHOLD (50):
            return ClassificationResult(profile.dominant_category, 0.90f)
        // Conflict: lower heuristic confidence
        return heuristicResult.copy(confidence = heuristicResult.confidence - 0.15f)
    // Agreement: boost confidence
    return heuristicResult.copy(confidence = min(heuristicResult.confidence + 0.10f, 0.99f))

// Rule C: Tap-rate boost for consistently-tapped personal/social content
lifetimeTapRate = profile.total_tapped / profile.total_received
If lifetimeTapRate > TAP_BOOST_THRESHOLD (0.30f)
   AND heuristicResult.label in {PERSONAL, SOCIAL_SIGNAL}:
    return heuristicResult.copy(confidence = min(heuristicResult.confidence + 0.10f, 0.99f))

// Rule D: Dismiss-rate penalty for consistently-dismissed personal content
dismissRate = profile.total_dismissed / profile.total_received
If dismissRate > DISMISS_PENALTY_THRESHOLD (0.70f)
   AND heuristicResult.label == PERSONAL:
    return heuristicResult.copy(confidence = heuristicResult.confidence - 0.20f)

return heuristicResult
```

All thresholds live as named constants in the companion object.

### 1.4 - Per-User Category Reclassification

When `totalVotes >= STRONG_EVIDENCE_THRESHOLD (50)` and the behavioral dominant
category disagrees with the heuristic for more than `RECLASSIFICATION_MAJORITY (70%)`
of votes, the profile's `dominant_category` is updated. This is written by the worker
during profile accumulation (Step 6).

The user sees more accurate suggestions without any configuration UI required.

**Critical guard — never auto-reclassify to PERSONAL:**

```
If newDominant == PERSONAL AND heuristicDominant != PERSONAL:
    // Do not auto-reclassify. Flag for future user-correction UI.
    return
```

PERSONAL is safety-critical. False positives (calling engagement bait "personal") mean
the suggestion engine will never propose action on apps that deserve it.

### 1.5 - Suggestion Calibration

`SuggestionGenerator` currently uses three static thresholds applied to the 24h window.
With behavioral profiles, these become adaptive.

**Changes to `SuggestionGenerator.generate()`:**

Add parameter: `profiles: Map<Pair<String, String>, AppBehaviorProfile>`

For each app in `appStats`:

1. Look up the profile for `(packageName, "")` as a package-level aggregate for V1.
   Per-channel calibration can follow once the data model is proven.

2. **Effective tap rate (blended):**
   ```
   effectiveTapRate = (24hTapRate * RECENT_WEIGHT) + (lifetimeTapRate * LIFETIME_WEIGHT)
   ```
   Proposed weights: `RECENT_WEIGHT = 0.4f`, `LIFETIME_WEIGHT = 0.6f`.
   History is weighted more than today to prevent a single unusually quiet day from
   triggering a suggestion. Both weights are named constants.

3. **Dynamic minimum evidence:**
   ```
   effectiveMinCount = max(MIN_NOTIFICATIONS_TO_SUGGEST, profile.total_received / 30)
   ```
   For a high-volume app (100/day), this requires more than 5 in a single cycle.
   For a low-volume app (1/week), it falls back to the existing minimum.

4. **Lifetime suppress gate:** Only suggest suppress if
   `effectiveTapRate < SUPPRESS_TAP_RATE_THRESHOLD` AND
   `profile.total_received >= LIFETIME_SUPPRESS_THRESHOLD (30)`.
   This prevents suppress suggestions based on two days of data.

5. **Rationale text update:** When the blended rate drives the suggestion, the rationale
   should say "Over your history with this app..." rather than "In the past 24 hours...".

---

## Phase 2: Dual LLM Backend (ONNX + llama.cpp)

### Architecture Overview

The current stub is two-tier: ONNX (Tier 1, stub) then heuristic (Tier 2, active).
The proposal adds a third option (llama.cpp GGUF). Final architecture:

```
Tier 1: ONNX small classification model (DistilBERT or MobileBERT, ~22MB INT8)
Tier 2: llama.cpp via JNI (SmolLM-135M or Qwen2-0.5B GGUF, few-shot classification)
Tier 3: Heuristic (always available, always the final fallback)
```

Selection runs once at worker startup, not per-notification:

```
if AiEngine.isModelLoaded():        use ONNX for this batch
else if LlamaEngine.isModelLoaded(): use llama.cpp (cap batch to 100 notifications)
else:                                use heuristic
```

Within a batch run, backends are not mixed per-notification. Consistency within a
report cycle matters more than per-record accuracy.

### 2.1 - ONNX Path: Real Inference

**Model choice:** DistilBERT-base or MobileBERT fine-tuned for 6-class notification
text classification, exported via HuggingFace `optimum` with `--task text-classification`.
Target: INT8-quantised, ~22MB, tokenizer baked into the ONNX graph if the export
pipeline supports it.

**Model storage:** `context.filesDir/models/classification_v1.onnx` — app-private
storage, not bundled in the APK. Sideloaded via `adb push` for the Pixel trial.
No INTERNET permission required in the main app.

**Inference loop (replaces the null-returning stub in `AiEngine.classify()`):**

```
1. Tokenize the prompt string using either:
   a. Baked tokenizer ops in the ONNX graph (string input tensor), or
   b. BpeTokenizer.kt (reads assets/tokenizer.json, builds vocab map)
2. Truncate to MAX_SEQ_LEN = 128 tokens (notifications are under 100 tokens)
3. Pad to MAX_SEQ_LEN with 0s
4. Build input_ids tensor: int64[1, MAX_SEQ_LEN]
   Build attention_mask tensor: int64[1, MAX_SEQ_LEN]
5. session.run(inputs) via reflection (retaining the existing reflective approach)
6. Extract float32 logits from output OnnxValue via reflection
7. Apply softmax
8. argmax -> category index -> NotificationCategory.entries[index].label
9. Return ClassificationResult(label, softmax[argmax])
```

**`AiModule` addition:**

A named string binding for the model file path, injected into `AiEngine` so the path
is not hardcoded inside the engine itself.

**Worker lifecycle:** `aiEngine.loadModel(modelPath)` at the top of `doWork()`.
`aiEngine.releaseModel()` at the end. The session is held only for the duration of one
worker invocation.

### 2.2 - llama.cpp Path: GGUF via JNI

**Why generative over a second classifier:**

A small GGUF model (SmolLM-135M at ~270MB Q4, Qwen2-0.5B at ~350MB Q4) handles
few-shot classification via prompt engineering, requiring no fine-tuning — only a
well-structured prompt. It also addresses the report generation use case flagged in
PLAN.md section M4 (Approach A, deferred as too complex at the time). One binary
handles both tasks.

**JNI layer design:**

New directory: `app/src/main/cpp/` containing:

```
CMakeLists.txt
llama_jni.cpp       -- JNI bridge (new)
vendor/llama.cpp/   -- vendored at a specific tagged release
```

`app/build.gradle.kts` gains an `externalNativeBuild { cmake { ... } }` block and
`defaultConfig { ndk { abiFilters += "arm64-v8a" } }` for the Pixel trial.

**Kotlin JNI interface:**

```kotlin
object LlamaCpp {
    init { System.loadLibrary("llama_jni") }
    external fun loadModel(modelPath: String): Long   // handle or 0 on failure
    external fun runInference(handle: Long, prompt: String, maxTokens: Int): String
    external fun freeModel(handle: Long)
}
```

**Prompt format for classification:**

```
[SYSTEM] You are a notification classifier. Reply with exactly one category name.
Categories: personal, engagement_bait, promotional, transactional, system,
social_signal, unknown

[USER]
[APP: com.instagram.android] [CHANNEL: direct_v2] [TITLE: Alice] [TEXT: Hey!]

[ASSISTANT]
```

Parse output defensively: strip whitespace, lowercase, pass through
`NotificationCategory.fromLabel()`, default to UNKNOWN on parse failure.

**Model storage:** `context.filesDir/models/llm_v1.gguf` — same sideload strategy as
the ONNX model. The two model files coexist in the same directory.

**llama.cpp is the higher-risk item in this plan** (see Risks). It belongs in a
separate implementation milestone, after the ONNX path is proven stable.

### 2.3 - WorkManager Timeout Consideration

ONNX (DistilBERT, ~10ms per notification) processes 500 records in ~5 seconds.
llama.cpp (~300ms per notification on Pixel 8 Pro, estimated) processes 100 records
in ~30 seconds. Neither exceeds WorkManager's default 10-minute timeout, but llama.cpp
batch must be explicitly capped at 100. Log elapsed time per step to catch regressions.

---

## Approach Evaluation

### Phase 1: Profile Update Timing

**Option A (Recommended): Update in `AiAnalysisWorker` (Step 6)**
- Pro: No listener changes. No added latency on notification arrival. All data available
  in one place: classified records and sessions are already in scope at Step 6. Consistent
  with the existing worker architecture.
- Con: Profile is stale by up to 24 hours. A user who changes behaviour mid-day is not
  reflected until the next worker run.
- Risk: Low. The 24h lag is acceptable for behavioral learning, which operates on
  weeks-scale patterns.

**Option B: Update in listener service on each removal**
- Pro: Profile is always current.
- Con: Adds two DB round trips to the hot path of every notification removal. The
  listener is already running remove + session recording async. Classification is
  unavailable in the listener (it happens in the worker), so profile updates would
  need to be re-joined with classification results later. Circular dependency.
- Risk: Medium. OEM battery management can kill the listener between posting and removal,
  causing profile updates to be dropped silently.

**Option C: Separate periodic worker (every 6 hours)**
- Pro: Decouples profile maintenance from the AI analysis cycle.
- Con: Three workers for what is fundamentally arithmetic over data already in the DB.
  Unnecessary operational complexity.

**Verdict: Option A.** The listener must not be burdened with classification-dependent
writes it cannot perform correctly. All information needed for profile accumulation is
available at Step 6 of the existing worker.

### Phase 2: ONNX vs llama.cpp

**ONNX (Option B) — Classification only, lower risk**
- Pro: Runtime is already a declared dependency. `AiEngine` is architected to receive it
  (session lifecycle, reflective instantiation already in place). Fine-tuned DistilBERT
  produces ~90% accuracy on narrow text classification tasks. Inference is ~10ms per
  record. No native code changes required.
- Con: Requires a fine-tuned model (must be sourced or trained — not available
  off-the-shelf for this exact 7-class notification schema). Addresses classification
  only; does not help with report generation.
- Risk: Medium. Model sourcing or training is the primary unknown.

**llama.cpp (Option C) — Generative, higher power, higher risk**
- Pro: Few-shot classification needs no fine-tuning — prompt engineering suffices.
  Handles novel apps the heuristic does not recognise. Can generate natural-language
  report summaries (the PLAN.md M4 Approach A that was deferred). Publicly available
  GGUF models (SmolLM-135M, Qwen2-0.5B on HuggingFace).
- Con: JNI integration is significant build complexity (CMake, vendored C++, per-ABI
  compilation). Inference latency ~300ms per notification. llama.cpp API surface changes
  frequently. Quality of structured output from a 135M-500M parameter model is unproven
  for this use case.
- Risk: High. JNI build setup, llama.cpp API stability, and inference quality at small
  model sizes are all uncertain.

**Verdict: Implement ONNX first. Design llama.cpp as an opt-in upgrade path in a
separate milestone.** ONNX addresses the immediate gap (real inference instead of a
stub) with the lowest incremental risk since the runtime is already declared and
`AiEngine` is pre-architected for it.

---

## Implementation Steps

### Phase 1 (Behavioral Learning) — ordered

**Step 1.1 — Create `AppBehaviorProfile` entity**

File to create:
`data/model/AppBehaviorProfile.kt`

Room `@Entity`, `@PrimaryKey(autoGenerate = true)`. Enforce uniqueness via:
`@Entity(tableName = "app_behavior_profiles", indices = [Index(value = ["package_name", "channel_id"], unique = true)])`
All fields as described in Section 1.1.

**Step 1.2 — Create `AppBehaviorProfileDao`**

File to create:
`data/db/AppBehaviorProfileDao.kt`

Required methods:
- `suspend fun incrementStats(pkg, channel, tapped, dismissed, autoRemoved, categoryLabel, nowMs)` — raw `@Query` with `INSERT ... ON CONFLICT DO UPDATE`
- `suspend fun getProfile(pkg, channel): AppBehaviorProfile?`
- `suspend fun getAllProfiles(): List<AppBehaviorProfile>`
- `suspend fun getProfilesForPackage(pkg): List<AppBehaviorProfile>`
- `suspend fun updateDominantCategory(pkg, channel, category, nowMs)`
- `suspend fun setUserReclassification(pkg, channel, category, nowMs)`
- `suspend fun addSessionStats(pkg, channel, sessionCount, sessionMs, nowMs)` — separate upsert for session accumulation
- `suspend fun deleteAll()`

**Step 1.3 — Register in `LithiumDatabase`**

File to modify:
`data/db/LithiumDatabase.kt`

- Add `AppBehaviorProfile::class` to `entities` list
- Add `abstract fun behaviorProfileDao(): AppBehaviorProfileDao`
- Bump `version = 3`

**Step 1.4 — Add Migration 2->3 in `DatabaseModule`**

File to modify:
`di/DatabaseModule.kt`

- Add `MIGRATION_2_3` object with the full `CREATE TABLE` and `CREATE UNIQUE INDEX` DDL
- Add it to `addMigrations(MIGRATION_1_2, MIGRATION_2_3)`
- Add `@Provides fun provideBehaviorProfileDao(db: LithiumDatabase): AppBehaviorProfileDao`

**Step 1.5 — Create `BehaviorProfileRepository`**

File to create:
`data/repository/BehaviorProfileRepository.kt`

`@Singleton @Inject constructor(private val dao: AppBehaviorProfileDao)`

Exposes:
- `suspend fun recordNotification(record: NotificationRecord, classificationLabel: String, nowMs: Long)` — resolves tap/dismiss from `record.removalReason`, calls DAO
- `suspend fun recordSession(packageName: String, durationMs: Long, nowMs: Long)` — calls `dao.addSessionStats` with `channelId = ""`
- `suspend fun getProfileMap(): Map<Pair<String, String>, AppBehaviorProfile>`

**Step 1.6 — Add profile accumulation to `AiAnalysisWorker` (Step 6)**

File to modify:
`ai/AiAnalysisWorker.kt`

- Inject `BehaviorProfileRepository` in `@AssistedInject constructor`
- After Step 5 (data retention), add Step 6:
  - For each record from the current cycle's `byCategory.values.flatten()`:
    call `behaviorProfileRepository.recordNotification(record, classification, now)`
  - For each session from `sessionDao.getSessionsSince(since)`:
    call `behaviorProfileRepository.recordSession(session.packageName, session.durationMs ?: 0L, now)`
  - Run dominance check: for each profile updated this cycle, check vote lock threshold,
    call `dao.updateDominantCategory()` if met

**Step 1.7 — Thread profile into `NotificationClassifier`**

File to modify:
`ai/NotificationClassifier.kt`

- Add `fun classify(record: NotificationRecord, profile: AppBehaviorProfile?): ClassificationResult`
- Existing `fun classify(record)` delegates to `classify(record, null)` — no caller changes
- Add private `fun applyBehavioralAdjustment(result: ClassificationResult, profile: AppBehaviorProfile): ClassificationResult` implementing Section 1.3 rules
- All thresholds as named constants in companion object

**Step 1.8 — Pass profiles during classification in worker**

File to modify:
`ai/AiAnalysisWorker.kt`

- Before Step 1, load all profiles: `val profiles = behaviorProfileRepository.getProfileMap()`
- In the classification loop: call `classifier.classify(record, profiles[Pair(record.packageName, record.channelId ?: "")])`

**Step 1.9 — Update `SuggestionGenerator`**

File to modify:
`ai/SuggestionGenerator.kt`

- Add parameter `profiles: Map<Pair<String, String>, AppBehaviorProfile>` to `generate()`
- Add private `fun computeEffectiveTapRate(stats: AppStats, profile: AppBehaviorProfile?): Float`
- Replace hardcoded threshold comparisons with `computeEffectiveTapRate` calls
- Add `RECENT_WEIGHT`, `LIFETIME_WEIGHT`, `LIFETIME_SUPPRESS_THRESHOLD` constants
- Update worker call site to pass `profiles` to `suggestionGenerator.generate()`

---

### Phase 2 (ONNX Real Inference) — ordered, after Phase 1 is stable

**Step 2.1 — Source or train the classification model**

- Export DistilBERT or MobileBERT fine-tuned for 7-class notification classification
  using HuggingFace `optimum` with `--task text-classification`
- Target: INT8-quantised, ~22MB, tokenizer baked into graph if pipeline supports it
- Document model provenance in `models/README.md` with SHA-256

**Step 2.2 — Implement `BpeTokenizer` (if tokenizer not baked into model)**

File to create (conditional):
`ai/BpeTokenizer.kt`

- Reads `assets/tokenizer.json` on first use
- `fun tokenize(text: String, maxLen: Int): LongArray` — padded int64 array
- `fun buildAttentionMask(tokenIds: LongArray): LongArray` — 1 for real, 0 for pad

**Step 2.3 — Wire real ONNX inference in `AiEngine`**

File to modify:
`ai/AiEngine.kt`

- Replace the null-returning stub in `classify()` with the inference loop from Section 2.1
- Retain the reflective approach (keeps file compilable without runtime on classpath)
- Add `MAX_SEQ_LEN = 128` constant

**Step 2.4 — Update `AiModule` to provide model path**

File to modify:
`di/AiModule.kt`

- Add `@Provides @Named("classificationModelPath") fun provideModelPath(@ApplicationContext ctx: Context): String`
- Inject into `AiEngine` constructor with `@Named("classificationModelPath")`

**Step 2.5 — Call `loadModel()` from worker startup**

File to modify:
`ai/AiAnalysisWorker.kt`

- At top of `doWork()`: `aiEngine.loadModel(modelPath)`
- At end of `doWork()`: `aiEngine.releaseModel()`
- Log whether model was available

---

### Phase 2 (llama.cpp — separate milestone, after ONNX proven)

**Step 2.6 — Add CMake build to app module**

Files to create:
`app/src/main/cpp/CMakeLists.txt`
`app/src/main/cpp/llama_jni.cpp`
`app/src/main/cpp/vendor/llama.cpp/` (vendored at a specific tagged release)

File to modify:
`app/build.gradle.kts`
- Add `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }`
- Add `defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }`

**Step 2.7 — Implement Kotlin JNI wrapper**

File to create:
`ai/LlamaCpp.kt` — `object` with `System.loadLibrary("llama_jni")` and three `external` declarations

**Step 2.8 — Implement `LlamaEngine`**

File to create:
`ai/LlamaEngine.kt`

`@Singleton @Inject constructor()`, same lifecycle pattern as `AiEngine`.
`fun classify(prompt: String): ClassificationResult?` — wraps `LlamaCpp.runInference`,
parses output string to `NotificationCategory`, returns null on parse failure or when
no model is loaded.

**Step 2.9 — Insert llama.cpp as Tier 2 in `NotificationClassifier`**

File to modify:
`ai/NotificationClassifier.kt`

- Inject `LlamaEngine` alongside `AiEngine`
- Tier order: ONNX -> llama.cpp -> heuristic

---

## Files Affected

### Phase 1

| Action | File path (relative to `app/src/main/java/ai/talkingrock/lithium/`) |
|--------|----------------------------------------------------------------------|
| Create | `data/model/AppBehaviorProfile.kt` |
| Create | `data/db/AppBehaviorProfileDao.kt` |
| Create | `data/repository/BehaviorProfileRepository.kt` |
| Modify | `data/db/LithiumDatabase.kt` — add entity, DAO method, version 3 |
| Modify | `di/DatabaseModule.kt` — add MIGRATION_2_3, provide DAO |
| Modify | `ai/AiAnalysisWorker.kt` — inject repository, Step 6, pass profiles |
| Modify | `ai/NotificationClassifier.kt` — profile overload, adjustment logic |
| Modify | `ai/SuggestionGenerator.kt` — profiles param, blended tap rate |

### Phase 2 (ONNX)

| Action | File path |
|--------|-----------|
| Create | `ai/BpeTokenizer.kt` (conditional on model export) |
| Create | `app/src/main/assets/tokenizer.json` (if tokenizer not baked) |
| Create | `models/README.md` (model provenance) |
| Modify | `ai/AiEngine.kt` — real inference loop |
| Modify | `di/AiModule.kt` — model path provider |
| Modify | `ai/AiAnalysisWorker.kt` — loadModel/releaseModel lifecycle |

### Phase 2 (llama.cpp)

| Action | File path |
|--------|-----------|
| Create | `app/src/main/cpp/CMakeLists.txt` |
| Create | `app/src/main/cpp/llama_jni.cpp` |
| Create | `app/src/main/cpp/vendor/llama.cpp/` |
| Create | `ai/LlamaCpp.kt` |
| Create | `ai/LlamaEngine.kt` |
| Modify | `app/build.gradle.kts` — externalNativeBuild, ndk.abiFilters |
| Modify | `ai/NotificationClassifier.kt` — inject LlamaEngine, Tier 2 |
| Modify | `di/AiModule.kt` — LlamaEngine binding if needed |

---

## Risks and Mitigations

### Risk 1: SQLite upsert syntax availability in SQLCipher

`ON CONFLICT DO UPDATE` requires SQLite 3.24 (2018). Android API 29 (minSdk) bundles
SQLite 3.28, but SQLCipher bundles its own SQLite. The 4.5.x line ships SQLite 3.39+,
so this should be safe — but verify against the release notes before implementing the
DAO. If it fails, decompose the upsert into a read-then-conditional-write (two round
trips per notification instead of one).

### Risk 2: Database migration on a device with live data

The trial device has a live `lithium.db` at version 2. A migration failure crashes
on startup by design (no `fallbackToDestructiveMigration()`). The migration SQL for
version 3 is purely additive — only a new table and index. Test the migration on
a fresh install and on a version-2 upgrade before deploying to the trial device.

Migration DDL (exact):
```sql
CREATE TABLE IF NOT EXISTS app_behavior_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    channel_id TEXT NOT NULL DEFAULT '',
    dominant_category TEXT NOT NULL DEFAULT 'unknown',
    total_received INTEGER NOT NULL DEFAULT 0,
    total_tapped INTEGER NOT NULL DEFAULT 0,
    total_dismissed INTEGER NOT NULL DEFAULT 0,
    total_auto_removed INTEGER NOT NULL DEFAULT 0,
    total_sessions INTEGER NOT NULL DEFAULT 0,
    total_session_ms INTEGER NOT NULL DEFAULT 0,
    category_vote_personal INTEGER NOT NULL DEFAULT 0,
    category_vote_engagement_bait INTEGER NOT NULL DEFAULT 0,
    category_vote_promotional INTEGER NOT NULL DEFAULT 0,
    category_vote_transactional INTEGER NOT NULL DEFAULT 0,
    category_vote_system INTEGER NOT NULL DEFAULT 0,
    category_vote_social_signal INTEGER NOT NULL DEFAULT 0,
    user_reclassified TEXT,
    user_reclassified_at_ms INTEGER,
    first_seen_ms INTEGER NOT NULL DEFAULT 0,
    last_seen_ms INTEGER NOT NULL DEFAULT 0,
    last_updated_ms INTEGER NOT NULL DEFAULT 0,
    profile_version INTEGER NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_pkg_channel
    ON app_behavior_profiles(package_name, channel_id);
```

### Risk 3: Null `removalReason` from listener restart gaps

`removalReason` is set in `onNotificationRemoved` via `keyToRowId`. If the listener
was restarted between posting and removal, `keyToRowId` loses the mapping and
`updateRemoval()` is never called — `removalReason` stays null.

In `BehaviorProfileRepository.recordNotification()`, treat null `removalReason` as
neither tap nor dismiss: do not increment either counter. This underestimates
engagement slightly but avoids false signals. Document this as a known approximation.

### Risk 4: ONNX model not available at trial time

The heuristic remains the active classifier if no model file is present (the fallback
chain is intact by design). Phase 2 changes are purely additive — the heuristic path
is untouched. No user-visible regression.

### Risk 5: llama.cpp JNI build complexity and API instability

The llama.cpp C++ source changes frequently. Vendoring a snapshot introduces the risk
of stale fixes; submodules introduce build-time network dependencies.

Mitigation: vendor a specific tagged release and document the tag. Add an update
procedure to `models/README.md`. Treat llama.cpp as a separate git branch until the
build is proven stable on the target NDK version.

### Risk 6: Blended tap rate formula inertia

The 0.6/0.4 weighting (lifetime vs recent) means a user who genuinely changes
behaviour will see that change reflected slowly in suggestions. Both weights are named
constants (`LIFETIME_TAP_RATE_WEIGHT`, `RECENT_TAP_RATE_WEIGHT`). After the Pixel
trial, review suggestion acceptance rates and adjust if suggestions lag actual
behaviour.

### Risk 7: Session-to-channel attribution gap

`SessionRecord` tracks `packageName` but not `channelId`. When accumulating session
stats into the behavioral profile, sessions are attributed to the package-level profile
`(packageName, "")`. This means session engagement cannot be differentiated by channel.
This is acceptable for V1 (per-package engagement data is still more informative than
nothing), but it limits the value of the per-channel profile for apps where the relevant
signal is "this channel is high-engagement, that channel is not".

Mitigation: document the attribution gap as a known limitation. Future work: store
`lastChannelId` on `SessionRecord` if the listener tracks which notification was tapped.

---

## Testing Strategy

### Phase 1 Tests

**`NotificationClassifier` behavioral adjustment (unit):**

- Given profile with 30 total votes, 25 of which are PROMOTIONAL, heuristic returns
  SOCIAL_SIGNAL at 0.65f: assert returned category is PROMOTIONAL at 0.90f (strong
  evidence override).
- Given profile with `total_tapped=8`, `total_received=20` (40% tap rate), heuristic
  returns PERSONAL at 0.60f: assert confidence boosted to 0.70f.
- Given null profile: assert result is identical to heuristic output.
- Given `user_reclassified = "transactional"`: assert result is always TRANSACTIONAL
  at 0.99f regardless of heuristic.
- Guard test: given profile with behavioral dominant_category PERSONAL, heuristic
  returns ENGAGEMENT_BAIT: assert PERSONAL auto-reclassification does NOT occur.

**`SuggestionGenerator` with profiles (unit):**

- Given app with 24h `totalCount=3` (below `MIN=5`) but lifetime `total_received=45`
  and low lifetime tap rate: assert a suggestion is generated (lifetime overrides
  24h minimum).
- Given two-cycle blended rate scenario: assert effective tap rate is between the 24h
  rate and lifetime rate, weighted approximately 40/60.
- Given `total_received=15` (below `LIFETIME_SUPPRESS_THRESHOLD=30`): assert suppress
  suggestion is NOT generated even when tap rate is 0%.

**`BehaviorProfileRepository` (unit/integration):**

- Insert 5 notification records with known removal reasons. Call `recordNotification`
  for each. Assert `total_received = 5`, `total_tapped` matches click records,
  `total_dismissed` matches cancel records.
- Call `recordNotification` with null `removalReason`. Assert neither `total_tapped`
  nor `total_dismissed` is incremented.
- Call twice for the same `(packageName, channelId)`. Assert `total_received = 2`
  (upsert increments, does not replace).

**Migration test:**

- Use `Room.inMemoryDatabaseBuilder` with `addMigrations(MIGRATION_2_3)` applied to
  a version-2 schema. Assert `app_behavior_profiles` table exists after migration.
  Assert the unique index is present. Assert existing tables (notifications, sessions,
  etc.) are unaffected.

### Phase 2 Tests (ONNX)

**`AiEngine` (unit):**

- Mock `OrtSession` via reflection. Assert `classify()` returns non-null when session
  is loaded.
- Assert `classify()` returns null when no session is loaded (heuristic fallthrough).
- Assert `releaseModel()` clears both `ortSession` and `ortEnvironment` to null.

**`BpeTokenizer` (unit, if implemented):**

- Tokenize a known 10-word string with `maxLen=128`. Assert output length equals 128.
- Assert attention mask has 1s for real tokens and 0s for padding positions.
- Assert truncation at `maxLen` does not crash.

**End-to-end smoke test (instrumented, if test model bundled in assets):**

- Load model via `AiEngine.loadModel()`. Run `classify()` on a known prompt. Assert
  result is non-null and label is one of the 7 valid category strings.

---

## Definition of Done

### Phase 1

- [ ] `AppBehaviorProfile` entity compiles and is registered in `LithiumDatabase` version 3
- [ ] `AppBehaviorProfileDao` upsert verified against SQLCipher SQLite version (3.24+ required)
- [ ] `MIGRATION_2_3` tested on a version-2 database; app starts without crash after upgrade
- [ ] `AiAnalysisWorker.doWork()` includes Step 6 and executes without error in a full run
- [ ] After one full 24h worker run on the trial device, `app_behavior_profiles` rows are present
- [ ] `NotificationClassifier.classify(record, null)` produces identical results to existing `classify(record)`
- [ ] All behavioral adjustment thresholds are named constants in the companion object (zero magic numbers)
- [ ] `SuggestionGenerator` accepts profiles parameter; rationale text references lifetime data when used
- [ ] `AppBehaviorProfileDao.deleteAll()` is wired into the purge-all-data flow
- [ ] All Phase 1 unit tests written and passing

### Phase 2 (ONNX)

- [ ] Model file sourced, provenance documented in `models/README.md` with SHA-256
- [ ] `AiEngine.classify()` returns non-null result when model file is present at expected path
- [ ] `AiEngine.classify()` returns null (falls to heuristic) when model file is absent
- [ ] ONNX inference produces correct category for at least 5 manually-verified test cases
- [ ] Worker loads model at `doWork()` start and releases it at `doWork()` end
- [ ] No ANR or OOM on Pixel 8 Pro with 500-notification batch under charging+idle constraints
- [ ] All Phase 2 ONNX unit tests written and passing

### Phase 2 (llama.cpp)

- [ ] `arm64-v8a` `.so` builds without error via Gradle on the CI/build machine
- [ ] `LlamaCpp.loadModel()` returns a non-zero handle for a valid GGUF file
- [ ] `LlamaCpp.runInference()` returns a string containing one of the 7 category names for a known prompt
- [ ] Parse failure falls through to heuristic without crash
- [ ] Worker llama.cpp batch is explicitly capped at 100; elapsed time logged per step
- [ ] Model file is NOT bundled in the APK; requires sideload or download

---

## Confidence Assessment

**Phase 1 (Behavioral Learning): High.**
The architecture is an extension of the existing worker pipeline. The data model is
straightforward. The SQLite upsert pattern is standard and the SQLCipher version
supports it. The primary uncertainty is the blended tap rate formula weights (0.6/0.4)
— these are reasonable initial values that need real-world validation against actual
suggestion acceptance rates after the Pixel trial.

**Phase 2 ONNX: Medium.**
The runtime is already in the dependency tree and `AiEngine` is pre-architected for it.
The blocking unknown is model sourcing: a fine-tuned model for this exact 7-class
notification schema must be found or trained. If training is out of scope, the path
depends on finding a suitable off-the-shelf model. If the model is available, the
wiring is low-risk.

**Phase 2 llama.cpp: Medium-Low.**
JNI C++ build integration is always environment-sensitive. The Android NDK toolchain,
ABI targeting, and llama.cpp API surface have historically caused integration friction.
Quality of structured output from a 135M-500M parameter model for this use case is
unproven. Budget 2-3x the estimated implementation time.

## Assumptions Requiring Validation Before Implementation

1. **SQLCipher 4.5.6 ships SQLite >= 3.24.** Verify in the SQLCipher release notes
   before implementing the upsert DAO. If false, the upsert becomes a read-then-write.

2. **`removalReason` is populated for the majority of notification records on the
   trial device.** Check the debug notification log screen for null ratio in existing
   data before relying on it for engagement metrics.

3. **`channelId` is non-null for the majority of notifications on the trial device.**
   If most notifications have a null channelId, the `(packageName, "")` sentinel will
   collapse all channels into one profile per package — still useful, but coarser.

4. **`optimum` ONNX export can bake the tokenizer into the graph.** If it cannot,
   `BpeTokenizer.kt` is required — add ~200 LOC of vocabulary-loading and BPE merge
   logic to the ONNX implementation scope.

5. **The Pixel 8 Pro trial device has ADB access for model sideloading.** If the trial
   moves to non-developer devices, a Settings-screen download mechanism becomes required
   before Phase 2 can ship.
