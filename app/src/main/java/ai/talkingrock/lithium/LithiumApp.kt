package ai.talkingrock.lithium

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
 */
@HiltAndroidApp
class LithiumApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
