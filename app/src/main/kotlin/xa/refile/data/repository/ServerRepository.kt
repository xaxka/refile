package xa.refile.data.repository

import xa.refile.core.webdav.ConnectionResult
import xa.refile.core.webdav.WebDavClient
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.ServerConfigDao
import xa.refile.data.db.ServerConfigEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * 服务器配置仓库（计划 §M1 SubTask 1.3.3）。
 *
 * 职责：
 * - 透传 DAO 的 Flow 观察与单条查询。
 * - 写入/更新时调用 [KeystoreCrypto] 加密密码，确保明文密码不落盘（红线）。
 * - [testConnection] 解密密码后构造 [WebDavClient]，仅做 PROPFIND 连通性测试，
 *   不读取/下载文件内容（红线，重命名仅经 MOVE/MKCOL 完成）。
 *
 * 简化：直接使用 [ServerConfigEntity] 作为领域模型，UI 层后续可自行映射。
 */
class ServerRepository @Inject constructor(
    private val dao: ServerConfigDao,
    private val crypto: KeystoreCrypto,
) {

    /**
     * 共享 OkHttpClient：所有 [clientFor] 构造的 client 复用同一连接池/线程池，
     * 避免频繁新建 client 造成资源泄漏。
     */
    private val sharedClient by lazy { OkHttpClient() }

    /** 观察所有服务器配置（按 updatedAt 倒序）。 */
    fun observeServers(): Flow<List<ServerConfigEntity>> = dao.observeAll()

    /** 按 id 取单条配置。 */
    suspend fun getServer(id: Long): ServerConfigEntity? = dao.getById(id)

    /**
     * 新增服务器配置。明文密码先经 Keystore 加密再落盘。
     *
     * @return 新插入行的 id。
     */
    suspend fun addServer(
        name: String,
        baseUrl: String,
        port: Int?,
        rootPath: String,
        username: String?,
        password: String?,
        authType: String,
        https: Boolean,
    ): Long {
        val encrypted = password?.takeIf { it.isNotBlank() }?.let { crypto.encrypt(it) }
        val entity = ServerConfigEntity(
            name = name,
            baseUrl = baseUrl,
            port = port,
            rootPath = rootPath,
            username = username,
            encryptedPassword = encrypted,
            authType = authType,
            https = https,
        )
        return dao.insert(entity)
    }

    /**
     * 更新服务器配置。
     * - [newPassword] 非空且非空白：重新加密并替换 [ServerConfigEntity.encryptedPassword]。
     * - 否则：保留原密文，仅更新其它字段与 [ServerConfigEntity.updatedAt]。
     */
    suspend fun updateServer(entity: ServerConfigEntity, newPassword: String?) {
        val now = System.currentTimeMillis()
        val updated = if (!newPassword.isNullOrBlank()) {
            entity.copy(
                encryptedPassword = crypto.encrypt(newPassword),
                updatedAt = now,
            )
        } else {
            entity.copy(updatedAt = now)
        }
        dao.update(updated)
    }

    /** 按 id 删除服务器配置。 */
    suspend fun deleteServer(id: Long) {
        dao.deleteById(id)
    }

    /**
     * 测试与目标服务器的连通性。
     *
     * B14: 改为复用 [clientFor] 构造 client，与实际重命名走同一套 client 构造逻辑，
     * 消除"连接测试失败但实际操作成功"的不一致。统一传 "/" 由
     * [WebDavClient] 补末尾斜杠。仅做连通性/认证探测，不下载文件内容。
     */
    suspend fun testConnection(entity: ServerConfigEntity): ConnectionResult {
        val client = clientFor(entity)
        return client.testConnection("/")
    }

    /**
     * 构造已带认证拦截器的 [WebDavClient]（Task 3.4 预览页冲突检测/伴随文件发现复用）。
     *
     * 解密存储的密码，按 [buildFullBaseUrl] 取完整 baseUrl 后构造 [WebDavClient]。
     * 与 [testConnection] 共用同一套 baseUrl 构造逻辑，避免在调用方（预览页 ViewModel）
     * 重复实现。
     *
     * 仅用于预览阶段的 PROPFIND 探测（冲突检测、伴随文件发现），不在此执行 MOVE/MKCOL。
     */
    suspend fun clientFor(entity: ServerConfigEntity): WebDavClient {
        val decryptedPassword = entity.encryptedPassword?.let { crypto.decrypt(it) }
        val fullBaseUrl = buildFullBaseUrl(entity)
        return WebDavClient(fullBaseUrl, entity.username, decryptedPassword, sharedClient)
    }

    /**
     * 取用于构造 [WebDavClient] 的完整 baseUrl。
     *
     * 按测试反馈简化：[ServerConfigEntity.baseUrl] 已存完整 URL（含 scheme/host/port/路径，
     * 如 `https://dav.example.com:8443/dav`），这里直接规范化返回，不再用 https/port 字段拼装。
     * 兼容旧数据：若 baseUrl 缺 scheme（如 `dav.example.com`），按 https 字段补 `https://`/`http://`。
     */
    private fun buildFullBaseUrl(entity: ServerConfigEntity): String {
        val raw = entity.baseUrl.trim()
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw.trimEnd('/')
        } else {
            val scheme = if (entity.https) "https" else "http"
            val host = raw.removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (entity.port != null) "$scheme://$host:${entity.port}" else "$scheme://$host"
        }
    }
}
