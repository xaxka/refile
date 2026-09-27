package xa.refile.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 服务器列表页 ViewModel（计划 §M1 SubTask 1.4.1）。
 *
 * - 暴露 [servers] 作为按仓库排序的服务器配置列表（WhileSubscribed(5s) 缓存）。
 * - [passwordUndecryptable]：Keystore 失效导致已存密码无法解密的服务器 id 集
 *   （P2-3，审查报告 2026-09-25——设备迁移/ROM 更换后解密失败，列表页醒目标记，
 *   提示重新输入密码；探测在 IO 线程执行）。
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

    /** P2-3：Keystore 失效、已存密码无法解密的服务器 id 集合。 */
    val passwordUndecryptable: StateFlow<Set<Long>> =
        repo.observeServers()
            .map { list -> list.filterNot { repo.isPasswordDecryptable(it) }.map { it.id }.toSet() }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet(),
            )

    fun deleteServer(id: Long) {
        viewModelScope.launch { repo.deleteServer(id) }
    }
}
