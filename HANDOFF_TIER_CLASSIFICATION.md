# Handoff: Phase 2 — Notification Tier Classification

**Built:** 2026-04-11  
**DB version:** 3 → 4  
**Branch:** master  
**APK:** `app/build/outputs/apk/debug/app-debug.apk`

## What was built

Notification tier classification runs at **capture time** — as each notification arrives in `LithiumNotificationListener.onNotificationPosted()`, `TierClassifier.classify()` assigns a tier 0–3 and a short reason code. Both are persisted to the database and exposed via the API.

### Tiers

| Tier | Name | Action | Assigned to |
|------|------|--------|-------------|
| 0 | Invisible | Never report | Spotify, YouTube Music, Tailscale, Lithium itself, `isOngoing=true`, `category=transport` |
| 1 | Noise | Log only, daily count | LinkedIn, Amazon, Play Store, marketing text keywords |
| 2 | Worth seeing | Hourly check | Gmail, Calendar, GitHub, financial apps, school apps (default) |
| 3 | Interrupt | Immediate | SMS (com.google.android.apps.messaging), calls, 2FA/security text |

### Files changed

| File | Change |
|------|--------|
| `app/src/main/java/.../classification/TierClassifier.kt` | **NEW** — pure object, no Android deps, fully testable |
| `data/model/NotificationRecord.kt` | Added `tier: Int = 2` and `tierReason: String?` |
| `data/db/LithiumDatabase.kt` | Version bumped 3 → 4 |
| `di/DatabaseModule.kt` | Added `MIGRATION_3_4` (two ALTER TABLE, registered in builder) |
| `data/db/NotificationDao.kt` | Added `TierCount` data class, `getAllSinceWithTiers()`, `getTierBreakdown()` |
| `service/LithiumNotificationListener.kt` | `buildRecord()` now calls `TierClassifier.classify()` |
| `api/LithiumApiServer.kt` | `/api/notifications?tier=N` filter; `/api/stats` tier breakdown |

## API changes

### GET /api/notifications

New optional `tier` parameter (repeatable):

```
GET /api/notifications?tier=2&tier=3          # only tiers 2 and 3
GET /api/notifications?since=1712800000000    # all tiers (unchanged behavior)
```

`NotificationDto` gains two new fields: `"tier": 2, "tierReason": "gmail"`

### GET /api/stats

Response now includes `tierBreakdown`:

```json
{
  "totalNotifications": 130533,
  "classifiedNotifications": 98421,
  "unclassifiedNotifications": 32112,
  "distinctApps": 52,
  "noiseRatio": 0.754,
  "tierBreakdown": {
    "0": 89012,
    "1": 18234,
    "2": 19871,
    "3": 3416
  }
}
```

## CRM integration (Phase 3 stub)

`TierClassifier.kt` has two TODO comments marking Phase 3 hook points:

1. **SMS** (line ~47): Currently all SMS → Tier 3. Phase 3 should check sender phone against the trust roster — unknown senders → Tier 2, family whitelist → Tier 3.
2. **Gmail** (line ~63): Currently all Gmail → Tier 2. Phase 3 should check sender email against trust roster — whitelist → Tier 3.

The trust roster sync (Option B, lightweight SQLite file on-device) is the next step. Contacts.db on Corellia is at `/home/kellogg/dev/contacts/contacts.db`. Export query:

```sql
-- emails
SELECT e.email, p.trust_level, p.display_name 
FROM emails e JOIN people p ON e.person_id = p.id
WHERE p.trust_level != "unknown";

-- phones
SELECT ph.phone, p.trust_level, p.display_name
FROM phones ph JOIN people p ON ph.person_id = p.id  
WHERE p.trust_level != "unknown";
```

## Deployment

The live APK is at:
```
/home/kellogg/dev/Lithium/app/build/outputs/apk/debug/app-debug.apk
```

Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The database migration runs automatically on first launch. Existing 130K+ notifications get `tier=2` (the DEFAULT 2 applied by SQLite). They will not be retroactively reclassified — only new notifications get real tier assignments. If retroactive classification is desired, run:

```sql
-- This would need to be done in a WorkManager task since the DB is SQLCipher encrypted.
-- TierClassifier is a pure Kotlin object, safe to call from a background worker.
```

## What to test

1. Open app — migration should complete silently (no crash)
2. Receive a Spotify notification — should be captured but not visible (Tier 0)
3. Send yourself an SMS — should be Tier 3 in API
4. `curl http://100.108.10.45:8400/api/stats` — verify `tierBreakdown` field present
5. `curl "http://100.108.10.45:8400/api/notifications?tier=3&limit=20"` — should show only SMS/calls/2FA

## Next session

- Phase 3: CRM trust roster sync to device (lightweight SQLite)
- Wire the two TODO stubs in TierClassifier (SMS phone lookup, Gmail sender lookup)
- Consider a WorkManager job to retroactively backfill tier on existing 130K notifications
- Consider surfacing tier in the briefing screen (BriefingScreen.kt)
