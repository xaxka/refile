package xa.refile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.data.db.RenameBatchEntity
import xa.refile.data.db.RenameEntryEntity
import xa.refile.data.repository.HistoryRepository
import xa.refile.data.repository.RevertResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 历史记录页 ViewModel（计划 §M5 SubTask 5.1.3）。
 *
 * 状态：
 * - [batches]：所有批次（按 createdAt 倒序，[HistoryRepository.observeBatches] 直供）。
 * - [selectedBatch] / [selectedEntries]：当前选中批次的详情。
 * - [reverting]：撤销进行中标志（驱动 UI 禁用按钮）。
 * - [revertProgress]：撤销进度（current/total），驱动底部 LinearProgressIndicator 实时刷新。
 * - [revertResult]：最近一次撤销结果（成功 N/M 或失败原因），由 UI 弹 Snackbar。
 *
 * [selectBatch] / [revertBatch] / [clearRevertResult] 为 UI 调用入口。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: HistoryRepository,
) : ViewModel() {

    val batches: StateFlow<List<RenameBatchEntity>> = repo.observeBatches()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _selectedBatch = MutableStateFlow<RenameBatchEntity?>(null)
    val selectedBatch: StateFlow<RenameBatchEntity?> = _selectedBatch.asStateFlow()

    private val _selectedEntries = MutableStateFlow<List<RenameEntryEntity>>(emptyList())
    val selectedEntries: StateFlow<List<RenameEntryEntity>> = _selectedEntries.asStateFlow()

    private val _reverting = MutableStateFlow(false)
    val reverting: StateFlow<Boolean> = _reverting.asStateFlow()

    /** 撤销进度：撤销进行中实时刷新；非撤销态为 null（UI 不渲染进度条）。 */
    private val _revertProgress = MutableStateFlow<RevertProgress?>(null)
    val revertProgress: StateFlow<RevertProgress?> = _revertProgress.asStateFlow()

    private val _revertResult = MutableStateFlow<RevertResult?>(null)
    val revertResult: StateFlow<RevertResult?> = _revertResult.asStateFlow()

    /**
     * 选中某批次：加载批次详情与条目列表。
     */
    fun selectBatch(id: Long) {
        viewModelScope.launch {
            _selectedBatch.value = repo.getBatch(id)
            _selectedEntries.value = repo.getEntries(id)
        }
    }

    /** 退出详情视图（清空选中态）。 */
    fun clearSelection() {
        _selectedBatch.value = null
        _selectedEntries.value = emptyList()
    }

    /**
     * 撤销整批：调用 [HistoryRepository.revertBatch]，过程中置 [reverting]=true 并
     * 通过 [revertProgress] 实时上报 current/total，结果写入 [revertResult]，并刷新当前选中批次。
     */
    fun revertBatch(id: Long) {
        viewModelScope.launch {
            _reverting.value = true
            _revertProgress.value = RevertProgress(current = 0, total = 0)
            try {
                val result = repo.revertBatch(id) { current, total ->
                    _revertProgress.value = RevertProgress(current = current, total = total)
                }
                _revertResult.value = result
                // 仅刷新批次列表的 reverted 标记（由 batches Flow 自动反映），
                // 不覆盖用户当前选中批次，避免撤销他批次时篡改选中态。
                // B15: 但若撤销的正是当前选中批次，需刷新选中态的 isReverted，
                // 否则用户可重复点击撤销按钮（DAO 端虽幂等，但 UI 应置灰禁用）。
                if (result is RevertResult.Success || result is RevertResult.Partial) {
                    if (_selectedBatch.value?.id == id) {
                        val fresh = repo.getBatch(id)
                        if (fresh != null) _selectedBatch.value = fresh
                    }
                }
            } finally {
                _reverting.value = false
                _revertProgress.value = null
            }
        }
    }

    /** 清除一次性撤销结果（Snackbar 消费后调用）。 */
    fun clearRevertResult() {
        _revertResult.value = null
    }

    /** 把 [RevertResult] 转成给用户看的简短文案。 */
    fun revertResultMessage(result: RevertResult): String = when (result) {
        is RevertResult.Success -> "已回滚 ${result.rolledBack}/${result.total} 条"
        is RevertResult.Partial -> {
            val head = "已回滚 ${result.rolledBack}/${result.total} 条，失败 ${result.failedEntries.size} 项"
            val tail = result.failedEntries.take(3).joinToString("\n") { "· ${it.targetPath.ifBlank { it.sourcePath }}" }
            if (tail.isBlank()) head else "$head\n$tail"
        }
        is RevertResult.Failure -> result.reason
    }

    /** 把 [RenameEntryEntity.status] 映射为 UI 状态枚举（驱动状态图标着色）。 */
    fun entryStatus(status: String): EntryStatus = when (status) {
        "SUCCESS" -> EntryStatus.SUCCESS
        "PARTIAL" -> EntryStatus.PARTIAL
        "FAILED" -> EntryStatus.FAILED
        "SKIPPED" -> EntryStatus.SKIPPED
        else -> EntryStatus.SKIPPED
    }

    /** 条目状态枚举。 */
    enum class EntryStatus { SUCCESS, PARTIAL, FAILED, SKIPPED }

    /** 撤销进度快照。current=0 表示尚未开始首条；total=0 表示无需撤销（前置校验已拦截）。 */
    data class RevertProgress(val current: Int, val total: Int)
}
