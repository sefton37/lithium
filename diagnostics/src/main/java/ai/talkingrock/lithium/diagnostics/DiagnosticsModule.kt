package ai.talkingrock.lithium.diagnostics

/**
 * Diagnostics module entry point — stub for Phase 0.
 *
 * This module is included in debug builds and excluded from release builds
 * (and excluded when -PexcludeDiagnostics is passed to Gradle).
 *
 * Future: expose a local HTTP server on loopback for database inspection,
 * WorkManager task status, and rule engine trace logging.
 */
object DiagnosticsModule {
    const val VERSION = "0.1.0"
}
