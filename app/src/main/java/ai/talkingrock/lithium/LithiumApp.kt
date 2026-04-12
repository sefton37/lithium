package ai.talkingrock.lithium

import ai.talkingrock.lithium.ai.WorkScheduler
import ai.talkingrock.lithium.api.LithiumApiService
import ai.talkingrock.lithium.service.NotificationChannelRegistry
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
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

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var notificationChannelRegistry: NotificationChannelRegistry

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Register all Lithium-owned notification channels before anything posts a notification.
        notificationChannelRegistry.registerAll()
        val workManager = WorkManager.getInstance(this)
        scheduleAiAnalysisWork(workManager)
        scheduleHealthCheckWork(workManager)
        WorkScheduler.scheduleTierBackfill(workManager)
        startApiService()  // Phase 1: embedded Ktor API server
    }

    /**
     * Enqueues the AI analysis periodic work request.
     *
     * Uses [androidx.work.ExistingPeriodicWorkPolicy.UPDATE] — updates constraints from
     * user preferences on each app launch without resetting the 24-hour period timer.
     *
     * WorkManager is initialised lazily on first use. The custom [HiltWorkerFactory] is
     * supplied via [workManagerConfiguration], which is read during that first initialisation.
     */
    private fun scheduleAiAnalysisWork(workManager: WorkManager) {
        WorkScheduler.scheduleAiAnalysis(workManager, sharedPreferences)
    }

    /**
     * Enqueues the [ai.talkingrock.lithium.ai.HealthCheckWorker] periodic request.
     *
     * Runs every 6 hours with no constraints to ensure the listener-disconnection
     * alert fires regardless of device power state.
     */
    private fun scheduleHealthCheckWork(workManager: WorkManager) {
        WorkScheduler.scheduleHealthCheck(workManager)
    }

    private fun startApiService() {
        // Android 14+ forbids starting a foreground service while the app is
        // in the background (e.g. when Application.onCreate runs because a
        // broadcast woke the process). We tolerate the failure: the service
        // will start on the next foreground launch.
        try {
            startForegroundService(Intent(this, LithiumApiService::class.java))
        } catch (e: IllegalStateException) {
            android.util.Log.w("LithiumApp", "startApiService: deferred (background start not allowed)")
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            android.util.Log.w("LithiumApp", "startApiService: deferred (FGS not allowed)")
        }
    }
}
