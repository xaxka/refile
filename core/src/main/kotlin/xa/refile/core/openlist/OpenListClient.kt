package xa.refile.core.openlist

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import xa.refile.core.webdav.ConnectionResult
import xa.refile.core.webdav.FileClient
import xa.refile.core.webdav.WebDavEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException

/**
 * OpenList（AList 兼容）文件操作客户端 —— WebDAV 之外的第二种后端（实现 [FileClient]）。
 *
 * 登录端点严格对标规范 https://fox.oplist.org/364155678e0.md ：`POST /api/auth/login` 以
 * `{username, password, otp_code?}` 换取 JWT token，token 经 [OpenListAuthInterceptor] 直接放入
 * `Authorization` 头（无 `Bearer` 前缀）。fs 操作走 `/api/fs/{list,mkdir,rename,move}`。
 *
 * 设计要点：
 * - **token 缓存 + 401 续期**：[login] 成功后缓存 token；fs 操作遇 401 自动清空 token、重新登录并
 *   重试一次（[withAuthRetry]），避免长会话内 token 过期导致整批失败。
 * - **双重状态判定**：OpenList 实际 HTTP 恒 200、由响应体 `code` 标识成败；规范将登录失败标为
 *   HTTP 400。[callApi] 同时处理 HTTP 非 2xx 与 `code != 200` 两种情形。
 * - **匿名访问**：[username] 为空时跳过登录，token 保持 null，fs 请求不带 `Authorization` 头，
 *   适用于公开目录的服务器。
 * - **move 语义**：OpenList 无单次「跨目录 + 改名」原子操作。同目录改名走 `/api/fs/rename`；
 *   跨目录先 `/api/fs/move`（保留原基名）再按需 `/api/fs/rename` 改名（[doMove]）。
 *   `overwrite` 参数为尽力而为：OpenList 无显式覆盖开关，目标已存在时服务器返回错误 → [move] 返回
 *   false（与 [xa.refile.core.webdav.WebDavClient.move] 失败语义一致）。
 * - **mkcol 幂等**：对已存在目录返回的 500「already exist」视为幂等成功（对齐 WebDAV 405 语义）。
 *
 * 红线：仅经 list/rename/move/mkdir 完成列目录与重命名，不下载文件内容。
 *
 * @param api       Retrofit 服务（由 [create] 构造，注入带认证拦截器的 client）。
 * @param tokenHolder 当前 token 共享容器（与 [OpenListAuthInterceptor] 同一实例）。
 * @param json      用于解析错误体的 Json 实例。
 * @param username  用户名（可空 → 匿名）。
 * @param password  密码（可空）。
 * @param otpCode   两步验证码（可空）。
 */
class OpenListClient internal constructor(
    private val api: OpenListApi,
    private val tokenHolder: TokenHolder,
    private val json: Json,
    private val username: String?,
    private val password: String?,
    private val otpCode: String?,
) : FileClient {

    /** B2 修复：串行化登录，防止并发 401 重试导致多重登录风暴。 */
    private val loginMutex = Mutex()

    /**
     * 用户登录（规范 §/api/auth/login）。
     *
     * 以 `{username, password, otp_code?}` 换取 JWT token 并缓存。成功返回 token 字符串。
     * - 匿名（[username] 为空）：直接返回空串，不发起请求。
     * - 规范 HTTP 400 / OpenList `code != 200`：抛 [OpenListAuthException]（含 code 与 message）。
     *
     * @return JWT token（匿名时为空串）。
     */
    suspend fun login(): String = loginInternal()

    /**
     * 列目录。
     *
     * - Depth 0：返回目录自身（1 条）。
     * - Depth 1：返回目录自身 + 直接子项。
     *
     * 调用 `/api/fs/list`；空目录的 `content` 为 null → 仅返回自身。失败（非 200 code / HTTP 错误）
     * 抛 [OpenListException]（401 抛 [OpenListAuthException]），与 [WebDavClient.propfind] 抛
     * [xa.refile.core.webdav.WebDavException] 的契约对齐，调用方可据异常区分「读取失败」与「空目录」。
     */
    override suspend fun propfind(path: String, depth: Int): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val normalized = normalizePath(path)
        val data = withAuthRetry {
            val body = callApi { api.list(FsListRequest(path = normalized)) }
            body.data ?: FsListData()
        }
        val self = selfEntry(normalized)
        if (depth <= 0) {
            listOf(self)
        } else {
            listOf(self) + (data.content ?: emptyList()).map { it.toEntry(normalized) }
        }
    }

    /**
     * 重命名/移动资源。
     *
     * 成功返回 true，失败返回 false（不抛异常，与 [WebDavClient.move] 一致）。源==目标直接返回 true。
     * 跨目录 + 改名需两次请求（move + rename），任一失败整体返回 false。
     */
    override suspend fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (normalizePath(fromPath) == normalizePath(toPath)) return@withContext true
            try {
                withAuthRetry { doMove(fromPath, toPath) }
                true
            } catch (e: OpenListAuthException) {
                false
            } catch (e: OpenListException) {
                false
            } catch (e: IOException) {
                false
            }
        }

    /**
     * 建目录（幂等）。成功（200）返回 true；已存在（500/409 含 "exist"）视为幂等成功返回 true；
     * 其余失败返回 false。
     */
    override suspend fun mkcol(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            withAuthRetry { callApi { api.mkdir(FsMkdirRequest(normalizePath(path))) } }
            true
        } catch (e: OpenListAuthException) {
            false
        } catch (e: OpenListException) {
            // B11 修复：精确匹配 "already exists" / "object exists" 等，
            // 避免误匹配 "internal server error" 中的 "exist" 子串。
            // 常见 500: "object already exists" / 409: "folder exists"
            val msg = e.message.lowercase()
            (e.code == 500 || e.code == 409) &&
                (msg.contains("already exists") || msg.contains("object exists") || msg.contains("folder exists") || msg.contains("directory exists"))
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 连通性 + 认证探测。
     *
     * 流程：有凭据则先 [loginInternal]（验证账号密码），再 `/api/fs/list` 探测 [path]。
     * - 登录失败 / list 401 → [ConnectionResult.AuthFailure]。
     * - list 405/501 → [ConnectionResult.NotWebDav]（端点不被支持）。
     * - list 其它非 200 → [ConnectionResult.HttpError]。
     * - 网络异常 → [ConnectionResult.NetworkError]。
     * - 成功 → [ConnectionResult.Success]（附带目录自身条目）。
     *
     * 不下载文件内容（红线）。
     */
    override suspend fun testConnection(path: String): ConnectionResult = withContext(Dispatchers.IO) {
        val normalized = normalizePath(path)
        try {
            if (!username.isNullOrBlank()) loginInternal() else tokenHolder.token = null
            callApi { api.list(FsListRequest(path = normalized)) }
            ConnectionResult.Success(selfEntry(normalized))
        } catch (e: OpenListAuthException) {
            ConnectionResult.AuthFailure(e.code)
        } catch (e: OpenListException) {
            // 401 一律经 [callApi] 转为 [OpenListAuthException] 在上一分支处理，此处仅剩非 401。
            when (e.code) {
                405, 501 -> ConnectionResult.NotWebDav(e.code)
                else -> ConnectionResult.HttpError(e.code)
            }
        } catch (e: IOException) {
            ConnectionResult.NetworkError(e.message ?: "network error")
        }
    }

    // -----------------------------------------------------------------------------------------
    // 登录与 token 续期
    // -----------------------------------------------------------------------------------------

    /** 登录并缓存 token；匿名时直接返回空串不发请求。失败抛 [OpenListAuthException]。 */
    private suspend fun loginInternal(): String = withContext(Dispatchers.IO) {
        if (username.isNullOrBlank()) {
            tokenHolder.token = null
            return@withContext ""
        }
        // B2 修复：用 Mutex 串行化登录，防止并发 401 重试时多个协程同时 login。
        loginMutex.withLock {
            // Double-check：持锁后再次确认 token 仍为空（可能其他协程刚刚登录成功）。
            val existing = tokenHolder.token
            if (existing != null) return@withLock existing
            // 登录端点失败（规范 HTTP 400 / OpenList code 错误）一律视为认证失败：
            // callApi 对非 401 的错误抛 [OpenListException]，此处统一转 [OpenListAuthException]。
            val body = try {
                callApi { api.login(LoginRequest(username, password ?: "", otpCode)) }
            } catch (e: OpenListException) {
                throw OpenListAuthException(e.code, e.message)
            }
            val token = body.data?.token
            if (token.isNullOrBlank()) {
                throw OpenListAuthException(body.code, "login failed: no token")
            }
            tokenHolder.token = token
            token
        }
    }

    /**
     * 确保已登录（token 已缓存）；匿名则跳过。供 fs 操作在首次调用前补登录。
     */
    private suspend fun ensureLoggedIn() {
        if (tokenHolder.token == null && !username.isNullOrBlank()) loginInternal()
    }

    /**
     * 执行需认证的 fs 操作，遇 401 自动续期重试一次。
     *
     * - 首次：[ensureLoggedIn] 后执行 [block]。
     * - block 抛 [OpenListAuthException]（401）：清空 token、重新登录、再执行一次 block。
     * - 重新登录失败（凭据错误）→ [loginInternal] 抛 [OpenListAuthException] 向上传播。
     * - 匿名场景：[ensureLoggedIn] 跳过；block 若 401 → 重试一次（仍 401 → 抛出，由调用方处理）。
     */
    private suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        ensureLoggedIn()
        return try {
            block()
        } catch (e: OpenListAuthException) {
            // B2 修复：清空 token 后串行化重新登录，防止并发 401 引发多重 login 风暴。
            tokenHolder.token = null
            loginInternal()
            // 持锁期间其他协程可能已刷新 token 并重试成功，此处仍按原逻辑重试一次 block。
            block()
        }
    }

    // -----------------------------------------------------------------------------------------
    // move 实现
    // -----------------------------------------------------------------------------------------

    /**
     * 执行移动/重命名（已保证源 != 目标）。
     *
     * - 同父目录：`/api/fs/rename`（path=源, name=新基名）。
     * - 跨父目录：`/api/fs/move`（src_dir/dst_dir/names=[源基名]，保留原基名落到目标父目录），
     *   若源基名 != 目标基名，再 `/api/fs/rename` 改名。
     *
     * P2 修复：OpenList 无「跨目录 + 改名」原子操作，原实现 move 成功后 rename 失败时
     * 文件停留在中间路径（dstParent/源基名）——源路径已不存在，调用方（RenameExecutor/重试）
     * 按源路径重做必然再失败，进入不可恢复状态。修复：rename 失败时补偿回滚——尽力把文件
     * move 回源目录，恢复初始状态使整体可重试；回滚自身失败（网络中断等）时吞掉异常、
     * 保留原始错误向上抛出（文件留在中间路径，至少 move() 如实返回 false）。
     */
    private suspend fun doMove(fromPath: String, toPath: String) {
        val from = normalizePath(fromPath)
        val to = normalizePath(toPath)
        val srcParent = parentDir(from)
        val dstParent = parentDir(to)
        val srcName = basename(from)
        val dstName = basename(to)
        if (srcParent == dstParent) {
            callApi { api.rename(FsRenameRequest(path = from, name = dstName)) }
        } else {
            callApi { api.move(FsMoveRequest(srcDir = srcParent, dstDir = dstParent, names = listOf(srcName))) }
            if (srcName != dstName) {
                val intermediate = joinPath(dstParent, srcName)
                try {
                    callApi { api.rename(FsRenameRequest(path = intermediate, name = dstName)) }
                } catch (e: Exception) {
                    // 补偿回滚：把文件从中间路径移回源目录；失败不掩盖原始异常。
                    runCatching {
                        callApi {
                            api.move(FsMoveRequest(srcDir = dstParent, dstDir = srcParent, names = listOf(srcName)))
                        }
                    }
                    throw e
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 响应统一处理
    // -----------------------------------------------------------------------------------------

    /**
     * 统一处理 [Response]：HTTP 非 2xx 或业务 `code != 200` 均转异常。
     *
     * - HTTP 非 2xx：尽力解析错误体取 message（解析失败回退 `HTTP {code}`）；401 → [OpenListAuthException]，
     *   其余 → [OpenListException]。
     * - HTTP 2xx：`code == 401` → [OpenListAuthException]；`code != 200` → [OpenListException]；
     *   成功返回响应体（含 `data`，调用方按需取用）。
     */
    private suspend fun <T> callApi(block: suspend () -> Response<OpenListResponse<T>>): OpenListResponse<T> {
        val resp = block()
        if (!resp.isSuccessful) {
            val code = resp.code()
            val msg = parseErrorMessage(resp) ?: "HTTP $code"
            if (code == 401) throw OpenListAuthException(401, msg)
            throw OpenListException(code, msg)
        }
        val body = resp.body() ?: throw OpenListException(0, "empty response body")
        if (body.code == 401) throw OpenListAuthException(401, body.message ?: "unauthorized")
        if (body.code != 200) throw OpenListException(body.code, body.message ?: "openlist error")
        return body
    }

    /** 从 HTTP 错误响应体解析 `message`（容错：非 JSON 时返回 null）。 */
    private fun parseErrorMessage(resp: Response<*>): String? {
        val raw = runCatching { resp.errorBody()?.string() }.getOrNull() ?: return null
        return runCatching {
            json.decodeFromString(OpenListResponse.serializer(JsonElement.serializer()), raw).message
        }.getOrNull()
    }

    // -----------------------------------------------------------------------------------------
    // 映射与路径工具
    // -----------------------------------------------------------------------------------------

    /** 构造目录自身的 [WebDavEntry]（href=路径, displayName=基名或 "/", isCollection=true）。 */
    private fun selfEntry(normalized: String): WebDavEntry =
        WebDavEntry(
            href = normalized,
            displayName = basename(normalized).ifEmpty { normalized },
            isCollection = true,
        )

    /** OpenListFile → WebDavEntry（href 由父路径 + name 拼装）。 */
    private fun OpenListFile.toEntry(parentPath: String): WebDavEntry = WebDavEntry(
        href = joinPath(parentPath, name),
        displayName = name,
        isCollection = isDir == true,
        contentLength = size,
        lastModified = modified,
        creationDate = created,
        contentType = null,
    )

    /** 规范化路径：补前导 `/`、折叠重复斜杠、去末尾斜杠（根 `/` 保留）。 */
    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        val collapsed = path.trim().replace(Regex("/+"), "/")
        val withLead = if (collapsed.startsWith("/")) collapsed else "/$collapsed"
        return if (withLead.length > 1) withLead.trimEnd('/') else "/"
    }

    /** 取父目录；根返回 `/`。 */
    private fun parentDir(path: String): String {
        val norm = normalizePath(path)
        if (norm == "/") return "/"
        val idx = norm.lastIndexOf('/')
        return if (idx <= 0) "/" else norm.substring(0, idx)
    }

    /** 取基名；根返回空串。 */
    private fun basename(path: String): String =
        normalizePath(path).trimEnd('/').substringAfterLast('/')

    /** 拼接目录与名称为绝对路径；根目录下直接 `/name`。 */
    private fun joinPath(dir: String, name: String): String =
        if (dir == "/") "/$name" else "$dir/$name"

    companion object {
        // B1 修复：未注入 client 时的共享兜底 client（复用同一连接池/线程池）。
        private val DEFAULT_SHARED_CLIENT = OkHttpClient()

        /**
         * 构建 [OpenListClient]：组装 Retrofit，挂上 kotlinx-serialization converter
         * （`ignoreUnknownKeys=true; coerceInputValues=true`）与 [OpenListAuthInterceptor]。
         *
         * [baseUrl] 缺末尾 `/` 时自动补齐（Retrofit 要求）。注入的 [client] 配置（DNS/超时等）保留，
         * 仅追加认证拦截器。
         */
        fun create(
            baseUrl: String,
            username: String?,
            password: String?,
            otpCode: String? = null,
            client: OkHttpClient? = null,
        ): OpenListClient {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
            val tokenHolder = TokenHolder()
            // B1 修复：client 为 null 时用共享兜底 client，避免每次实例化新建独立连接池。
            val baseClient = client ?: DEFAULT_SHARED_CLIENT
            val authClient = baseClient.newBuilder()
                .addInterceptor(OpenListAuthInterceptor(tokenHolder))
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(baseUrl))
                .client(authClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return OpenListClient(
                api = retrofit.create(OpenListApi::class.java),
                tokenHolder = tokenHolder,
                json = json,
                username = username,
                password = password,
                otpCode = otpCode,
            )
        }

        /** 确保 baseUrl 以 `/` 结尾（Retrofit 要求）。 */
        private fun ensureTrailingSlash(baseUrl: String): String =
            if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }
}

/**
 * OpenList 业务异常（非 200 code / HTTP 非 2xx，且非 401）。
 *
 * @property code    业务码或 HTTP 状态码。
 * @property message 服务器返回或推断的消息。
 */
open class OpenListException(val code: Int, override val message: String) : Exception(message)

/**
 * OpenList 认证异常（401：token 缺失/失效或登录凭据错误）。
 *
 * [testConnection] 据此映射为 [ConnectionResult.AuthFailure]。
 */
class OpenListAuthException(code: Int = 401, message: String) : OpenListException(code, message)
