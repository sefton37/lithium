# Plan: Implicit Pairwise Preference Signals

**Status: Implemented with two known deviations from this plan.**

The `implicit_judgments` table (DB v12), `ImplicitJudgmentDao`, capture in
`LithiumNotificationListener`, and integration into `ScoringRefit` are all in place.

**Known deviation 1 — `screenWasOn` hardcoded to `true`:**
The plan (§2a) requires calling `PowerManager.isInteractive` synchronously on the
main thread to populate `screenWasOn`. The implementation in `captureImplicit`
hardcodes `screenWasOn = true` for every row. Off-screen dismissals are not
distinguished from on-screen ones. `ScoringRefit` filters on `screen_was_on = 0`
but no rows will ever carry that value in the current build. The rank-delta correction
and `Prefs.REFIT_LAST_IMPLICIT_COUNT` diagnostic logging are present; the behavior
bias fade-out in `Scorer` is in place.

**Known deviation 2 — `TAP_OVER_PEER` cascade path not exercised on device:**
The cascade rank gate (only peers with `rank < removedRank` for `REASON_CLICK`) is
implemented in code, but the filter relies on `rankingMap.rankOrMax` returning valid
ranks. This has not been verified on a physical device. If `rankingMap` returns
`Int.MAX_VALUE` for most peers, `TAP_OVER_PEER` rows will always be empty.

**Known deviation 3 — `ScoringRefit` never ran with real implicit data:**
The debounce gate in `ScoringRefit.refit()` checks `trainingJudgmentDao.count()` for
new *explicit* judgments (threshold: 10). Implicit judgments do not count toward this
gate. If no explicit training has been done, `ScoringRefit` never runs and implicit
data never feeds the Elo or category weight paths, regardless of how many implicit
rows exist. The 24h cadence of `AiAnalysisWorker` is the only other trigger.

---

## Goal

Capture real notification-shade behavior — taps and user-dismissals — as weak
pairwise preference signals, store them in a dedicated table, and fold them into
the existing `ScoringRefit` pipeline (Elo replay + category weight fit) so the
scorer improves without requiring any deliberate training gesture from the user.

This plan is a downstream extension of `PLAN_SCORING_MODEL.md` Phase C
(`ScoringRefit`). It assumes schema v11 (channel_rankings + score_quantiles),
`Scorer`, `TierMapper`, and `ScoringRefit` are all already in place.

## Non-Goals

- No new UI (no "training from behavior" screen or indicators).
- No changes to explicit `TrainingJudgment` logic or XP/quest system.
- No dwell-time (`DWELL`) signal in this plan — schema reserves the slot; capture
  is deferred.
- No cross-device sync or cloud upload of implicit signals.
- No changes to `TierClassifier` rule paths.
- No per-notification position-bias modeling beyond what the regression handles.

---

## Approach

### §1 — `implicit_judgments` Table

**Schema** (new Room entity, v12):

```sql
CREATE TABLE IF NOT EXISTS implicit_judgments (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    kind                    TEXT    NOT NULL,
    winner_notification_id  INTEGER NOT NULL,
    loser_notification_id   INTEGER NOT NULL,
    winner_package          TEXT    NOT NULL,
    winner_channel_id       TEXT    NOT NULL,
    loser_package           TEXT    NOT NULL,
    loser_channel_id        TEXT    NOT NULL,
    winner_rank             INTEGER NOT NULL,
    loser_rank              INTEGER NOT NULL,
    cohort_size             INTEGER NOT NULL,
    screen_was_on           INTEGER NOT NULL,
    created_at_ms           INTEGER NOT NULL
)
```

`kind` values: `TAP_OVER_PEER`, `PEER_OVER_DISMISSED`, `DWELL` (reserved).

**Why this shape:**

- `winner_package` / `winner_channel_id` and their loser equivalents are
  denormalized at capture time. The `notifications` table is hard-deleted by
  retention (`NotificationDao.deleteOlderThan`). `implicit_judgments` must
  outlive the source rows to remain useful for refit — joining to deleted rows
  returns nothing. Capturing pkg/channel at write time costs ~50 bytes per row
  and makes every refit query self-contained.

- Foreign key to `notifications` is intentionally **not declared** (no
  `FOREIGN KEY` constraint). The notification IDs are retained for debugging and
  potential future re-inspection, but they will dangle after retention cleanup.
  That is acceptable; the DAO never joins to `notifications` for refit queries.

- `winner_rank` / `loser_rank` feed the position-bias correction term in the
  logistic regression (§3). Stored as raw shade-order integers so the regression
  can use them directly without re-querying.

- `screen_was_on` stored as INTEGER (0/1) matching Room's Boolean-to-INTEGER
  convention. Rows captured when screen is off are written but excluded from the
  category weight fit (off-screen dismissals are much noisier signals).

**AI classification snapshot:** At capture time, the listener looks up the
`NotificationRecord` for both winner and each loser via `keyToRowId` and records
`aiClassification` and `aiConfidence`. These four columns are added to the final
schema (see §4). They are nullable — classification may not be available yet
from `AiAnalysisWorker`. Rows with null classification still contribute to Elo
replay; they are excluded from the category weight fit.

**Full schema including classification columns** (used in migration SQL):

```sql
CREATE TABLE IF NOT EXISTS implicit_judgments (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    kind                     TEXT    NOT NULL,
    winner_notification_id   INTEGER NOT NULL,
    loser_notification_id    INTEGER NOT NULL,
    winner_package           TEXT    NOT NULL,
    winner_channel_id        TEXT    NOT NULL,
    loser_package            TEXT    NOT NULL,
    loser_channel_id         TEXT    NOT NULL,
    winner_rank              INTEGER NOT NULL,
    loser_rank               INTEGER NOT NULL,
    winner_ai_class          TEXT,
    winner_ai_conf           REAL,
    loser_ai_class           TEXT,
    loser_ai_conf            REAL,
    cohort_size              INTEGER NOT NULL,
    screen_was_on            INTEGER NOT NULL,
    created_at_ms            INTEGER NOT NULL
)
```

**Retention:** implicit_judgments accumulates indefinitely — rows are a few
dozen bytes each and will number in the low thousands even for heavy users. No
retention purge is needed for v12. Revisit if the table exceeds ~50k rows
(see Open Questions §4).

---

### §2 — Capture in `onNotificationRemoved`

**Current state of `onNotificationRemoved`** (lines 260–282 of
`LithiumNotificationListener.kt`):

- Calls `notificationRepo.updateRemoval(rowId, removedAtMs, reasonString)`.
- On `REASON_CLICK`: launches a delayed `usageTracker.measureSessionAfterTap`.
- Does nothing else with peer state.

**Required additions:**

#### 2a. Snapshot peers synchronously on the main thread

`getActiveNotifications()` returns the live shade contents at call time. The
removal callback fires immediately when the user acts; any delay risks the array
reflecting post-removal state. Snapshot it at the **first line** of
`onNotificationRemoved`, before the coroutine launches:

```kotlin
override fun onNotificationRemoved(sbn: StatusBarNotification,
                                   rankingMap: RankingMap, reason: Int) {
    val removedAtMs = System.currentTimeMillis()
    val peers: Array<StatusBarNotification> =
        try { getActiveNotifications() } catch (_: Exception) { emptyArray() }
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    val screenOn: Boolean = pm.isInteractive
    // ... existing rowId lookup, updateRemoval launch, then:
    serviceScope.launch {
        recordImplicitSignals(sbn, rankingMap, reason, peers, screenOn, rowId)
    }
}
```

`PowerManager.isInteractive()` is the correct API for "is the screen on and
accepting input." It is synchronous, main-thread safe, and does not require any
BroadcastReceiver for `ACTION_USER_PRESENT`. Off-screen signals are captured
but flagged (`screen_was_on = 0`).

#### 2b. Gating rules inside `recordImplicitSignals`

`private suspend fun recordImplicitSignals(sbn, rankingMap, reason, peers,
screenOn, rowId)`

Gates before generating any rows:

1. **Package filter:** Skip if `sbn.packageName.startsWith(LITHIUM_PACKAGE_PREFIX)`.
2. **Ongoing filter:** Skip if `sbn.isOngoing`. Ongoing/media/transport dismissals
   are system-housekeeping, not preference.
3. **rowId filter:** Skip if `rowId == null` (notification was never tracked —
   e.g. posted before the listener connected).
4. **Reason filter:** Only `REASON_CLICK` and `REASON_CANCEL` produce rows.
   Everything else returns early. This explicitly excludes:
   - `REASON_APP_CANCEL` / `REASON_APP_CANCEL_ALL` — app withdrew, not user.
   - `REASON_CANCEL_ALL` (Clear All) — ambiguous; see §6.
   - `REASON_LISTENER_CANCEL` — Lithium's own suppression.
   - `REASON_TIMEOUT`, `REASON_SNOOZED`, etc.

#### 2c. Peer filtering

From the raw `peers` snapshot:

- Drop the just-removed notification itself (match by `sbn.key`).
- Drop Lithium's own notifications (`packageName.startsWith(LITHIUM_PACKAGE_PREFIX)`).
- Drop ongoing notifications (`peer.isOngoing`).
- Drop peers not tracked by Lithium: skip if `keyToRowId[peer.key]` is absent
  (suppressed by Lithium or posted before listener connected).
- Cap at `MAX_COHORT_SIZE = 20`. If more than 20 eligible peers remain, sort by
  shade rank ascending (lowest rank = highest in shade) and take the first 20. The
  user plausibly examined the topmost notifications before acting.

Avoid per-peer DB reads here. Rely on `isOngoing` and `keyToRowId` membership
as the primary filters. Accept that a small number of non-ongoing tier-0 rows may
slip through; they produce weak signal at worst.

#### 2d. Rank extraction

```kotlin
fun getRank(key: String, rankingMap: RankingMap): Int {
    val r = Ranking()
    return if (rankingMap.getRanking(key, r)) r.rank else Int.MAX_VALUE
}
```

`Ranking.getRank()` returns the 0-based position in the current shade order.
Lower = higher in shade. Compute for the removed notification and for each peer.
Peers returning `Int.MAX_VALUE` sort to the bottom and are dropped before the
cohort cap applies.

#### 2e. Pairwise row generation

**`REASON_CLICK` → `TAP_OVER_PEER` rows**

Cascade click model (see §3 for rationale): only generate rows against peers
whose rank is **strictly less than** the tapped notification's rank. Peers ranked
below (further down in the shade) were not necessarily examined.

For each qualifying peer:
```
winner = tapped notification
loser  = peer (rank < tapped rank)
kind   = TAP_OVER_PEER
```

**`REASON_CANCEL` → `PEER_OVER_DISMISSED` rows**

The user explicitly dismissed this notification. All remaining filtered peers are
preferable. No cascade constraint — the act of dismissing is unambiguous regardless
of relative rank:
```
winner = peer
loser  = dismissed notification
kind   = PEER_OVER_DISMISSED
```

**AI classification snapshot (both cases):**

For each row being written, look up `keyToRowId` for winner and loser, then call
`notificationDao.getById(rowId)` to retrieve `aiClassification` and `aiConfidence`.
This is a suspend call inside `serviceScope` (IO dispatcher) — acceptable latency.
If `getById` returns null (row aged out between tap and lookup), write the row
with null classification columns.

#### 2f. Batch write

Collect all rows into `List<ImplicitJudgment>` and write via a single
`implicitJudgmentDao.insertAll(rows)` call. Single transaction per event; Room
`@Insert` on a list runs in one SQLite transaction automatically. If the list is
empty (all peers filtered out), skip the insert.

---

### §3 — Position-Bias Correction

**Problem:** Notifications near the top of the shade are seen more often than
those near the bottom. A tap at rank 2 provides strong evidence against peers
at rank 0 and 1 (the user read them and passed). It provides weaker evidence
against peers at rank 8 — the user may not have scrolled there at all.

**Three candidate approaches:**

#### A. Cascade click model (capture-time filtering)

On a tap at rank `r`, only generate `TAP_OVER_PEER` rows for peers at rank `< r`.
On a dismiss, generate `PEER_OVER_DISMISSED` for all peers. No rank-bias term in
the regression.

*Strengths:* Simple. No modeling required. Works at any data volume. Captures
only pairs where examination is probable by construction.

*Weaknesses:* Binary threshold — either a peer is included or not. Throws away
potentially valid signal from peers just below the tap rank.

#### B. Position-based model (PBM) with inverse-propensity weighting

Fit `P(examine | rank)` per shade position from click data, then apply IPW to
each row before the regression: row weight = `1 / P(examine | rank_loser)`.

*Strengths:* Principled; produces calibrated weights.

*Weaknesses:* Requires enough click data per rank position to estimate
`P(examine)` reliably. At typical single-user data volumes (dozens to low
hundreds of tap events), rank-stratified click counts will be sparse. Thin-data
estimates amplify noise. Adds a modeling step that must re-run periodically.
**Not viable at this scale.**

#### C. Learned rank-delta feature in the regression

Add `rank_winner - rank_loser` directly into the logistic regression that fits
category weights. The regression absorbs the position bias coefficient alongside
the category weights. No separate modeling layer; degrades gracefully if the
rank-delta coefficient has no signal.

*Strengths:* Data-efficient; no additional modeling layer.

*Weaknesses:* Conflates rank-bias and category preference in the same regression.
Less interpretable than IPW.

**Recommended approach: A + C.**

Cascade filtering at capture time (A) eliminates the most obvious unexamined
comparisons before any row is written. This keeps the table clean and reduces
noise for all downstream use. Adding `rank_delta = rank_winner - rank_loser` as
a feature in the regression (C) then corrects for residual position bias within
the included pairs. At Lithium's single-user data scale, this is strictly better
than B and more robust than relying on either A or C alone.

**Row contents given the combined model:**

Both `winner_rank` and `loser_rank` are stored as captured. For `TAP_OVER_PEER`
rows, `loser_rank < winner_rank` by construction (cascade gate). For
`PEER_OVER_DISMISSED` rows, `loser_rank` is the dismissed notification's rank;
`winner_rank` is the peer's rank and may be above or below.

**How `ScoringRefit` uses `winner_rank` / `loser_rank`:**

In Step 2 (category weight fit), each implicit row gets a feature vector extended
by one element: `x[n_categories] = winner_rank - loser_rank`. Negative values
mean the winner was higher in the shade. The coefficient on this feature absorbs
the residual position effect. The coefficient is not exposed through
`Scorer.categoryBias` at inference time — it is a fitting artifact only. Store it
separately in `Prefs.RANK_DELTA_WEIGHT` for diagnostic logging.

---

### §4 — Integration into `ScoringRefit`

#### Step 1: Bradley-Terry Elo replay (modified)

The existing `replayElo()` replays `app_battle_judgments` and channel-pair
`training_judgments`. Add a third pass: replay `implicit_judgments` with
`K_IMPLICIT_CHANNEL = 8` (vs. `K_EXPLICIT = 32` for explicit judgments).

Rules for implicit Elo replay:
- Process `implicit_judgments` chronologically after all explicit judgments.
- For each row where packages differ OR channel_ids differ: update
  `ChannelRanking` for both channels using K=8. Also update `AppRanking` if
  packages differ, using K=4 (half of channel K — implicit cross-app preference
  is weaker signal than channel-level).
- **Skip screen-off rows** (`screen_was_on = 0`) for Elo updates. Off-screen
  dismissals may be habitual clearance rather than preference expression.
- For same-`(pkg, channel)` pairs: skip Elo update. Same-channel implicit rows
  carry content preference signal, not channel-level preference; they feed
  Step 2 only.

Add `ImplicitJudgmentDao.getAllChronological(): List<ImplicitJudgment>` for
this pass. The in-memory `RankState` accumulator pattern in `replayElo()` extends
naturally; the implicit pass slots in after the channel-pair pass with its own K
constants defined at the top of `ScoringRefit.companion`.

#### Step 2: Category weight fit (modified)

Add same-`(pkg, channel)` implicit pairs to the logistic regression training set.

Changes to `fitCategoryWeights()`:

1. Extend `TrainingRow` to carry a `weight: Double` field (default 1.0 for
   existing explicit rows).
2. After collecting explicit `rows`, append implicit same-channel rows:
   - Query via `implicitJudgmentDao.getSameChannelChronological()`.
   - Skip rows where `winner_ai_class` or `loser_ai_class` is null.
   - Skip rows where `screen_was_on = 0`.
   - Feature vector `x[k]` = same construction as explicit rows
     (`winnerConf if winnerClass==k else 0.0`) minus loser contribution.
   - Append `x[n_categories] = winner_rank - loser_rank` as last element.
   - Label = 1.0 (winner side always wins). `weight = 0.25`.
3. For explicit rows, also extend the feature vector to `n_categories + 1` but
   set `x[n_categories] = 0.0` (explicit judgments have no rank context). This
   keeps the weight vector shape consistent.
4. Update gradient loop: `grad[k] += err * row.features[k] * row.weight`.
5. Persist the extended weight vector (`n_categories + 1` floats) under
   `Prefs.CATEGORY_WEIGHTS`. The last element is the rank-delta coefficient.
6. `Scorer.categoryBias()` reads only the first `n_categories` elements — rank
   delta is not exposed at inference time.

Add `Prefs.RANK_DELTA_WEIGHT` key for logging the last element separately.

#### Step 3: Quantile recompute

No change. Scores are scores regardless of signal source.

#### Debounce

`ScoringRefit.refit()` currently reads `trainingJudgmentDao.count()` and skips
if fewer than `REFIT_MIN_NEW_JUDGMENTS = 10` new explicit judgments exist since
the last run.

**Decision: implicit judgments do not count toward the debounce trigger.**

Implicit judgments accumulate continuously (any tap or dismiss generates them)
and would trivially satisfy a threshold of 10 every day regardless of whether the
user has trained at all. The 24h cadence of `AiAnalysisWorker` is the primary
rate limit. The judgment-count gate exists to avoid wasted computation when
nothing has changed — implicit signals always change, making the gate meaningless
if they count. Keep the gate explicit-only.

Add `Prefs.REFIT_LAST_IMPLICIT_COUNT` written at end of each refit run, for
diagnostic logging only.

---

### §5 — `AppBehaviorProfile` Interaction

**Current role:** `lifetimeTapRate` and `lifetimeDismissRate` contribute a
behavioral bias `γ · biasBehavior` to the score in `Scorer.score()`. `γ` grows
with `totalSessions`; `MAX_BEHAVIOR_BIAS = 0.15` hard-caps the contribution.

**Risk:** Once implicit signals flow through `ChannelRanking` Elo (which drives
`θ_c` in the scorer), we use tap and dismiss behaviors twice: once through the
Elo path and once through the `AppBehaviorProfile` bias. For heavy users with
many implicit judgments, this causes double-counting.

**Recommended approach: cold-start dominance with fade-out.**

Keep `AppBehaviorProfile` behavioral bias active below a combined signal
threshold, reduce it linearly above. In `Scorer.score()`:

```kotlin
val nImplicit = implicitJudgmentDao.countForChannel(packageName, channelId ?: "")
val nCombined = nC + nImplicit / 10   // implicit counts at 1/10 of explicit weight
val gammaEffective = gamma * (1.0 - (nCombined / 50.0).coerceIn(0.0, 1.0))
// use gammaEffective instead of gamma in logit combination
```

When `nCombined` reaches 50 (equivalent of ~5 explicit judgments or ~500 implicit
events), `gammaEffective → 0` and the behavior bias fades out entirely. The Elo
signal from implicit judgments then dominates cleanly.

Requires one new DAO method:
`ImplicitJudgmentDao.countForChannel(pkg: String, channelId: String): Int`
— counts rows where pkg/channel appears on either winner or loser side.

**Alternative: drop AppBehaviorProfile bias entirely.** Cleaner, eliminates
double-counting. Loses cold-start signal for brand-new packages before any
judgment. Rejected — cold-start matters.

**Alternative: keep it unchanged.** Maximum double-count bias is 0.15 in logit
space given the hard cap. Acceptable as a temporary posture while implicit data
is sparse. Revisit once real volumes are observed. If engineering time is
constrained, this alternative is low-risk and deferrable.

---

### §6 — Risks and Edge Cases

| Risk | Disposition |
|---|---|
| Notification retention deletes `notifications` rows while `implicit_judgments` still references their IDs | Mitigated by design — pkg/channel/classification are denormalized at capture. No refit query joins on `notifications` IDs. Dangling IDs are harmless. No `ON DELETE` constraint declared. |
| `REASON_CANCEL_ALL` (user taps "Clear All") | Ignored entirely. Ambiguous — was the user clearing noise, or acting on preference? No pairwise inference is valid when all peers are simultaneously removed. |
| Ongoing / media / transport notifications | Excluded at source via `sbn.isOngoing` gate on both the removed notification and each peer in `recordImplicitSignals`. These represent system state, not preference. |
| Lithium's own persistent notification appears as a peer | Excluded via `LITHIUM_PACKAGE_PREFIX` check on every peer. |
| Rapid re-post: app dismisses its own notification immediately after user dismiss | `REASON_APP_CANCEL` is in the ignore list; it never reaches `recordImplicitSignals`. A subsequent `REASON_CLICK` on the re-post is an independent gesture — acceptable. |
| `getActiveNotifications()` race: peer posted or removed between callback fire and API call | Window is microseconds. Worst case: removed notification appears in peers (filtered by `sbn.key` match). Mid-callback new arrivals are unobservable noise — acceptable. |
| Screen-off dismissals polluting the dataset | Captured with `screen_was_on = 0`. Excluded from both Elo replay and category weight fit. Retained in DB for potential future analysis. |
| Cohort size explosion on devices with 50+ notifications | Hard cap at `MAX_COHORT_SIZE = 20` after filtering. Cascade model further limits `TAP_OVER_PEER` to peers ranked above the tapped item — typically 0–5 on most shades. |
| `RankingMap.getRanking()` returns false for a peer | Returns `Int.MAX_VALUE` from the rank helper. Sorted to the bottom of the post-filter list; dropped before the cohort cap applies. |
| AI classification null at capture time | Row is written with null `winner_ai_class`/`loser_ai_class`. Contributes to Elo but excluded from category weight fit. Accepted loss; most notifications will be classified by the time the next notification from the same app arrives. |
| `keyToRowId` does not contain an entry for a peer | Means the peer was suppressed by Lithium (`REASON_LISTENER_CANCEL`) or predates the current listener session. Peer is skipped — not a valid training comparison. |
| `REASON_APP_CANCEL` — app withdrew the notification | Already in the ignore list; no rows generated. Worth calling out explicitly because it could be confused with `REASON_CANCEL`. |

---

### §7 — File-by-File Changes

**New files:**

`data/model/ImplicitJudgment.kt`
- Room entity `@Entity(tableName = "implicit_judgments")` with autoGenerate PK.
- 17 columns as specified in §1. `kind` is a plain `String` — no Room TypeConverter
  needed (values are human-readable constants, not an enum).
- Approximately 65 lines.

`data/db/ImplicitJudgmentDao.kt`
- Four methods:
  - `insertAll(judgments: List<ImplicitJudgment>)` — `@Insert`, single transaction
  - `getAllChronological(): List<ImplicitJudgment>` — `ORDER BY created_at_ms ASC`
  - `getSameChannelChronological(): List<ImplicitJudgment>` — WHERE
    `winner_package = loser_package AND winner_channel_id = loser_channel_id`,
    ordered chronologically
  - `countForChannel(pkg: String, channelId: String): Int` — WHERE
    `(winner_package = pkg AND winner_channel_id = channelId) OR
    (loser_package = pkg AND loser_channel_id = channelId)`
- Approximately 50 lines.

**Modified files:**

`data/db/LithiumDatabase.kt`
- Add `ImplicitJudgment::class` to `entities` list.
- Bump `version` from 11 to 12.
- Add `abstract fun implicitJudgmentDao(): ImplicitJudgmentDao`.
- Add version history comment: "- 12: Implicit signals — added implicit_judgments table."
- ~6 lines changed.

`di/DatabaseModule.kt`
- Add `MIGRATION_11_12` object (SQL in §8 below).
- Register in `addMigrations(...)` call.
- Add `@Provides fun provideImplicitJudgmentDao(db: LithiumDatabase): ImplicitJudgmentDao`.
- ~30 lines added.

`service/LithiumNotificationListener.kt`
- Add `@Inject lateinit var implicitJudgmentDao: ImplicitJudgmentDao`.
- Inject or acquire `PowerManager` (via `getSystemService`; no Hilt binding needed).
- Modify `onNotificationRemoved` (line 260): snapshot `peers` array and `screenOn`
  on the main thread before the coroutine launches. Existing `usageTracker` delay
  coroutine is unchanged.
- Add `private suspend fun recordImplicitSignals(sbn, rankingMap, reason, peers,
  screenOn, rowId)` method (~90 lines): all gating, peer filtering, cascade rank
  gate, AI classification lookup, row construction, batch insert.
- ~110 lines net addition.

`ai/scoring/ScoringRefit.kt`
- Add `ImplicitJudgmentDao` constructor parameter.
- Add companion constants: `K_IMPLICIT_CHANNEL = 8`, `K_IMPLICIT_APP = 4`.
- Extend `TrainingRow` data class with `weight: Double = 1.0`.
- Add `private suspend fun replayImplicitElo(appStates, channelStates)` method;
  called at the end of `replayElo()` after the channel-pair pass (~40 lines).
- Modify `fitCategoryWeights()`:
  - Extend feature vector length by 1.
  - Append implicit same-channel rows at weight 0.25 with rank_delta feature.
  - Update gradient loop to multiply by `row.weight`.
  - Persist rank_delta coefficient to `Prefs.RANK_DELTA_WEIGHT`.
  - ~50 lines changed/added.
- ~90 lines net addition.

`ai/scoring/Scorer.kt`
- Add `ImplicitJudgmentDao` constructor parameter (injectable via Hilt).
- In `score()`: call `implicitJudgmentDao.countForChannel(packageName, channelId ?: "")`.
- Compute `nCombined` and `gammaEffective` per §5.
- Replace `gamma` with `gammaEffective` in logit combination.
- Add `"n_implicit"` and `"gamma_effective"` to `contributions` map.
- ~15 lines changed.

`data/Prefs.kt`
- Add `REFIT_LAST_IMPLICIT_COUNT = "scoring_refit_last_implicit_count"`.
- Add `RANK_DELTA_WEIGHT = "scoring_rank_delta_weight"`.
- ~6 lines added.

`di/AppModule.kt` or `di/DatabaseModule.kt`
- If `ScoringRefit` and `Scorer` use `@Inject constructor` with `@Singleton`,
  Hilt auto-wires `ImplicitJudgmentDao` from the `@Provides` binding in
  `DatabaseModule`. Verify no manual binding changes are needed. If the project
  uses explicit `@Provides` for `ScoringRefit` or `Scorer`, update those
  provider functions to pass the new DAO parameter.

---

### §8 — Room Migration v11 → v12

```kotlin
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS implicit_judgments (
                id                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind                     TEXT    NOT NULL,
                winner_notification_id   INTEGER NOT NULL,
                loser_notification_id    INTEGER NOT NULL,
                winner_package           TEXT    NOT NULL,
                winner_channel_id        TEXT    NOT NULL,
                loser_package            TEXT    NOT NULL,
                loser_channel_id         TEXT    NOT NULL,
                winner_rank              INTEGER NOT NULL,
                loser_rank               INTEGER NOT NULL,
                winner_ai_class          TEXT,
                winner_ai_conf           REAL,
                loser_ai_class           TEXT,
                loser_ai_conf            REAL,
                cohort_size              INTEGER NOT NULL,
                screen_was_on            INTEGER NOT NULL,
                created_at_ms            INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

Register: `.addMigrations(..., MIGRATION_11_12)` in the Room builder.

Commit the generated schema export at:
`app/schemas/ai.talkingrock.lithium.data.db.LithiumDatabase/12.json`

---

## Testing Strategy

**Unit tests (JVM, no Android framework):**

Extract the row-generation logic from `recordImplicitSignals` into a pure
function accepting `(removedKey, removedIsOngoing, removedPkg, removedChannelId,
removedRank, peers: List<PeerInfo>, reason, screenOn)` returning
`List<ImplicitJudgment>`. This decouples it from Android APIs entirely.

- `REASON_CLICK` produces only `TAP_OVER_PEER` rows.
- Cascade gate: peers with rank >= tapped rank are excluded.
- `REASON_CANCEL` produces only `PEER_OVER_DISMISSED` rows; no cascade gate.
- Ongoing peers are excluded.
- Lithium package peers are excluded.
- Cohort cap at 20: if 30 peers pass filters, only 20 (lowest rank) are returned.
- `REASON_CANCEL_ALL` and `REASON_APP_CANCEL` produce zero rows.
- `screen_was_on = false` propagates to all rows.

`ScoringRefit` implicit Elo pass: given a list of `ImplicitJudgment` objects,
verify `RankState` updates use K=8 for channel, K=4 for cross-app, K=0 for
same-channel. Screen-off rows produce no Elo update.

`fitCategoryWeights` with implicit rows: verify sample weight 0.25 is applied
(gradient contribution is 1/4 of equivalent explicit row); verify rank_delta
feature is last element in feature vector; verify screen-off implicit rows are
excluded.

`Scorer` fade-out: `nCombined = 0` → `gammaEffective == gamma`;
`nCombined = 50` → `gammaEffective == 0.0`.

**Room instrumentation tests:**

- `MIGRATION_11_12`: apply migration from v11, verify `implicit_judgments` table
  exists with all 17 columns and correct types; verify existing tables are
  unaffected.
- `ImplicitJudgmentDao.insertAll`: batch insert 5 rows; verify all 5 retrievable.
- `ImplicitJudgmentDao.getSameChannelChronological`: mixed same-channel and
  cross-channel rows; verify only same-channel rows returned.
- `ImplicitJudgmentDao.countForChannel`: verify count includes rows where
  pkg/channel appears on either winner or loser side.

**Manual / device tests:**

- Tap a notification in the shade; verify `implicit_judgments` rows appear in the
  Room debug inspector (or logcat `Scorer` tag) with `kind = TAP_OVER_PEER` and
  correct `winner_rank` / `loser_rank` values with `loser_rank < winner_rank`.
- Swipe-dismiss a notification; verify `PEER_OVER_DISMISSED` rows appear.
- "Clear All" (`REASON_CANCEL_ALL`); verify zero rows written.
- Trigger `AiAnalysisWorker` manually via debug menu; verify `ScoringRefit` runs
  end-to-end without exception, and `AppRanking`/`ChannelRanking` Elo values
  shift for packages with implicit signal.
- Verify no crash when `getActiveNotifications()` returns an empty array (only one
  notification in the shade when tapped).

---

## Definition of Done

- [ ] `ImplicitJudgment` entity compiles; all 17 columns match migration SQL exactly.
- [ ] `MIGRATION_11_12` applied without error; Room schema export `12.json` committed.
- [ ] `ImplicitJudgmentDao` has all four methods; Hilt binding in `DatabaseModule`.
- [ ] `onNotificationRemoved` captures peer snapshot and `screenOn` synchronously on
      main thread before any coroutine launches.
- [ ] `recordImplicitSignals` implements all six gating rules (package, ongoing, rowId,
      reason, screen-off, cohort cap).
- [ ] Cascade rank gate applied for `REASON_CLICK`; no cascade for `REASON_CANCEL`.
- [ ] `REASON_CANCEL_ALL` and `REASON_APP_CANCEL` produce zero rows (verified by test).
- [ ] AI classification snapshot attempted for every winner/loser at capture time;
      null handled gracefully without crash.
- [ ] `ScoringRefit.replayElo()` includes implicit Elo pass with K=8/K=4/K=0 rules.
- [ ] Screen-off rows excluded from both Elo replay and category weight fit.
- [ ] `ScoringRefit.fitCategoryWeights()` appends same-channel implicit rows at
      weight 0.25; rank_delta feature in position `n_categories`; explicit rows get
      `rank_delta = 0.0`.
- [ ] Extended weight vector stored in `Prefs.CATEGORY_WEIGHTS`; `Scorer.categoryBias`
      reads only first `n_categories` elements (no inference regression).
- [ ] `Scorer.score()` reads `countForChannel` and applies `gammaEffective` fade-out.
- [ ] `Prefs.REFIT_LAST_IMPLICIT_COUNT` and `Prefs.RANK_DELTA_WEIGHT` added.
- [ ] Pure row-generation function unit tests pass (all gating and cascade rules).
- [ ] Elo K-value unit tests pass.
- [ ] Migration instrumentation test passes.
- [ ] Manual tap test: rows appear in DB with `loser_rank < winner_rank` for tap events.
- [ ] `ScoringRefit` runs without exception end-to-end when implicit rows are present.

---

## Open Questions

1. **`keyToRowId` availability for peers during capture.** The map is keyed by SBN
   key → DB row ID. It is populated in `onNotificationPosted` only for notifications
   Lithium processed in the current service session. For a peer notification that
   arrived before a service restart, the key will be absent. The plan treats absence
   as "skip this peer." Confirm this is the right behavior, or decide whether a DAO
   lookup by `(packageName, postedAtMs)` is worth the additional latency per peer.

2. **AI classification back-fill.** At capture time, `aiClassification` may be null
   because `AiAnalysisWorker` runs asynchronously. A post-classification back-fill
   pass (run inside `AiAnalysisWorker` after Step 5, updating `winner_ai_class` and
   `loser_ai_class` for recently-classified implicit rows) would recover these signals.
   Cost: one update query per newly-classified notification ID pair. Benefit: implicit
   rows become useful for the category weight fit that would otherwise discard them.
   Evaluate after observing what fraction of capture-time rows have null classification.

3. **`PEER_OVER_DISMISSED` signal weight.** A single user-dismiss with 15 peers
   generates 15 rows at `K_implicit = 8`. This is the same strength as a tap. Dismiss
   signals are arguably weaker — the user may dismiss habitually without preference
   ordering. Consider `K_IMPLICIT_DISMISS = 4` and `sample_weight = 0.10` for dismiss-
   derived rows vs. tap-derived rows. Best decided after observing real data distribution
   rather than pre-tuning.

4. **`implicit_judgments` retention.** No purge policy is specified. At 50 rows/day,
   the table reaches ~18k rows/year — fine. At 200+ rows/day (heavy notification
   load, frequent tapping), this could reach 70k+ rows/year and noticeably slow the
   refit query. If observed table size warrants it, add a retention sweep in
   `AiAnalysisWorker` mirroring `NotificationDao.deleteOlderThan`. Defer to
   post-launch monitoring.

5. **`ChannelRankingDao` missing `getAll()`.** The Elo replay builds state in-memory
   and upserts; it does not need to read existing channel rankings. The implicit Elo
   pass follows the same in-memory pattern. Confirm before implementation that no
   new read query is needed for the replay pass.
