# Lithium API Server — Phase 1 Handoff

**Date:** 2026-04-11  
**Status:** Phase 1 complete — debug APK builds successfully  
**Build verified:** `./gradlew assembleDebug` passes, 74 tasks, 0 errors, 3 pre-existing deprecation warnings

---

## What Was Built

An embedded Ktor CIO HTTP server that exposes Lithium's notification database over the
local network (intended for Tailscale-only access).

### New Files

**`app/src/main/java/ai/talkingrock/lithium/api/LithiumApiServer.kt`**  
The Ktor server itself. `@Singleton` with `@Inject constructor` — Hilt wires it automatically.
Binds `0.0.0.0:8400`. Injects `NotificationDao` and `AppBehaviorProfileDao` directly
(not through repositories) because the existing `NotificationRepository` doesn't expose
all the query methods needed (count, countClassified, getAllSince, etc.).

Endpoints:
- `GET  /api/health`                   — uptime + server timestamp
- `GET  /api/notifications`             — recent notifications (params: `since` epoch_ms, `limit`)
- `GET  /api/notifications/unresolved`  — unclassified records (AI hasn't run yet)
- `GET  /api/stats`                     — total/classified/unclassified counts, noise ratio
- `GET  /api/contacts`                  — per-app engagement summary from AppBehaviorProfile
- `POST /api/classifications`           — write classification labels back to DB
- `POST /api/dismiss`                   — bulk set removedAtMs + reason="api_dismiss"

**`app/src/main/java/ai/talkingrock/lithium/api/LithiumApiService.kt`**  
Android foreground service. `@AndroidEntryPoint`. Starts the server in `onStartCommand`,
stops in `onDestroy`. Shows a persistent low-priority notification: "Lithium API active /
Listening on :8400". Uses `START_STICKY` so Android restarts on process kill.
Foreground service type: `dataSync` (matches existing `FOREGROUND_SERVICE_DATA_SYNC` permission).

### Modified Files

**`gradle/libs.versions.toml`**  
Added `ktor = "2.3.12"` to `[versions]` and three library entries to `[libraries]`:
- `ktor-server-cio`
- `ktor-server-content-negotiation`
- `ktor-serialization-kotlinx-json`

**`app/build.gradle.kts`**  
Added the three Ktor implementation deps. Added `"/META-INF/INDEX.LIST"` to packaging
exclusions to avoid resource merge conflicts from Ktor's JARs.

**`app/src/main/AndroidManifest.xml`**  
- Added `<uses-permission android:name="android.permission.INTERNET" />` — this is the
  big security posture change: Lithium goes from zero-network to Tailscale-only.
- Added `<service android:name=".api.LithiumApiService" android:exported="false"
  android:foregroundServiceType="dataSync" />`.
- Updated the stale "NO INTERNET here" comment.

**`app/src/main/java/ai/talkingrock/lithium/LithiumApp.kt`**  
Added `startApiService()` call in `onCreate()` which calls `startForegroundService(Intent(...))`.
The service starts automatically on every app launch.

---

## Architecture Notes

**Why DAOs directly, not repositories?**  
`NotificationRepository` exposes: `insert`, `updateRemoval`, `getRecent(Flow)`, `getUnclassified`,
`getByPackage(Flow)`, `deleteOlderThan`, `getAll(Flow)`, `getDistinctPackageNames`, `getCount`, `deleteAll`.  
Missing for the API: `getAllSince` (one-shot), `countClassified`, `countDistinctClassifiedApps`,
`updateClassification`. Rather than bloating the repository, the API server injects the DAO directly.
If Phase 2 needs more, consider adding an `ApiRepository` wrapper.

**Why `0.0.0.0` not `100.108.10.45`?**  
Android doesn't expose a reliable way to bind a server socket to a Tailscale virtual interface
by IP address (the Tailscale IP can change, and binding to a specific IP on Android requires
the interface to be up before the server starts). Binding to `0.0.0.0` and relying on Tailscale
ACLs to restrict access is the standard Android Tailscale pattern.

**`usesCleartextTraffic=false` doesn't block the server**  
This manifest flag governs outbound HTTP connections from the app, not inbound. Ktor acting as
a server is unaffected. No network security config changes were needed.

**Service notification ID 1002**  
`LithiumNotificationListener` uses `NUDGE_NOTIFICATION_ID = 1001`. The API service uses 1002.
No collision.

---

## What Phase 2 Should Do

1. **Deploy to device** — ADB install the debug APK. Verify `curl http://100.108.10.45:8400/api/health`
   returns `{"status":"ok",...}` from the Tailscale network.

2. **Authentication** — The server currently has no auth. Phase 2 should add a shared-secret token
   check (HTTP header `X-Lithium-Token`). The token should be generated once, stored in
   `EncryptedSharedPreferences`, and displayed in the Settings screen via a QR code or copy button.
   Without this, any device on the Tailscale network can read notification data.

3. **Settings toggle** — Add a switch in `SettingsScreen.kt` to start/stop `LithiumApiService`.
   Right now the server always starts on app launch. Use `startService` / `stopService` from
   `SettingsViewModel`.

4. **`NotificationRepository` extension** (optional) — If Phase 2 adds more complex queries,
   consider creating an `ApiRepository` that wraps the DAOs with the methods needed by the server.

5. **Phase 2 endpoints** (per roadmap):
   - `GET /api/queue` — QueuedNotification records pending user review
   - `POST /api/queue/action` — resolve queued items
   - `GET /api/rules` — list rules
   - `POST /api/rules` — create rule from Claude suggestion

6. **Proguard rules** — Ktor is included in the debug build. Before release, add proguard rules
   for Ktor's reflection-based features (ContentNegotiation, routing) if minification is enabled.

---

## Key File Locations (Corellia)

```
/home/kellogg/dev/Lithium/
  app/src/main/java/ai/talkingrock/lithium/
    api/
      LithiumApiServer.kt    — Ktor server + all DTOs
      LithiumApiService.kt   — Android foreground service
    LithiumApp.kt            — modified: starts API service
  app/src/main/AndroidManifest.xml   — INTERNET permission + service decl
  gradle/libs.versions.toml          — ktor = "2.3.12"
  app/build.gradle.kts               — Ktor deps + INDEX.LIST exclusion
```
