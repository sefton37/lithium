package ai.talkingrock.lithium

import ai.talkingrock.lithium.ai.WorkScheduler
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class — the Hilt root.
 *
 * Also implements [Configuration.Provider] to supply a custom [HiltWorkerFactory]
 * to WorkManager. This is required when using @HiltWorker-annotated workers.
 *
 * WorkManager's auto-initialization is disabled in AndroidManifest.xml
 * (the androidx.startup InitializationProvider entry is removed for WorkManagerInitializer).
 * Without that manifest change, WorkManager initializes itself with the default factory
 * before this class can provide the Hilt factory, causing a crash.
 *
 * M3: Schedules the [ai.talkingrock.lithium.ai.AiAnalysisWorker] periodic job on startup.
 */
@HiltAndroidApp
class LithiumApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleAiAnalysisWork()
    }

    /**
     * Enqueues the AI analysis periodic work request.
     *
     * Uses [androidx.work.ExistingPeriodicWorkPolicy.KEEP] — if the job is already
     * enqueued (from a previous app launch or boot), it is left unchanged. This prevents
     * the 24-hour window from resetting on every app launch.
     *
     * WorkManager is initialised lazily on first use. The custom [HiltWorkerFactory] is
     * supplied via [workManagerConfiguration], which is read during that first initialisation.
     */
    private fun scheduleAiAnalysisWork() {
        WorkScheduler.scheduleAiAnalysis(WorkManager.getInstance(this))
    }
}
