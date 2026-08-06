package xa.refile.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨页面重命名完成事件总线。
 *
 * [xa.refile.worker.RenameWorker] 执行成功后由 [xa.refile.ui.progress.ProgressViewModel]
 * 调 [notifyCompleted] 发布完成的 serverId；[xa.refile.ui.browser.BrowserViewModel] 收集
 * [events]，匹配自身 serverId 时刷新当前目录，使重命名后的新文件名立即反映到浏览页。
 *
 * 用 [MutableSharedFlow]（replay=0）：仅"最近一次完成"需要触发刷新，且同一服务器连续
 * 多次完成都能各自触发（不像 StateFlow 会去重相同值）。
 */
@Singleton
class RenameCompletionBus @Inject constructor() {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val events: SharedFlow<Long> = _events

    /** 发布一次重命名完成事件，payload 为目标 serverId。 */
    fun notifyCompleted(serverId: Long) {
        _events.tryEmit(serverId)
    }
}
