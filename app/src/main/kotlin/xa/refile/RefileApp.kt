package xa.refile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * - `@HiltAndroidApp` triggers Hilt's code generation and dependency container.
 * - Implements [Configuration.Provider] so WorkManager picks up the
 *   [HiltWorkerFactory] (workers can use `@HiltWorker` + `@AssistedInject`).
 *
 * The default `WorkManagerInitializer` from `androidx.startup` is removed in the
 * manifest (see `AndroidManifest.xml`); when an app implements
 * `Configuration.Provider`, WorkManager defers initialization until first use
 * and consults this configuration. No explicit `WorkManager.initialize(...)`
 * call is needed in [onCreate].
 */
@HiltAndroidApp
class RefileApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
