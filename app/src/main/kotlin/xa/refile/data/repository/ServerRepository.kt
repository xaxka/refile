package xa.refile.data.repository

import xa.refile.core.openlist.OpenListClient
import xa.refile.core.webdav.ConnectionResult
import xa.refile.core.webdav.FileClient
import xa.refile.core.webdav.WebDavClient
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.ServerConfigDao
import xa.refile.data.db.ServerConfigEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 服务器配置仓库（计划 §M1 SubTask 1.3.3）。
 *
 * 职责：
 * - 透传 DAO 的 Flow 观察与单条查询。
 * - 写入/更新时调用 [KeystoreCrypto] 加密密码，确保明文密码不落盘（红线）。
 * - [clientFor] 按 [ServerConfigEntity.type] 构造对应的 [FileClient]（WebDAV 或 OpenList）。
 * - [testConnection] 解密密码后构造 client，仅做连通性/认证探测。
 *
 * OkHttp 连接池：共享单例 [OkHttpClient]，OkHttp 内部按域名自动分池管理连接，
 * 无需按 serverId 手动隔离。
 */
class ServerRepository @Inject constructor(
    private val dao: ServerConfigDao,
    private val crypto: KeystoreCrypto,
) {

    /**
     * 共享 OkHttpClient：OkHttp 的 ConnectionPool 按 host 内部隔离，多服务器共享同一实例
     * 不会导致连接争用。需要独立并发上限时经 [clientFor] 的 maxRequestsPerHost 参数
     * 用 newBuilder() 派生（共享连接池、独立 Dispatcher），不修改本实例的全局配置。
     */
    private val sharedClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 观察所有服务器配置（按 updatedAt 倒序）。 */
    fun observeServers(): Flow<List<ServerConfigEntity>> = dao.observeAll()

    /** 一次性查询全部服务器配置（事务内使用；withTransaction 中收集 Flow 会持写锁等读锁，有死锁/ANR 风险）。 */
    suspend fun getAllServers(): List<ServerConfigEntity> = dao.getAll()

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
        type: String,
        https: Boolean,
    ): Long {
        val encrypted = password?.takeIf { it.isNotBlank() }?.let { crypto.encrypt(it) }
        val entity = ServerConfigEntity(
            name = name,
            type = type,
            baseUrl = baseUrl,
            port = port,
            rootPath = rootPath,
            username = username,
            encryptedPassword = encrypted,
            https = https,
        )
        return dao.insert(entity)
    }

    /**
     * 更新服务器配置。
     * - [clearPassword] 为 true：清除已存密码（[ServerConfigEntity.encryptedPassword] 置 null）。
     * - [newPassword] 非空且非空白：重新加密并替换 [ServerConfigEntity.encryptedPassword]。
     * - 否则：保留原密文，仅更新其它字段与 [ServerConfigEntity.updatedAt]。
     *
     * P2 修复（报告 #13）：新增 [clearPassword] 显式清空入口。此前密码留空一律
     * 视为「保留原密文」，用户一旦设置密码便无法清除（只能删除重建服务器）。
     */
    suspend fun updateServer(entity: ServerConfigEntity, newPassword: String?, clearPassword: Boolean = false) {
        val now = System.currentTimeMillis()
        val updated = when {
            clearPassword -> entity.copy(encryptedPassword = null, updatedAt = now)
            !newPassword.isNullOrBlank() -> entity.copy(
                encryptedPassword = crypto.encrypt(newPassword),
                updatedAt = now,
            )
            else -> entity.copy(updatedAt = now)
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
     * 复用 [clientFor] 构造 client，与实际重命名走同一套 client 构造逻辑，
     * 消除"连接测试失败但实际操作成功"的不一致。仅做连通性/认证探测，不下载文件内容。
     */
    suspend fun testConnection(entity: ServerConfigEntity): ConnectionResult {
        val client = clientFor(entity)
        return client.testConnection("/")
    }

    /**
     * 按 [ServerConfigEntity.type] 构造对应的 [FileClient]（WebDAV 或 OpenList）。
     *
     * 解密存储的密码，按 [buildFullBaseUrl] 取完整 baseUrl 后构造 client。
     * 所有 server 共享同一个 [OkHttpClient]（OkHttp ConnectionPool 内部按 host 隔离）；
     * 传入 [maxRequestsPerHost] 时经 newBuilder() 派生带独立 Dispatcher 的 client
     * （共享连接池），仅影响本次返回的 client。
     */
    suspend fun clientFor(
        entity: ServerConfigEntity,
        maxRequestsPerHost: Int? = null,
    ): FileClient {
        // P2 修复：原实现直接改共享单例的 dispatcher.maxRequestsPerHost——全局生效且
        // 「粘住」：RenameWorker/HistoryRepository 按并发设置调用后，后续不带参数的调用
        // （浏览/预览/连接测试）也继承该上限，跨服务器互相干扰（并发设为 1 时浏览变串行）。
        // 修复：newBuilder() 派生共享连接池、独立 Dispatcher 的 client，作用域仅限本次调用。
        val client = if (maxRequestsPerHost != null) {
            val perHost = maxRequestsPerHost.coerceAtLeast(1)
            sharedClient.newBuilder()
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        // maxRequests 需 >= maxRequestsPerHost，否则 host 上限被全局上限截断。
                        this.maxRequests = maxOf(sharedClient.dispatcher.maxRequests, perHost)
                        this.maxRequestsPerHost = perHost
                    },
                )
                .build()
        } else {
            sharedClient
        }
        val decryptedPassword = entity.encryptedPassword?.let { crypto.decrypt(it) }
        val fullBaseUrl = buildFullBaseUrl(entity)
        return if (entity.type == "openlist") {
            OpenListClient.create(
                baseUrl = fullBaseUrl,
                username = entity.username,
                password = decryptedPassword,
                client = client,
            )
        } else {
            WebDavClient(fullBaseUrl, entity.username, decryptedPassword, client)
        }
    }

    /**
     * 取用于构造 client 的完整 baseUrl。
     *
     * [ServerConfigEntity.baseUrl] 已存完整 URL（含 scheme/host/port/路径），
     * 直接规范化返回。兼容旧数据：缺 scheme 时补 https://。
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
