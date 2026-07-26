package xa.refile.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Hosts 自动测速（spec §5.3.3）。
 *
 * 对每个候选 IP 构造一条 HTTPS 请求（自定义 DNS 把目标 hostname 解析到该 IP，
 * TLS SNI/证书校验仍由 OkHttp 基于请求 URL 的 hostname 处理），测：
 * - 延迟：请求开始到响应返回的耗时（ms）。
 * - 可用性：HTTP 状态码 2xx/3xx 算可用。
 * - 失败：超时/IO 异常返回 [errorMessage]，[latencyMs]/[statusCode] 为 null。
 *
 * [testAllIps] 并行测速，[pickFastest] 选延迟最低且可用的。
 *
 * @param baseClient 测速用基础 client，本类在其上 newBuilder() 改 DNS+超时；
 *                   测试可注入信任自签证书的 client 以跑 HTTPS MockWebServer。
 */
class HostsSpeedTest(
    private val baseClient: OkHttpClient = OkHttpClient(),
) {

    /**
     * 单 IP 测速结果。
     *
     * @param ip 被测 IP。
     * @param latencyMs 延迟（毫秒），失败时为 null。
     * @param isAvailable 是否可用（HTTP 状态码 2xx/3xx）。
     * @param statusCode HTTP 状态码，失败时为 null。
     * @param errorMessage 失败原因（异常 message），成功时为 null。
     */
    data class IpSpeedTestResult(
        val ip: String,
        val latencyMs: Long?,
        val isAvailable: Boolean,
        val statusCode: Int?,
        val errorMessage: String?,
    )

    /**
     * 测试单个 IP 的连通性 + 延迟。
     *
     * 构造 [Dns]：对目标 [hostname] 返回 [ip]，其它回退系统 DNS。
     * 用 HTTPS GET 请求 `https://{hostname}:{port}{path}`。
     *
     * 注意：曾用 HEAD 方法，但 TMDB API 根路径与 image.tmdb.org 对 HEAD 返回 405
     * Method Not Allowed，导致测速一律判失败（spec §5.3.3 自动选优不可用）。
     * 改用 GET；只要拿到任意 HTTP 响应（含 4xx）即视为「连通」，5xx/异常才算失败。
     * 超时：connect 10s / read 15s / call 10s（中国大陆访问 TMDB 偏紧，故放宽）。
     *
     * @param hostname 目标域名（用于 SNI/Host/证书校验）。
     * @param ip 被测 IP 字面量。
     * @param port 端口，默认 443。
     * @param path 请求路径，默认 `/`。
     * @param client 已配置好超时的共享 client（建议由 [testAllIps] 入口构造一次以复用连接池）；
     *              为 null 时基于 [baseClient] 现配；本方法在其上再 newBuilder().dns(pinDns)。
     */
    suspend fun testIp(
        hostname: String,
        ip: String,
        port: Int = 443,
        path: String = "/",
        client: OkHttpClient? = null,
    ): IpSpeedTestResult = withContext(Dispatchers.IO) {
        // 自定义 DNS：目标 hostname → 该 IP，其它走系统。
        val pinDns = object : Dns {
            override fun lookup(name: String): List<InetAddress> =
                if (name.equals(hostname, ignoreCase = true)) {
                    listOf(InetAddress.getByName(ip))
                } else {
                    Dns.SYSTEM.lookup(name)
                }
        }

        // 共享 client 复用连接池与超时配置；每个 IP 仅需 newBuilder().dns(pinDns)。
        val configuredClient = client ?: baseClient.newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val requestClient = configuredClient.newBuilder()
            .dns(pinDns)
            .build()

        val portPart = if (port == 443) "" else ":$port"
        val request = Request.Builder()
            .url("https://$hostname$portPart$path")
            .get()
            .build()

        val startNanos = System.nanoTime()
        try {
            requestClient.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - startNanos) / NANOS_PER_MS
                val code = response.code
                // 5xx 视为服务端错误/不可用；其余（含 4xx、3xx、2xx）视为连通成功
                val available = code < 500
                IpSpeedTestResult(
                    ip = ip,
                    latencyMs = latency,
                    isAvailable = available,
                    statusCode = code,
                    errorMessage = if (available) null else "HTTP $code",
                )
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            IpSpeedTestResult(
                ip = ip,
                latencyMs = null,
                isAvailable = false,
                statusCode = null,
                errorMessage = errorMessageFor(e),
            )
        }
    }

    /**
     * 并行测速一组 IP。
     *
     * 用 [coroutineScope] + [async] 并发，[awaitAll] 等所有完成。结果顺序与 [ips] 一致。
     *
     * 入口构造一个共享的带超时配置的 client（newBuilder 一次），传入各 [testIp]
     * 复用底层连接池，避免每个 IP 反复 TLS 握手。
     */
    suspend fun testAllIps(hostname: String, ips: List<String>): List<IpSpeedTestResult> =
        coroutineScope {
            val sharedClient = baseClient.newBuilder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            ips.map { ip ->
                async { testIp(hostname, ip, client = sharedClient) }
            }.awaitAll()
        }

    /**
     * 选出延迟最低且可用的 IP。全部失败返回 null。
     *
     * 调用 [testAllIps] 后过滤可用结果按延迟升序取首个。
     */
    suspend fun pickFastest(hostname: String, ips: List<String>): IpSpeedTestResult? {
        val results = testAllIps(hostname, ips)
        return results
            .filter { it.isAvailable && it.latencyMs != null }
            .minByOrNull { it.latencyMs!! }
    }

    /**
     * 直连检测结果（不走 hosts，用系统 DNS）。
     *
     * @param hostname 被测域名。
     * @param isDirectAvailable 系统解析+HTTPS 连通是否成功。
     * @param latencyMs 延迟（毫秒），失败时为 null。
     * @param statusCode HTTP 状态码，失败时为 null。
     * @param errorMessage 失败原因，成功时为 null。
     */
    data class DirectTestResult(
        val hostname: String,
        val isDirectAvailable: Boolean,
        val latencyMs: Long?,
        val statusCode: Int?,
        val errorMessage: String?,
    )

    /**
     * 测试 [hostname] 走系统 DNS 能否直连（不应用 hosts 配置）。
     *
     * 用 baseClient 原样发起 HTTPS 请求（DNS 用系统默认），判断当前网络环境是否需要 hosts：
     * - 成功（任意 HTTP 响应 < 500）→ 当前环境可直连，无需启用 hosts。
     * - 失败（超时/IO 异常/5xx）→ 当前环境无法直连，建议配置 hosts。
     *
     * 与 [testIp] 的区别：[testIp] 把 hostname pin 到指定 IP 测速（用于 hosts 选优），
     * 本方法不 pin IP，完全走系统 DNS，用于诊断「是否需要 hosts」。
     *
     * @param hostname 目标域名。
     * @param path 请求路径，默认 `/`。
     */
    suspend fun testDirectConnect(
        hostname: String,
        path: String = "/",
    ): DirectTestResult = withContext(Dispatchers.IO) {
        // 直连用 baseClient（系统 DNS，不应用 HostsDns），仅补超时。
        val directClient = baseClient.newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("https://$hostname$path")
            .get()
            .build()

        val startNanos = System.nanoTime()
        try {
            directClient.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - startNanos) / NANOS_PER_MS
                val code = response.code
                val available = code < 500
                DirectTestResult(
                    hostname = hostname,
                    isDirectAvailable = available,
                    latencyMs = latency,
                    statusCode = code,
                    errorMessage = if (available) null else "HTTP $code",
                )
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            DirectTestResult(
                hostname = hostname,
                isDirectAvailable = false,
                latencyMs = null,
                statusCode = null,
                errorMessage = errorMessageFor(e),
            )
        }
    }

    /**
     * 把测速异常转换为用户可读的中文错误信息。
     *
     * 区分：[SocketTimeoutException]（按 message 判连接/读取超时）、[SSLException]（TLS 握手失败），
     * 其余回退原始 message。
     */
    private fun errorMessageFor(e: Throwable): String = when (e) {
        is SocketTimeoutException ->
            if (e.message?.contains("read", ignoreCase = true) == true) "读取超时" else "连接超时"
        is SSLException -> "TLS 握手失败"
        else -> e.message ?: e::class.simpleName ?: "未知错误"
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 15_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
