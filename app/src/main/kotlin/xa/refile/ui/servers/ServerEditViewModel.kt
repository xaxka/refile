package xa.refile.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.webdav.ConnectionResult
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

/**
 * 添加/编辑服务器页 ViewModel（计划 §M1 SubTask 1.4.2）。
 *
 * 表单字段：别名 / 服务器类型（webdav/openlist）/ 完整 URL / 用户名 / 密码。
 * - URL 字段值存入 [ServerConfigEntity.baseUrl]，已含 scheme/host/port/路径，
 *   仓库与浏览器直接作为完整 baseUrl 使用，不再拼装。
 * - 用户名必填（不支持匿名访问）。
 * - 编辑模式预填时密码留空表示「保留原密码」，明文密码绝不回显（红线）。
 * - 认证方式固定为 auto（WebDAV 自动协商），不再暴露给用户选择。
 *
 * [testConnection] 用当前输入构造临时 [ServerConfigEntity] 调 [ServerRepository.testConnection]，
 * 把 [ConnectionResult] 映射为可读文案。为对新输入密码做连通性测试，密码先经 [KeystoreCrypto]
 * 加密形成临时 entity（加密→仓库内解密为同一明文 round-trip）；UI 层不持久化任何密文。
 */
@HiltViewModel
class ServerEditViewModel @Inject constructor(
    private val repo: ServerRepository,
    private val crypto: KeystoreCrypto,
) : ViewModel() {

    /** 测试连接结果的 UI 投影。 */
    sealed interface TestResultUi {
        data class Success(val message: String = "连接成功") : TestResultUi
        data class Error(val message: String) : TestResultUi
    }

    /** 表单 UI 状态。 */
    data class UiState(
        val id: Long? = null,
        val name: String = "",
        val type: String = "webdav",
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        val isEditing: Boolean = false,
        /** 编辑模式下原实体是否存有密码（决定是否展示「清空密码」开关）。 */
        val hasStoredPassword: Boolean = false,
        /**
         * P2 修复（报告 #13）：显式「清空已存密码」意图。此前密码留空一律保留原密文，
         * 用户无法清除已存密码。勾选后保存时把 encryptedPassword 置 null。
         * 输入新密码会自动取消该意图。
         */
        val clearPassword: Boolean = false,
        val isTesting: Boolean = false,
        val isSaving: Boolean = false,
        val testResult: TestResultUi? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 编辑模式预填时缓存的原始实体，用于"密码留空=保留原密文"分支。 */
    private var loadedEntity: ServerConfigEntity? = null

    fun updateName(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun updateType(value: String) {
        _uiState.update { it.copy(type = value) }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun updateUsername(value: String) {
        _uiState.update { it.copy(username = value) }
    }

    fun updatePassword(value: String) {
        // 输入新密码时自动取消「清空密码」意图（两者互斥，输入优先）。
        _uiState.update {
            it.copy(
                password = value,
                clearPassword = if (value.isNotEmpty()) false else it.clearPassword,
            )
        }
    }

    /** 切换「清空已存密码」开关（仅编辑模式且有已存密码时可切换）。 */
    fun toggleClearPassword() {
        _uiState.update {
            if (it.isEditing && it.hasStoredPassword) it.copy(clearPassword = !it.clearPassword) else it
        }
    }

    /** 编辑模式预填。id 为 null 或 <=0 视为新增。 */
    suspend fun load(id: Long?) {
        if (id == null || id <= 0L) {
            loadedEntity = null
            _uiState.update { it.copy(isEditing = false) }
            return
        }
        val entity = repo.getServer(id) ?: run {
            _uiState.update { it.copy(isEditing = false) }
            return
        }
        loadedEntity = entity
        _uiState.update {
            it.copy(
                id = entity.id,
                name = entity.name,
                type = entity.type,
                baseUrl = entity.baseUrl,
                username = entity.username ?: "",
                password = "", // 明文密码不回显（红线）
                isEditing = true,
                hasStoredPassword = !entity.encryptedPassword.isNullOrBlank(),
                clearPassword = false,
            )
        }
    }

    /** 构造用于"测试连接"的临时实体。密码用当前输入（加密后 round-trip），留空则沿用原密文；
     *  勾选「清空密码」则以无密码状态测试。 */
    private fun buildTempEntity(): ServerConfigEntity {
        val s = _uiState.value
        val (https, port, rootPath) = parseUrlExtras(s.baseUrl)
        val encrypted = if (s.clearPassword) {
            null
        } else if (s.password.isNotEmpty()) {
            crypto.encrypt(s.password)
        } else {
            loadedEntity?.encryptedPassword
        }
        return ServerConfigEntity(
            id = s.id ?: 0L,
            name = s.name,
            type = s.type,
            baseUrl = s.baseUrl.trim().trimEnd('/'),
            port = port,
            rootPath = rootPath,
            username = s.username.trim(),
            encryptedPassword = encrypted,
            https = https,
        )
    }

    /** 用当前输入测试连接，结果写入 [uiState.testResult]。 */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val mapped = try {
                mapResult(repo.testConnection(buildTempEntity()))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                TestResultUi.Error("网络错误：${t.message ?: "未知错误"}")
            }
            _uiState.update { it.copy(isTesting = false, testResult = mapped) }
        }
    }

    private fun mapResult(result: ConnectionResult): TestResultUi = when (result) {
        is ConnectionResult.Success -> TestResultUi.Success()
        is ConnectionResult.AuthFailure ->
            TestResultUi.Error("认证失败（${result.code}），请检查用户名密码")
        is ConnectionResult.NotWebDav -> TestResultUi.Error("目标服务不可用或协议不匹配")
        is ConnectionResult.HttpError -> TestResultUi.Error("HTTP 错误 ${result.code}")
        is ConnectionResult.NetworkError -> TestResultUi.Error("网络错误：${result.message}")
    }

    /**
     * 新增或更新。返回 id；校验失败或仓库异常时抛出，由 UI 捕获展示。
     *
     * 校验：名称非空、URL 合法（含 scheme+host）、用户名非空（不支持匿名）。
     */
    suspend fun save(): Long {
        val s = _uiState.value
        require(s.name.isNotBlank()) { "名称不能为空" }
        val url = s.baseUrl.trim()
        require(url.isNotEmpty()) { "URL 不能为空" }
        require(isValidUrl(url)) { "URL 格式错误（需含 http/https 与主机）" }
        require(s.username.isNotBlank()) { "用户名不能为空（不支持匿名访问）" }

        _uiState.update { it.copy(isSaving = true) }
        try {
            val (https, port, rootPath) = parseUrlExtras(url)
            // baseUrl 存完整 URL（去掉末尾斜杠）；port/https/rootPath 仅为兼容旧 entity 字段
            val normalizedUrl = url.trimEnd('/')
            val newPassword = s.password.ifBlank { null }

            return if (s.isEditing && s.id != null && loadedEntity != null) {
                val entity = loadedEntity!!.copy(
                    name = s.name,
                    type = s.type,
                    baseUrl = normalizedUrl,
                    port = port,
                    rootPath = rootPath,
                    username = s.username.trim(),
                    https = https,
                )
                repo.updateServer(entity, newPassword, clearPassword = s.clearPassword)
                s.id
            } else {
                repo.addServer(
                    name = s.name,
                    baseUrl = normalizedUrl,
                    port = port,
                    rootPath = rootPath,
                    username = s.username.trim(),
                    password = newPassword,
                    type = s.type,
                    https = https,
                )
            }
        } finally {
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    /** 校验 URL：必须有 http/https scheme 与 host。 */
    private fun isValidUrl(url: String): Boolean = try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: URISyntaxException) {
        false
    }

    /**
     * 从完整 URL 解析出兼容字段：
     * - [https]：scheme 为 https 时 true，否则 false。
     * - [port]：URL 显式带端口时返回该端口，否则 null。
     * - [rootPath]：URL 中 host 之后到末尾的路径（已含前导 /，无则 "/"）。
     *
     * 这些字段仅为兼容旧 [ServerConfigEntity] schema 保留，仓库与浏览器实际只用 [ServerConfigEntity.baseUrl]。
     */
    private fun parseUrlExtras(url: String): Triple<Boolean, Int?, String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Triple(true, null, "/")
        val https = uri.scheme?.lowercase() != "http"
        val port = uri.port.takeIf { it > 0 }
        val rawPath = uri.rawPath ?: ""
        val rootPath = if (rawPath.isBlank() || rawPath == "/") "/" else rawPath
        return Triple(https, port, rootPath)
    }
}
