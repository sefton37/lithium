package ai.talkingrock.lithium.data

/**
 * SharedPreferences key constants used across the app.
 *
 * All preferences are stored in the [EncryptedSharedPreferences] singleton
 * provided by [ai.talkingrock.lithium.di.AppModule].
 */
object Prefs {

    // -- Onboarding --

    /** Boolean: true after the user has completed the guided onboarding flow. */
    const val ONBOARDING_COMPLETE = "onboarding_complete"

    // -- Data Readiness --

    /** Boolean: true after the data-readiness notification has been sent. */
    const val DATA_READY_NOTIFIED = "data_ready_notified"

    /** Minimum classified notifications before the system considers data "ready". */
    const val DATA_READY_MIN_COUNT = 50

    /** Minimum distinct apps with classified notifications before data is "ready". */
    const val DATA_READY_MIN_APPS = 3

    // -- Settings (existing keys, centralised here for reference) --

    const val PREF_RETENTION_DAYS = "retention_days"
    const val PREF_DIAGNOSTICS = "diagnostics_enabled"
    const val DEFAULT_RETENTION_DAYS = 30

    // -- Shade Mode --

    /** Boolean: true when the user has enabled Shade Mode (notification interception). Default false. */
    const val SHADE_MODE_ENABLED = "shade_mode_enabled"

    /**
     * Boolean: true after the default tier-based seed rules have been inserted by
     * [ai.talkingrock.lithium.data.db.ShadeModeSeeder]. Once set, the seeder is a no-op
     * even if called again — one-shot, idempotent.
     */
    const val SHADE_MODE_SEED_DONE = "shade_mode_seed_done"

    // -- Worker Constraints --

    /** Boolean: require wall charger (AC/wireless) before running analysis. Default true. */
    const val PREF_REQUIRE_CHARGING = "worker_require_charging"
    const val DEFAULT_REQUIRE_CHARGING = true

    /** Boolean: require battery not low. Default true. */
    const val PREF_REQUIRE_BATTERY_NOT_LOW = "worker_require_battery_not_low"
    const val DEFAULT_REQUIRE_BATTERY_NOT_LOW = true

    /** Boolean: require device idle (Doze). Default false (relaxed from original). */
    const val PREF_REQUIRE_IDLE = "worker_require_idle"
    const val DEFAULT_REQUIRE_IDLE = false
}
