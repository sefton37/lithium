package ai.talkingrock.lithium.data.db

import android.content.SharedPreferences
import androidx.room.withTransaction
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.model.Rule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot seeder that inserts the four default tier-based rules on the user's first
 * shade-mode enable. After the seed completes, [Prefs.SHADE_MODE_SEED_DONE] is set to
 * true and subsequent calls are no-ops.
 *
 * Default tier rules (first-match-wins, ordered by tier descending so higher-priority
 * tiers are evaluated first):
 *
 * | Rule              | Condition        | Action   |
 * |-------------------|------------------|----------|
 * | Allow Tier 3      | TierMatch(3)     | ALLOW    |
 * | Queue Tier 2      | TierMatch(2)     | QUEUE    |
 * | Suppress Tier 1   | TierMatch(1)     | SUPPRESS |
 * | Suppress Tier 0   | TierMatch(0)     | SUPPRESS |
 *
 * The rules are created with `source = "seed"` so the user can distinguish them from
 * manually created rules in the Rules management screen.
 *
 * Fix #3 — double-seed protection:
 * - A [Mutex] prevents concurrent coroutines from both passing the flag check (TOCTOU).
 * - The four inserts are wrapped in a single Room `withTransaction { }` so a crash
 *   between inserts cannot leave partial seeds.
 * - If the flag is absent but seed rules already exist ([RuleDao.countSeedRules] > 0),
 *   the flag is self-healed and no inserts are performed. This covers the crash window
 *   between the transaction commit and the SharedPreferences write.
 *
 * [transactionRunner] is injectable so tests can provide a no-op transaction wrapper
 * without needing to mock Room's suspend inline extension function.
 *
 * This class is suspend-friendly. Call [seedIfNeeded] from a coroutine on the IO dispatcher.
 */
@Singleton
class ShadeModeSeeder @Inject constructor(
    private val ruleDao: RuleDao,
    private val database: LithiumDatabase,
    private val sharedPreferences: SharedPreferences,
) {

    /**
     * Wraps [block] in a Room transaction. Defaults to [LithiumDatabase.withTransaction].
     * Tests may replace this property to avoid mocking the Room KTX suspend inline extension.
     */
    internal var transactionRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        database.withTransaction { block() }
    }

    // Mutex prevents rapid double-tap: two coroutines both reading flag = false and both
    // attempting to insert. Only one runs at a time; the second sees flag = true on entry.
    private val mutex = Mutex()

    /**
     * Inserts the default tier seed rules if they have not been inserted before.
     * Idempotent — safe to call multiple times:
     * 1. [Prefs.SHADE_MODE_SEED_DONE] = true → immediate return.
     * 2. Seed rules already present (self-heal after crash) → set flag and return.
     * 3. Neither → run the transaction then set the flag.
     */
    suspend fun seedIfNeeded() {
        mutex.withLock {
            // Primary guard: flag already set.
            if (sharedPreferences.getBoolean(Prefs.SHADE_MODE_SEED_DONE, false)) {
                return
            }

            // Self-heal: flag absent but rules already exist (crash between transaction
            // commit and SharedPreferences write on a previous call).
            if (ruleDao.countSeedRules() > 0) {
                sharedPreferences.edit()
                    .putBoolean(Prefs.SHADE_MODE_SEED_DONE, true)
                    .apply()
                return
            }

            val now = System.currentTimeMillis()

            // Rules are inserted oldest-first so RuleEngine evaluates them in this order:
            // Tier 3 → Tier 2 → Tier 1 → Tier 0 (first-match-wins).
            val seedRules = listOf(
                Rule(
                    name = "Allow Tier 3 (interrupts)",
                    conditionJson = """{"type":"tier_match","tier":3}""",
                    action = "allow",
                    status = "approved",
                    createdAtMs = now,
                    source = "seed"
                ),
                Rule(
                    name = "Queue Tier 2 (worth seeing)",
                    conditionJson = """{"type":"tier_match","tier":2}""",
                    action = "queue",
                    status = "approved",
                    createdAtMs = now + 1,
                    source = "seed"
                ),
                Rule(
                    name = "Suppress Tier 1 (noise)",
                    conditionJson = """{"type":"tier_match","tier":1}""",
                    action = "suppress",
                    status = "approved",
                    createdAtMs = now + 2,
                    source = "seed"
                ),
                Rule(
                    name = "Suppress Tier 0 (invisible)",
                    conditionJson = """{"type":"tier_match","tier":0}""",
                    action = "suppress",
                    status = "approved",
                    createdAtMs = now + 3,
                    source = "seed"
                ),
            )

            // Atomic transaction: all four inserts succeed or none do.
            // Crash inside the transaction → rules are NOT partially inserted.
            transactionRunner {
                seedRules.forEach { ruleDao.insertRule(it) }
            }

            // SharedPreferences write happens AFTER the transaction commits and outside the
            // Room transaction (SharedPreferences is not transactional with Room). The Mutex
            // ensures this write is still serialised relative to other seedIfNeeded callers.
            sharedPreferences.edit()
                .putBoolean(Prefs.SHADE_MODE_SEED_DONE, true)
                .apply()
        }
    }
}
