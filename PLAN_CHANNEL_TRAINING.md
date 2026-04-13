# Plan: Channel-Level Training Mode

**Status: Scope 1 and partial Scope 2 implemented. Scope 3 (UI) not implemented.**

**What landed:**
- `notification_channels` table entity (`NotificationChannel.kt`) and DAO
  (`NotificationChannelDao.kt`) exist. `LithiumNotificationListener` calls
  `channelDao.upsert` on notification ingest.
- `TrainingJudgment` has four new nullable channel columns (`leftChannelId`,
  `leftChannelName`, `rightChannelId`, `rightChannelName`).
- `MIGRATION_9_10` SQL ran. `ChannelRanking` entity also landed in v10.

**What did NOT land:**
- `NotificationChannel::class` is NOT in the `LithiumDatabase.kt` entities list.
  The table does not exist in the running database despite the entity file being
  present. **This is a bug.** The entity must be added to `@Database(entities = ...)`
  and a migration that creates the `notification_channels` table must be written.
  Currently the DB history shows v10 added `channel_rankings` only; the
  `notification_channels` table was either skipped or included in the same migration
  without being tracked.
- `Challenge.ChannelPair` sealed class variant does NOT exist in `TrainingViewModel.kt`.
  The training UI only has `NotificationPair` and `AppBattle`.
- `pickChannelPairChallenge`, `submitChannelPair`, `judgedChannelPairs` are not present.
- `BattleCard` does not accept or display `channelDisplayName`.
- `ChallengeContent` has no `Challenge.ChannelPair` branch.
- The channel-framing banner copy ("Which channel deserves more attention?") is not
  in the UI.

The DoD checklist items below have not been re-evaluated since implementation. Treat
unchecked items as unverified.

---

## Goal

Shift the notification-pair training challenge from comparing arbitrary individual
notifications to comparing **app + notification-channel pairs**. Each side of a
challenge represents a distinct `(packageName, channelId)` group, shown with a
human-readable channel display name. Judgments produce `(pkg, channel)`-keyed signal
that downstream aggregation can use directly.

## Non-Goals

- No changes to AppBattle mode or AppRanking Elo logic.
- No retroactive re-labeling of existing `training_judgments` rows (old rows will have
  NULL channel columns — that is acceptable and expected).
- No new quest types (channel-pair challenges run in Free Play and any existing quest
  that does not force `onlyUnclassified`).
- No UI changes to the Briefing, Rules, or History screens in this plan.
- No server-side or model-training pipeline changes.

---

## Approach

### Scope 1 — Channel display-name cache

**Decision: separate `notification_channels` table, not a column on `notifications`.**

Rationale: `channelId` is stable per `(packageName, channelId)` tuple; the display name
belongs to the channel, not to each notification. Adding it to every notification row
wastes space, complicates every existing query, and does nothing for pre-existing rows
without names. The `notification_channels` table stores one row per
`(package_name, channel_id)` unique pair; `last_seen_ms` tracks freshness so stale
entries can be ignored without deletion.

The display name is retrieved from `RankingMap.getRanking(key).channel?.name` during
`onNotificationPosted`. This API requires `NotificationListenerService` being connected;
it is already called on the same callback thread where `buildRecord` runs. The name is
nullable (system may not surface it for all channels); the UI falls back to `channelId`
when the display name is absent.

**Alternative considered: column on `notifications`.**  
Rejected because: (a) every existing notification row would have NULL, requiring a full
table scan backfill that is slow on large installs; (b) the channel name for a given
`(pkg, channel)` is the same for all rows — storing it per-row is denormalized; (c) Room
`ALTER TABLE` migrations on notifications are already numerous and each one must be
listed in `DatabaseModule.kt`.

### Scope 2 — Channel-pair challenge selection

**Decision: add a third `Challenge` variant (`ChannelPair`), leave `NotificationPair`
intact.**

`NotificationPair` is still used by `onlyUnclassified` quests (which target individual
notification content, not channel-level preference). Replacing it would break "Label the
Unknown" quest semantics. `ChannelPair` is a new variant that participates in the same
`loadNextPair` scheduling logic as `AppBattle`: it replaces `NotificationPair` for
non-unclassified quests in Free Play, using the existing weighting mechanism already in
`shouldDoAppBattle`.

The selection algorithm:
1. Fetch the same `getAmbiguousCandidates` pool (already excludes tier-0 and ongoing).
2. Group in-memory by `(packageName, channelId)`.
3. Keep only groups with `size >= MIN_CHANNEL_SAMPLE` (proposed: 3, same order of
   magnitude as `MIN_JUDGMENTS_TO_MAP`).
4. Exclude `(pkg, channel)` pairs that have already been judged as a pair this session
   (tracked in-memory in ViewModel, similar to how `getJudgedNotificationIds` works but
   at channel level — a `Set<Pair<String,String?>>` is sufficient).
5. From the remaining groups, pick two with differing tiers or tier ambiguity; pick one
   representative `NotificationRecord` from each group (the one with lowest
   `|aiConfidence - 0.5|`, i.e. most ambiguous).
6. If fewer than two eligible groups exist, fall through to `pickNotificationChallenge`
   (existing behavior).

**No new DAO query is strictly required for selection** — the existing
`getAmbiguousCandidates` pool is grouped in Kotlin. A dedicated DAO query for
"distinct eligible channels with count" is optional but would be more efficient at scale
(see Open Questions). The plan specifies the Kotlin path first; the DAO optimization can
be a follow-up.

**Alternative considered: replace `NotificationPair` entirely.**  
Rejected because `onlyUnclassified` quests need the individual-notification view — they
are designed to label content, not rank channels.

### Scope 3 — UI framing + judgment schema

`BattleCard` gains a `channelDisplayName: String?` parameter. The channel identity is
shown prominently as a subtitle under the app name, making clear that the choice
represents the channel, not this specific notification. The example notification content
(title, text, timestamp) is retained but visually de-emphasized (secondary text style).

**Banner copy change:** from "Which notification matters more?" to  
> "Which channel deserves more attention?"  
> *Your choice applies to all notifications from this channel.*

Button copy: "A's channel" / "B's channel" (instead of "A is more important" /
"B is more important").

`TrainingJudgment` gains four nullable columns: `left_channel_id`, `left_channel_name`,
`right_channel_id`, `right_channel_name`. Nullable so existing `NotificationPair` rows
(which have no channel context) remain valid without any backfill.

---

## File-by-File Changes

### New file: `data/model/NotificationChannel.kt`

Create Room entity `@Entity(tableName = "notification_channels")` with:
- `packageName: String` (PK component)
- `channelId: String` (PK component)
- `displayName: String?`
- `lastSeenMs: Long`

Composite primary key via `@Entity(primaryKeys = ["package_name", "channel_id"])`.

### New file: `data/db/NotificationChannelDao.kt`

```
upsert(channel: NotificationChannel)   // INSERT OR REPLACE
getDisplayName(packageName, channelId): String?   // suspend, one-shot
```

`upsert` is called at notification ingest. `getDisplayName` is called by the ViewModel
when building a `ChannelPair` challenge.

### Modified: `data/db/LithiumDatabase.kt`

- Add `NotificationChannel::class` to `entities` list.
- Bump `version` from 9 to 10.
- Add `abstract fun notificationChannelDao(): NotificationChannelDao`.

### Modified: `di/DatabaseModule.kt`

- Add `MIGRATION_9_10` object:
  ```sql
  CREATE TABLE IF NOT EXISTS notification_channels (
      package_name TEXT NOT NULL,
      channel_id   TEXT NOT NULL,
      display_name TEXT,
      last_seen_ms INTEGER NOT NULL,
      PRIMARY KEY (package_name, channel_id)
  );

  ALTER TABLE training_judgments ADD COLUMN left_channel_id   TEXT;
  ALTER TABLE training_judgments ADD COLUMN left_channel_name TEXT;
  ALTER TABLE training_judgments ADD COLUMN right_channel_id   TEXT;
  ALTER TABLE training_judgments ADD COLUMN right_channel_name TEXT;
  ```
- Add `MIGRATION_9_10` to the `addMigrations(...)` call (line ~335).
- Add `@Provides fun provideNotificationChannelDao(...)` binding.
- Update version history comment block.

### Modified: `data/model/TrainingJudgment.kt`

Add four nullable `@ColumnInfo` fields after `right_confidence`:
- `leftChannelId: String? = null`
- `leftChannelName: String? = null`
- `rightChannelId: String? = null`
- `rightChannelName: String? = null`

Existing rows will read NULL for all four — correct behavior.

### Modified: `service/LithiumNotificationListener.kt`

**Inject `NotificationChannelDao`** (add `@Inject lateinit var channelDao: NotificationChannelDao`).

**In `onNotificationPosted`**, after building `record` and before or during the
`serviceScope.launch` block, extract the channel display name from the `RankingMap`:

```kotlin
// After sbn is available, before coroutine launches:
val channelDisplayName: String? = try {
    currentRanking?.getRanking(sbn.key)?.channel?.name?.toString()
} catch (_: Exception) { null }
```

`currentRanking` is the `RankingMap` passed to `onNotificationRankingUpdate`, or
retrieved via `getCurrentRanking()`. Note: `onNotificationPosted` does not receive a
`RankingMap` parameter (unlike `onNotificationRemoved`) — use `getCurrentRanking()` from
`NotificationListenerService`. Call this on the main thread before the coroutine launch
(same pattern as the existing `sbnExtras` extraction for `NotificationResurface`).

Inside the `serviceScope.launch` block, after `notificationRepo.insert(record)`:
```kotlin
if (record.channelId != null) {
    channelDao.upsert(NotificationChannel(
        packageName = record.packageName,
        channelId = record.channelId,
        displayName = channelDisplayName,
        lastSeenMs = record.postedAtMs
    ))
}
```

This must run in every code path (allowed, suppressed, queued, safety_exempt with
content) — add it to all four `serviceScope.launch` branches, or extract a shared
`afterInsert(record, rowId)` suspend helper to avoid repetition.

### Modified: `ui/training/TrainingViewModel.kt`

**Inject `NotificationChannelDao`** into the constructor.

**Add `Challenge.ChannelPair`** to the `Challenge` sealed class (line ~35):
```kotlin
data class ChannelPair(
    val left: NotificationRecord,
    val leftChannelName: String?,
    val right: NotificationRecord,
    val rightChannelName: String?
) : Challenge()
```

**Add `MIN_CHANNEL_SAMPLE = 3`** to `companion object`.

**Add `judgedChannelPairs: MutableSet<Pair<String, String?>>` field** (in-memory,
reset on `selectQuest`). Key is `"${pkg}|${channelId}"` concatenated strings — or
use a `data class ChannelKey(val pkg: String, val channelId: String?)`.

**Add `pickChannelPairChallenge(quest: Quest): Challenge.ChannelPair?`**:

```
1. Load ambiguous candidates (same call as pickNotificationChallenge, same excludeIds).
2. Group by Pair(packageName, channelId).
3. Filter groups: size >= MIN_CHANNEL_SAMPLE AND group key not in judgedChannelPairs.
4. If fewer than 2 groups: return null.
5. Sort groups by average |aiConfidence - 0.5| ascending (most ambiguous first).
6. Pick group A = first eligible group, group B = first eligible group with different
   (pkg, channel) from A (and different packageName preferred but not required).
7. Representative for each: the record in the group with minimum |aiConfidence - 0.5|.
8. Look up display names: channelDao.getDisplayName(pkg, channelId).
9. Return ChannelPair(leftRecord, leftName, rightRecord, rightName).
```

**Update `loadNextPair`** (line ~289): In the `quest == Quests.FREE_PLAY` branch, add
`pickChannelPairChallenge` as the primary attempt before falling back to
`pickNotificationChallenge`. For non-unclassified quests (non-free-play), attempt
`pickChannelPairChallenge` before `pickNotificationChallenge` as well (channel-level
learning is more useful than random notification pairs in all non-unclassified quests).
`onlyUnclassified` quests skip `pickChannelPairChallenge` entirely.

Concrete ordering for Free Play:
```
tryAppBattle → pickAppBattle or pickChannelPair
otherwise    → pickChannelPair → pickNotificationChallenge → pickAppBattle
```

**Update `submit`**: add a `when` branch for `Challenge.ChannelPair` that calls a new
`submitChannelPair(c, choice)` method.

**Add `submitChannelPair`**: identical to `submitNotificationPair` in XP logic, but
builds `TrainingJudgment` with `leftChannelId`, `leftChannelName`,
`rightChannelId`, `rightChannelName` populated from the challenge fields. Also
adds `"${left.packageName}|${left.channelId}"` to `judgedChannelPairs`.

**Reset `judgedChannelPairs`** in `selectQuest`.

### Modified: `ui/training/TrainingScreen.kt`

**Update `BattleCard` signature** (line 488):
```kotlin
private fun BattleCard(
    label: String,
    record: NotificationRecord,
    channelDisplayName: String?,  // new
    state: CardBattleState
)
```

**Inside `BattleCard`'s `Column`**: Between the app-name `Row` (line 534) and the
title `Text` (line 550), insert the channel identity row:
```kotlin
val channelLabel = channelDisplayName?.takeIf { it.isNotBlank() }
    ?: record.channelId?.takeIf { it.isNotBlank() }
if (channelLabel != null) {
    Text(
        text = channelLabel,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
```

Style the title/text/timestamp block with `color = MaterialTheme.colorScheme.onSurfaceVariant`
(currently `onSurface`) to visually de-emphasize the example content relative to the
channel identity.

**Update `ChallengeContent`** (line ~319): Add branch for `Challenge.ChannelPair`:
```kotlin
is Challenge.ChannelPair -> {
    ChallengeBanner(
        title = "Which channel deserves more attention?",
        subtitle = "Your choice applies to all notifications from this channel."
    )
    BattleCard("A", challenge.left, challenge.leftChannelName, leftState)
    Button(...) { Text("A's channel") }
    BattleCard("B", challenge.right, challenge.rightChannelName, rightState)
    Button(...) { Text("B's channel") }
}
```

Update existing `Challenge.NotificationPair` call sites to pass `null` for
`channelDisplayName` (backward-compatible with existing BattleCard parameter addition).

---

## Room Migration Details

**Version bump:** 9 → 10.

**`MIGRATION_9_10` SQL** (add to `DatabaseModule.kt` before `derivePassphrase`):

```sql
-- New channel cache table
CREATE TABLE IF NOT EXISTS notification_channels (
    package_name TEXT NOT NULL,
    channel_id   TEXT NOT NULL,
    display_name TEXT,
    last_seen_ms INTEGER NOT NULL,
    PRIMARY KEY (package_name, channel_id)
);

-- Channel snapshot columns on training_judgments (nullable, safe ALTER TABLE)
ALTER TABLE training_judgments ADD COLUMN left_channel_id   TEXT;
ALTER TABLE training_judgments ADD COLUMN left_channel_name TEXT;
ALTER TABLE training_judgments ADD COLUMN right_channel_id  TEXT;
ALTER TABLE training_judgments ADD COLUMN right_channel_name TEXT;
```

Room migration code:
```kotlin
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_channels (
                package_name TEXT NOT NULL,
                channel_id   TEXT NOT NULL,
                display_name TEXT,
                last_seen_ms INTEGER NOT NULL,
                PRIMARY KEY (package_name, channel_id)
            )
        """.trimIndent())
        db.execSQL("ALTER TABLE training_judgments ADD COLUMN left_channel_id TEXT")
        db.execSQL("ALTER TABLE training_judgments ADD COLUMN left_channel_name TEXT")
        db.execSQL("ALTER TABLE training_judgments ADD COLUMN right_channel_id TEXT")
        db.execSQL("ALTER TABLE training_judgments ADD COLUMN right_channel_name TEXT")
    }
}
```

Register in `provideDatabase`: `.addMigrations(..., MIGRATION_9_10)`.

Update schema export comment in `LithiumDatabase.kt`:
```
- 10: Channel training — added notification_channels table;
      added left/right channel id/name to training_judgments.
```

---

## Risks and Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| `getCurrentRanking()` returns null or channel is null for some notifications | High — common for apps on older API levels or OEM modifications | `channelDisplayName` is nullable throughout; UI falls back to `channelId`; channelId itself may be null for pre-O notifications (fall back to empty string "default") |
| Pre-existing `notifications` rows have no entry in `notification_channels` | Certain — table starts empty | ViewModel's `getDisplayName` returns null → UI shows channelId. Acceptable; table populates organically as new notifications arrive |
| `pickChannelPairChallenge` always returns null if groups are too small | Likely on fresh installs | Falls back to `pickNotificationChallenge` as before; no regression |
| `judgedChannelPairs` grows unbounded within a session | Low risk (bounded by number of distinct channels on device) | Acceptable; reset on `selectQuest`. Cap at 200 entries if desired |
| `submitChannelPair` XP path diverges from `submitNotificationPair` over time | Medium | Both call the same `computeXpForJudgment` helper; keep them aligned. Consider extracting a shared `submitPairJudgment(left, right, leftChannelId, leftChannelName, rightChannelId, rightChannelName, choice)` function |
| Schema export file not updated | Certain if forgotten | Room generates the JSON export at build time; commit `app/schemas/ai.talkingrock.lithium.data.db.LithiumDatabase/10.json` |
| `buildRecord` and channel upsert run in different coroutine dispatches | Low | Both run within `serviceScope` (IO); channel upsert is fire-and-forget after the notification insert. No ordering guarantee needed — the `notification_channels` row is a cache |
| Two `(pkg, channel)` groups from the same app always selected | Low-medium | The pair-selection in `pickChannelPairChallenge` step 6 prefers different `packageName` first. If only one app has enough samples, same-app different-channel pairs are allowed (still useful signal) |
| `onNotificationPosted` does not receive `RankingMap` directly | Confirmed from source | Use `getCurrentRanking()` from `NotificationListenerService`, called on main thread before `serviceScope.launch`. This is the same approach used by `onNotificationRemoved` which receives rankingMap; for posted, `getCurrentRanking()` is the documented pattern |

---

## Testing Strategy

**Unit tests (JVM, no Android framework)**

- `pickChannelPairChallenge`: given a list of `NotificationRecord`s with known
  `(packageName, channelId)` distributions, verify: groups below `MIN_CHANNEL_SAMPLE`
  are excluded; already-judged channel pairs are excluded; returned records are the most
  ambiguous representative per group.
- `submitChannelPair`: verify `TrainingJudgment` is built with correct channel fields,
  and `judgedChannelPairs` is updated.

**Room instrumentation tests**

- `MIGRATION_9_10`: apply migration, verify `notification_channels` table exists with
  correct schema; verify `training_judgments` has four new nullable columns; verify
  pre-existing rows read with NULL values.
- `NotificationChannelDao.upsert`: insert a row, upsert with new `displayName`, verify
  display name updated and only one row exists.
- `NotificationChannelDao.getDisplayName`: returns null when absent, returns correct name
  when present.

**Manual / device tests**

- Open Training tab, trigger several rounds; verify `BattleCard` shows channel name (or
  channelId fallback) visually distinct from app name.
- Trigger a round where both sides have the same app but different channels; verify
  banner copy and button copy show channel framing.
- Trigger a `NotificationPair` round (unclassified quest); verify channel row is absent
  (null passed) — no regression in existing UI.
- Run fresh install (no prior notifications); verify graceful fallback to
  `pickNotificationChallenge` (no crash, no empty screen).
- Verify `notification_channels` rows appear in Room debug inspector after several
  notifications arrive.

---

## Definition of Done

- [ ] `notification_channels` entity, DAO, and Hilt binding exist and compile
- [ ] `MIGRATION_9_10` SQL is correct; `app/schemas/.../10.json` is committed
- [ ] `TrainingJudgment` has four new nullable channel columns (Room entity + migration aligned)
- [ ] `LithiumNotificationListener` calls `channelDao.upsert` on every notification ingest path (all four branches)
- [ ] `Challenge.ChannelPair` sealed class variant exists with left/right channel fields
- [ ] `pickChannelPairChallenge` implemented with sample-size gate, judged-pair exclusion, and fallback
- [ ] `submitChannelPair` writes `TrainingJudgment` with channel fields populated
- [ ] `loadNextPair` routes to `pickChannelPairChallenge` for non-unclassified quests
- [ ] `BattleCard` accepts and displays `channelDisplayName?` with fallback to `channelId`
- [ ] `ChallengeContent` renders `Challenge.ChannelPair` with updated banner copy and button copy
- [ ] Existing `NotificationPair` render path passes `null` for `channelDisplayName` — no regression
- [ ] Migration instrumentation test passes (9 → 10) including NULL-column check on old rows
- [ ] Unit tests for `pickChannelPairChallenge` group selection logic pass

---

## Open Questions

1. **DAO query vs. in-memory grouping at scale.** The current plan groups the
   `getAmbiguousCandidates` pool (up to 60 rows) in Kotlin. On installs with thousands
   of notifications, `getAmbiguousCandidates` already limits to `CANDIDATE_POOL_SIZE=60`.
   This is fine for now. If the pool needs to be channel-aware at the SQL level for
   better diversity, add a new DAO method:
   ```sql
   SELECT package_name, channel_id, COUNT(*) AS cnt,
          AVG(ABS(COALESCE(ai_confidence, 0.5) - 0.5)) AS ambiguity
   FROM notifications
   WHERE tier > 0 AND is_ongoing = 0 AND channel_id IS NOT NULL
     AND (title IS NOT NULL OR text IS NOT NULL)
     AND id NOT IN (:excludeIds)
   GROUP BY package_name, channel_id
   HAVING cnt >= :minSample
   ORDER BY ambiguity ASC
   ```
   This would return eligible channel groups directly, removing the Kotlin grouping step.
   Defer until pool size or diversity is observed to be a real problem.

2. **`onlyUnclassified` quest and channel pairing.** Currently `onlyUnclassified = true`
   forces `pickNotificationChallenge`. The implementer should confirm with the team
   whether a "Label Unknown Channels" quest variant is desired before adding it; it is
   out of scope for this plan.

3. **Backfill of channel names for existing notifications.** There is no mechanism to
   recover display names for already-captured notifications — the `RankingMap` only
   reflects live notifications. The `notification_channels` table will fill in over time.
   If fast coverage is required, a `WorkManager` worker could iterate
   `getDistinctPackageNames()`, query `NotificationManager.getNotificationChannels(pkg)`,
   and upsert display names. Out of scope for this plan.

4. **`patternKey` and XP accounting.** The existing `patternKey(record)` function
   produces `"${pkg}|${tierReason}"`. For `ChannelPair` judgments, the more meaningful
   key would be `"${pkg}|${channelId}"`. The XP `newlyMapped` logic in
   `submitNotificationPair` uses `patternKey`. Decide whether `submitChannelPair` should
   use a channel-keyed variant or reuse the tier-reason key. Using `channelId` is more
   precise but would require a parallel `channelPatternStats` flow. Using `tierReason`
   reuses existing stats. Recommend: reuse `patternKey(record)` in `submitChannelPair`
   for now; channel-level XP accounting is a follow-up.
