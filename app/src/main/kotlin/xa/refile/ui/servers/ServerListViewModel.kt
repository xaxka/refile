package xa.refile.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 服务器列表页 ViewModel（计划 §M1 SubTask 1.4.1）。
 *
 * - 暴露 [servers] 作为按仓库排序的服务器配置列表（WhileSubscribed(5s) 缓存）。
 * - [deleteServer] 在 viewModelScope 中转发到仓库；UI 层负责删除前二次确认。
 */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val repo: ServerRepository,
) : ViewModel() {

    val servers: StateFlow<List<ServerConfigEntity>> =
        repo.observeServers()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    fun deleteServer(id: Long) {
        viewModelScope.launch { repo.deleteServer(id) }
    }
}
