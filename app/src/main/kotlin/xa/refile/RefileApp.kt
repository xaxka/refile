package xa.refile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xa.refile.core.backup.HostPresets
import xa.refile.core.backup.HostsSpeedTest
import xa.refile.data.prefs.SettingsRepository
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
 *
 * 启动时自动检测 hosts 必要性：若用户开启了 hosts 优化，且当前网络可直连所有预设
 * TMDB 域名（无需 hosts），则临时禁用 hosts（运行时状态，不持久化），让 OkHttpClient
 * 走系统 DNS，避免不必要的 hosts 绕行。用户在设置页手动测试/开关 hosts 时会重置。
 */
@HiltAndroidApp
class RefileApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var hostsSpeedTest: HostsSpeedTest

    /** 应用级协程作用域，用于启动时的 hosts 自动检测等后台任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        checkHostsNecessityOnStartup()
    }

    /**
     * 启动时检测是否需要 hosts：仅当用户持久化开启了 hosts 时执行。
     *
     * 并发测所有预设 TMDB 域名能否走系统 DNS 直连，全部可达 → 临时禁用 hosts
     * （[SettingsRepository.setRuntimeHostsDisabled]）。
     * 任一失败 → 保持 hosts 启用（运行时禁用状态为 false）。
     * 异常/超时不影响应用启动，最坏情况是 hosts 保持开启（与未检测一致）。
     */
    private fun checkHostsNecessityOnStartup() {
        appScope.launch {
            try {
                val config = settings.hostsConfig.first()
                // 仅当用户开启了 hosts 才检测；关闭状态无需检测。
                if (!config.enabled) return@launch
                val allDirect = HostPresets.DEFAULT_CANDIDATES.all { hostname ->
                    hostsSpeedTest.testDirectConnect(hostname).isDirectAvailable
                }
                if (allDirect) {
                    settings.setRuntimeHostsDisabled(true)
                }
            } catch (_: Throwable) {
                // 检测失败保持默认（hosts 启用），不影响应用启动。
            }
        }
    }
}
