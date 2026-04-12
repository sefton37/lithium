package ai.talkingrock.lithium.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves an Android package name to the human-facing label Android itself
 * uses (launcher / app drawer / Play Store). Wraps [AppNames.displayName] so
 * callers without a Context (workers, background generators) can still get
 * the authoritative label via a single injected dependency.
 *
 * Results are cached because [android.content.pm.PackageManager.loadLabel]
 * does a binder round-trip per call and we fan out per notification during
 * report generation.
 */
@Singleton
class AppLabelResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<String, String>()

    @Synchronized
    fun label(packageName: String): String =
        cache.getOrPut(packageName) { AppNames.displayName(context, packageName) }
}
