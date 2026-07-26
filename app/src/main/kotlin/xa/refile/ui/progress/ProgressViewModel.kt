package xa.refile.ui.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import xa.refile.core.rename.RenameOperationJson
import xa.refile.core.rename.RenameReport
import xa.refile.worker.RenameWorkScheduler
import xa.refile.worker.RenameWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 执行进度/结果页 ViewModel（计划 §M4 Task 4.3）。
 *
 * 从导航参数取得 workId（[KEY_WORK_ID]），观察 [RenameWorkScheduler.observeWork] 返回的
 * [WorkInfo] 流，把其中的 progress WorkData（[RenameWorker.KEY_PROGRESS_CURRENT]/TOTAL/
 * FILENAME）与 outputData（[RenameWorker.KEY_RESULT_REPORT_JSON]）解析成可观察的进度与
 * 报告状态。
 *
 * 结果态提供：
 * - [retryFailed]：取报告 [RenameReport.failedOperations] 重新入队一批仅含失败项的操作，
 *   并切换 workId 观察新批次（复用当前页面，不新增回退栈）。
 * - [cancelWork]：调用 [WorkManager.cancelWorkById] 取消当前任务。
 *
 * WorkInfo? 元素可空（work 不存在/被清理时为 null），故 [workInfo] 类型为 StateFlow<WorkInfo?>。
 */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val scheduler: RenameWorkScheduler,
    private val workManager: WorkManager,
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
    private fun handleWorkInfo(info: WorkInfo) {
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

    /** 解码完整报告 JSON，并记录重试入队所需的 serverId/batchName。 */
    private fun parseReport(info: WorkInfo) {
        val json = info.outputData.getString(RenameWorker.KEY_RESULT_REPORT_JSON)
        _report.value = if (!json.isNullOrBlank()) {
            try {
                RenameOperationJson.decodeReport(json)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        retryServerId = info.outputData.getLong(RenameWorker.KEY_SERVER_ID, -1L)
        retryBatchName = info.outputData.getString(RenameWorker.KEY_BATCH_NAME)
    }

    /**
     * 仅重试失败项：取报告 [RenameReport.failedOperations] 重新入队一批只含失败项的操作，
     * 然后切换 [workId] 观察新批次（页面回到执行中态）。
     */
    fun retryFailed() {
        val current = _report.value ?: return
        val failedOps = current.failedOperations.map { it.first }
        if (failedOps.isEmpty() || retryServerId <= 0L) return
        val newWorkId = scheduler.enqueue(
            serverId = retryServerId,
            operations = failedOps,
            batchName = retryBatchName,
        )
        workId = newWorkId
        // 重置结果态，回到执行中态观察新批次。
        _isFinished.value = false
        _isCancelled.value = false
        _report.value = null
        _errorMessage.value = null
        _progressCurrent.value = 0
        _progressTotal.value = failedOps.size
        _currentFilename.value = null
        startObserving()
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
