package xa.refile.core.webdav

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.CreationDate
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetContentType
import at.bitfire.dav4jvm.property.GetLastModified
import at.bitfire.dav4jvm.property.ResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 基于 dav4jvm（OkHttp）的 WebDAV 客户端（计划 §M1 SubTask 1.2.1）。
 *
 * 底层委托给 [DavResource]：PROPFIND/MOVE/MKCOL 的 XML 构造、MultiStatus 解析、
 * URL 编码、Basic/Digest 认证协商均由 dav4jvm 处理，比手写实现更成熟可靠。
 *
 * 提供 PROPFIND（Depth 0/1）、MOVE、MKCOL、连接测试。重命名只通过 MOVE/MKCOL 完成，
 * 不读取/下载文件内容（红线）。
 *
 * dav4jvm 的请求是同步阻塞的，这里用 [withContext]`[Dispatchers.IO]` 包裹协程化。
 * OkHttpClient 通过构造函数注入（便于注入指向 MockWebServer 的 client）；其 DNS/超时
 * 等配置会被保留，仅追加 followRedirects=false（dav4jvm 要求）与认证处理器。
 *
 * 密码仅用于构造 [BasicDigestAuthHandler]，不落盘、不进入日志。
 *
 * @param baseUrl   服务器根 URL，如 `https://dav.example.com/dav`。
 * @param username  用户名（可空，匿名访问时为 null）。
 * @param password  密码（可空）。
 * @param client    可注入的 OkHttpClient（默认新建）；配置保留并追加认证 + 关闭重定向跟随。
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
    client: OkHttpClient = OkHttpClient(),
) {
    // dav4jvm 要求 client 不自动跟随重定向（它自行处理 30x，否则 PROPFIND 被降级为 GET）。
    // 保留注入 client 的 DNS/超时等配置，仅追加认证与重定向设置。
    private val httpClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (username != null && password != null) {
                val auth = BasicDigestAuthHandler(domain = null, username, password)
                authenticator(auth)
                addNetworkInterceptor(auth)
            }
        }
        .build()

    /**
     * 发 PROPFIND 请求。
     * - Depth 0：只返回资源本身（1 条）。
     * - Depth 1：返回当前目录及其直接子项（第一项通常是当前目录本身）。
     *
     * 注意：本应用中 PROPFIND 始终作用于「目录」（浏览器列目录、预览冲突检测、
     * 伴随文件发现、连接测试 rootPath），因此 URL 一律补齐末尾 `/`。
     * 许多 WebDAV 服务器（Alist、nginx-based）对不带末尾斜杠的目录 URL 会返回
     * 301 重定向到带斜杠的 URL；OkHttp 在跟随 301/302 时会按 RFC 7231 把
     * PROPFIND 降级为 GET，导致拿不到 multistatus 响应。dav4jvm 关闭了自动重定向，
     * 301 会直接抛错，因此主动补斜杠从源头规避该重定向。
     *
     * 非 2xx/207 抛 [WebDavException]（含 HTTP 状态码），便于调用方区分「读取失败」与
     * 「空目录」。空目录的 PROPFIND Depth 1 仍会返回 1 条（目录自身），不会抛异常。
     */
    suspend fun propfind(path: String, depth: Int): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<WebDavEntry>()
        try {
            DavResource(httpClient, resolveCollectionUrl(path)).propfind(
                depth,
                DisplayName.NAME,
                GetContentLength.NAME,
                GetLastModified.NAME,
                CreationDate.NAME,
                ResourceType.NAME,
                GetContentType.NAME,
            ) { response, _ ->
                results += response.toEntry()
            }
        } catch (e: HttpException) {
            throw WebDavException(e.code, "HTTP ${e.code}")
        } catch (e: DavException) {
            throw WebDavException(500, e.message ?: "WebDAV error")
        } catch (e: IOException) {
            // B2: 网络错误不可与「合法空目录」混为一谈（空目录仍返回 1 条目录自身，不会进本分支）。
            // 用 code=0 表示非 HTTP 状态码的网络错误，调用方可据 code 区分断网与空结果。
            throw WebDavException(0, "Network error: ${e.message ?: "unknown"}")
        }
        results
    }

    /**
     * 发 MOVE 请求重命名/移动资源。
     * - `Destination` 头为完整 URL（dav4jvm 内部处理编码）。
     * - `overwrite=false`（默认）发送 `Overwrite: F`（不覆盖）；`overwrite=true` 不发送
     *   Overwrite 头（交由服务器默认行为）。成功（2xx，典型 201/204）返回 true，其余返回 false。
     */
    suspend fun move(fromPath: String, toPath: String, overwrite: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // dav4jvm 的 forceOverride=true 发送 Overwrite: F（不覆盖）；
                // forceOverride=false 不发送 Overwrite 头。
                // 我们的 overwrite=false 表示「不覆盖」→ forceOverride=true。
                DavResource(httpClient, resolveUrl(fromPath)).move(
                    resolveUrl(toPath),
                    forceOverride = !overwrite,
                ) { }
                true
            } catch (e: HttpException) {
                false
            } catch (e: DavException) {
                false
            } catch (e: IOException) {
                false
            }
        }

    /**
     * 发 MKCOL 创建目录。405（已存在/不允许）视为幂等成功（返回 true）。
     * 201 返回 true，其余状态码返回 false。
     */
    suspend fun mkcol(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DavResource(httpClient, resolveUrl(path)).mkCol(null) { }
            true
        } catch (e: HttpException) {
            // 405 既可能是目录已存在，也可能是服务器不支持 MKCOL。两种含义此处都视为幂等成功
            // （上层重命名流程依赖目录存在即可）；如需区分需查 Allow 头，暂不引入。
            if (e.code == 405) {
                logger.log(Level.FINE, "mkcol: 405 on {0}, assuming collection already exists", path)
                true
            } else {
                false
            }
        } catch (e: DavException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 对 [path] 发 PROPFIND Depth: 0 测试连接，返回成功/失败原因。
     * - 网络异常 → [ConnectionResult.NetworkError]。
     * - 401 → [ConnectionResult.AuthFailure]（认证失败）。
     * - 405/501 → [ConnectionResult.NotWebDav]（PROPFIND 不被支持）。
     * - 207 或 2xx → [ConnectionResult.Success]（附带解析到的资源）。
     * - 其它 → [ConnectionResult.HttpError]。
     */
    suspend fun testConnection(path: String): ConnectionResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<WebDavEntry>()
        try {
            DavResource(httpClient, resolveCollectionUrl(path)).propfind(
                0,
                DisplayName.NAME,
                GetContentLength.NAME,
                GetLastModified.NAME,
                CreationDate.NAME,
                ResourceType.NAME,
                GetContentType.NAME,
            ) { response, _ ->
                results += response.toEntry()
            }
            ConnectionResult.Success(results.firstOrNull())
        } catch (e: HttpException) {
            when (e.code) {
                401 -> ConnectionResult.AuthFailure(e.code)
                405, 501 -> ConnectionResult.NotWebDav(e.code)
                else -> ConnectionResult.HttpError(e.code)
            }
        } catch (e: DavException) {
            ConnectionResult.HttpError(500)
        } catch (e: IOException) {
            ConnectionResult.NetworkError(e.message ?: "network error")
        }
    }

    /** 拼接 baseUrl 与 path（保留 baseUrl 路径前缀），返回 HttpUrl。 */
    private fun resolveUrl(path: String): HttpUrl {
        // B8: 不能用字符串拼接 + toHttpUrl()——文件名中的 ? 会被解析为查询参数起始、
        // # 会被解析为 fragment，导致 MOVE 目标路径被截断。改用 HttpUrl.Builder.addPathSegment
        // 自动对每段做百分号编码（空格/中文/?/# 均安全）。
        val baseHttpUrl = baseUrl.trimEnd('/').toHttpUrl()
        val normalized = when {
            path.isEmpty() || path == "/" -> ""
            path.startsWith("/") -> path.removePrefix("/")
            else -> path
        }
        if (normalized.isEmpty()) return baseHttpUrl
        val builder = baseHttpUrl.newBuilder()
        // addPathSegment 不处理 / 分隔的多段，需先按 / 拆分逐段添加；中间空段（连续 //）跳过。
        normalized.split('/').forEach { segment ->
            if (segment.isNotEmpty()) builder.addPathSegment(segment)
        }
        val built = builder.build()
        // B8 补丁：addPathSegment 会丢掉末尾斜杠（split 产生的末尾空串被跳过）。
        // PROPFIND 等 collection 请求（经 resolveCollectionUrl 补末尾 /）需要末尾斜杠避免
        // 服务器 301 重定向，这里在 URL 层显式补回。
        return if (normalized.endsWith("/") && !built.encodedPath.endsWith("/")) {
            built.newBuilder().encodedPath(built.encodedPath + "/").build()
        } else {
            built
        }
    }

    /**
     * 拼接 baseUrl 与 path 并强制末尾 `/`，用于 PROPFIND 等目录性请求：
     * 避免部分 WebDAV 服务器对无末尾斜杠的目录 URL 返回 301 重定向。
     */
    private fun resolveCollectionUrl(path: String): HttpUrl {
        val withSlash = when {
            path.isEmpty() || path == "/" -> "/"
            path.endsWith("/") -> path
            else -> "$path/"
        }
        return resolveUrl(withSlash)
    }

    /** 把 dav4jvm 的 [Response] 映射为应用的 [WebDavEntry]。 */
    private fun Response.toEntry(): WebDavEntry {
        val resourceType = this[ResourceType::class.java]
        return WebDavEntry(
            href = href.encodedPath,
            displayName = this[DisplayName::class.java]?.displayName,
            isCollection = resourceType?.types?.contains(ResourceType.COLLECTION) == true,
            contentLength = this[GetContentLength::class.java]?.contentLength,
            // dav4jvm 把 getlastmodified 解析为 epoch millis，转回 RFC1123 字符串以保持原有契约。
            lastModified = this[GetLastModified::class.java]?.lastModified?.let(::formatHttpDate),
            creationDate = this[CreationDate::class.java]?.creationDate,
            contentType = this[GetContentType::class.java]?.type?.toString(),
        )
    }

    companion object {
        // 纯 JVM 模块用 JUL（Timber/Android Log 不可用）；调试级日志，默认不输出。
        private val logger = Logger.getLogger(WebDavClient::class.java.name)

        // SimpleDateFormat 非线程安全，用 ThreadLocal 隔离（多协程并发在 IO 上调用）。
        private val HTTP_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }

        private fun formatHttpDate(epochMillis: Long): String =
            HTTP_DATE_FORMAT.get().format(Date(epochMillis))
    }
}

/**
 * 连接测试结果（计划 §M1 SubTask 1.2.1 testConnection）。
 */
sealed class ConnectionResult {
    /** 连接成功，附带 PROPFIND 解析到的根资源（可能为空）。 */
    data class Success(val entry: WebDavEntry? = null) : ConnectionResult()
    /** 认证失败（401）。 */
    data class AuthFailure(val code: Int = 401) : ConnectionResult()
    /** 非 WebDAV（PROPFIND 不被支持，405/501）。 */
    data class NotWebDav(val code: Int) : ConnectionResult()
    /** 其它 HTTP 错误。 */
    data class HttpError(val code: Int) : ConnectionResult()
    /** 网络错误（无法连接）。 */
    data class NetworkError(val message: String) : ConnectionResult()
}

/**
 * WebDAV 请求异常（PROPFIND/MOVE 等非成功状态码）。
 *
 * 用于让调用方区分「读取失败（HTTP 错误）」与「空目录（成功但无子项）」：
 * - 空目录的 PROPFIND Depth 1 会返回 1 条（目录自身），不会抛本异常。
 * - 401/404/5xx 等会抛本异常，调用方可据 [code] 给出针对性提示。
 *
 * @property code HTTP 状态码。
 * @property message 人类可读的简短描述。
 */
class WebDavException(val code: Int, override val message: String) : Exception(message)
