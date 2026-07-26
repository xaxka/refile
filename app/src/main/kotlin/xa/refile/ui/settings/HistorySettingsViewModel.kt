package xa.refile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 历史记录设置 ViewModel。
 *
 * 持有两条保留策略配置：
 * - [maxCount]：历史记录保留条数上限（0=不限）。
 * - [autoClearDays]：自动清理多少天前的历史（0=不清理）。
 *
 * 二者均持久化于 [SettingsRepository]，UI 即时反映；用户改动经 [saveMaxCount] /
 * [saveAutoClearDays] 落盘。[cleanNow] 立即按当前策略执行一次清理。
 */
@HiltViewModel
class HistorySettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    /** 历史记录保留条数上限（0=不限）。 */
    val maxCount: StateFlow<Int> = settings.historyMaxCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    /** 自动清理多少天前的历史（0=不清理）。 */
    val autoClearDays: StateFlow<Int> = settings.historyAutoClearDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    /** 立即清理结果文案（成功/失败），由 Composable 弹 Snackbar。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 保存保留条数上限。 */
    fun saveMaxCount(value: Int) {
        viewModelScope.launch { settings.setHistoryMaxCount(value) }
    }

    /** 保存自动清理天数。 */
    fun saveAutoClearDays(value: Int) {
        viewModelScope.launch { settings.setHistoryAutoClearDays(value) }
    }

    /** 按当前策略立即清理一次过期/超量历史。 */
    fun cleanNow() {
        viewModelScope.launch {
            try {
                historyRepository.cleanupOnStartup()
                _message.value = "已按当前策略清理"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _message.value = "清理失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** 清除提示消息。 */
    fun clearMessage() {
        _message.value = null
    }
}
