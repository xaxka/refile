package xa.refile.ui.progress

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameOperationJson
import xa.refile.core.rename.RenameReport
import xa.refile.core.rename.RenameResult
import xa.refile.data.repository.RenameCompletionBus
import xa.refile.worker.RenameWorkScheduler
import xa.refile.worker.RenameWorker
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 执行进度/结果页 ViewModel（计划 §M4 Task 4.3）。
 *
 * 从导航参数取得 workId（[KEY_WORK_ID]），观察 [RenameWorkScheduler.observeWork] 返回的
 * [WorkInfo] 流，把其中的 progress WorkData（[RenameWorker.KEY_PROGRESS_CURRENT]/TOTAL/
 * FILENAME）与 outputData（[RenameWorker.KEY_RESULT_REPORT_PATH] 指向的 cacheDir 文件）
 * 解析成可观察的进度与报告状态。
 *
 * 结果态提供：
 * - [retryFailed]：取报告 [RenameReport.failedOperations] 重新入队一批仅含失败项的操作，
 *   并切换 workId 观察新批次（复用当前页面，不新增回退栈）。
 * - [cancelWork]：调用 [WorkManager.cancelWorkById] 取消当前任务。
 *
 * WorkInfo? 元素可空（work 不存在/被清理时为 null），故 [workInfo] 类型为 StateFlow<WorkInfo?>。
 *
 * 完整 report JSON 不再经 WorkData 传递（会超 10KB 上限），由 [RenameWorker] 写入 cacheDir
 * 文件，本 ViewModel 用 outputData 里的文件名从 [Context.getCacheDir] 读取并解码。
 *
 * 任务 SUCCEEDED 时经 [RenameCompletionBus] 发布 serverId，浏览页据此刷新当前目录，
 * 使重命名后的新文件名在用户返回浏览页时立即反映。
 */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val scheduler: RenameWorkScheduler,
    private val workManager: WorkManager,
    @ApplicationContext private val appContext: Context,
    private val completionBus: RenameCompletionBus,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var workId: UUID = UUID.fromString(
        checkNotNull(savedStateHandle.get<String>(KEY_WORK_ID)) { "缺少 workId 导航参数" }
    )
    private var collectJob: Job? = null

    private val _workInfo = MutableStateFlow<WorkInfo?>(null)
    val workInfo: StateFlow<WorkInfo?> = _workInfo.asStateFlow()

    private val _progressCurrent = MutableStateFlow(0)
    val progressCurrent: StateFlow<Int> = _progressCurrent.asStateFlow()

    private val _progressTotal = MutableStateFlow(0)
    val progressTotal: StateFlow<Int> = _progressTotal.asStateFlow()

    private val _currentFilename = MutableStateFlow<String?>(null)
    val currentFilename: StateFlow<String?> = _currentFilename.asStateFlow()

    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    private val _isCancelled = MutableStateFlow(false)
    val isCancelled: StateFlow<Boolean> = _isCancelled.asStateFlow()

    private val _report = MutableStateFlow<RenameReport?>(null)
    val report: StateFlow<RenameReport?> = _report.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 重试入队所需参数（完成时从 outputData 回传）。 */
    private var retryServerId: Long = -1L
    private var retryBatchName: String? = null

    init {
        startObserving()
    }

    /** 订阅当前 [workId] 的 WorkInfo 流并驱动各派生状态。可被 [retryFailed] 重启。 */
    private fun startObserving() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            scheduler.observeWork(workId).collect { info ->
                _workInfo.value = info
                if (info != null) handleWorkInfo(info)
            }
        }
    }

    /** 从 [WorkInfo] 的 progress/outputData 解析进度与结果。 */
    private suspend fun handleWorkInfo(info: WorkInfo) {
        _progressCurrent.value = info.progress.getInt(RenameWorker.KEY_PROGRESS_CURRENT, 0)
        _progressTotal.value = info.progress.getInt(RenameWorker.KEY_PROGRESS_TOTAL, 0)
        val filename = info.progress.getString(RenameWorker.KEY_PROGRESS_FILENAME)
        _currentFilename.value = filename?.takeIf { it.isNotBlank() }

        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                _isFinished.value = false
                _isCancelled.value = false
            }
            WorkInfo.State.SUCCEEDED -> {
                _isFinished.value = true
                _isCancelled.value = false
                parseReport(info)
                // 通知浏览页该 server 的目录需刷新：重命名后文件名已变，
                // 用户返回浏览页时应看到最新列表而非旧缓存。
                if (retryServerId > 0L) completionBus.notifyCompleted(retryServerId)
            }
            WorkInfo.State.FAILED -> {
                _isFinished.value = true
                _isCancelled.value = false
                val err = info.outputData.getString(RenameWorker.KEY_ERROR)
                _errorMessage.value = err ?: "任务执行失败"
            }
            WorkInfo.State.CANCELLED -> {
                _isFinished.value = true
                _isCancelled.value = true
            }
        }
    }

    /**
     * 从 [WorkInfo] outputData 取 report 文件名，到 [appContext] 的 cacheDir 读取并解码 [RenameReport]。
     * 同时记录重试入队所需的 serverId/batchName。在 IO 线程读文件。
     */
    private suspend fun parseReport(info: WorkInfo) {
        val reportName = info.outputData.getString(RenameWorker.KEY_RESULT_REPORT_PATH)
        val report: RenameReport? = if (!reportName.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(appContext.cacheDir, reportName)
                    if (file.exists()) RenameOperationJson.decodeReport(file.readText()) else null
                }.getOrNull()
            }
        } else {
            null
        }
        _report.value = report
        retryServerId = info.outputData.getLong(RenameWorker.KEY_SERVER_ID, -1L)
        retryBatchName = info.outputData.getString(RenameWorker.KEY_BATCH_NAME)
    }

    /**
     * 重试失败项并切换 [workId] 观察新批次（页面回到执行中态）。
     *
     * - [RenameResult.Failed]：主文件未落地，整体重试（主文件 + 伴随）。
     * - [RenameResult.Partial]：主文件已成功，仅把失败的伴随文件拆为独立操作重试
     *   （与 [xa.refile.core.rename.RenameExecutor.retry] 语义一致）。
     *
     * P2 修复：原实现只取 [RenameReport.failedOperations]（仅 Failed），Partial（主文件
     * 成功但部分伴随文件失败）被排除——失败的伴随文件重命名永久丢失，无法通过重试恢复。
     */
    fun retryFailed() {
        val current = _report.value ?: return
        val retryOps = buildList {
            for ((op, res) in current.results) {
                when (res) {
                    is RenameResult.Failed -> add(op)
                    is RenameResult.Partial -> {
                        for (comp in op.companions) {
                            if (comp.sourcePath in res.failedCompanions) {
                                add(
                                    RenameOperation(
                                        sourcePath = comp.sourcePath,
                                        targetPath = comp.targetPath,
                                        companions = emptyList(),
                                        mediaType = op.mediaType,
                                    ),
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (retryOps.isEmpty() || retryServerId <= 0L) return
        viewModelScope.launch {
            val newWorkId = scheduler.enqueue(
                serverId = retryServerId,
                operations = retryOps,
                batchName = retryBatchName,
            )
            workId = newWorkId
            // 重置结果态，回到执行中态观察新批次。
            _isFinished.value = false
            _isCancelled.value = false
            _report.value = null
            _errorMessage.value = null
            _progressCurrent.value = 0
            _progressTotal.value = retryOps.size
            _currentFilename.value = null
            startObserving()
        }
    }

    /** 取消当前任务（WorkManager 异步处理，本调用立即返回）。 */
    fun cancelWork() {
        workManager.cancelWorkById(workId)
    }

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
    }

    companion object {
        /** 导航参数键：workId 的字符串形式。 */
        const val KEY_WORK_ID = "workId"
    }
}
