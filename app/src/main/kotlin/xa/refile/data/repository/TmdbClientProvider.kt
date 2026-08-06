package xa.refile.data.repository

import xa.refile.core.tmdb.TmdbClient
import xa.refile.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 客户端共享基础设施（Task 20 拆分自原 `TmdbCacheRepository`）。
 *
 * [TmdbDetailRepository] 与 [TmdbSearchRepository] 共用以下资源，抽到此处避免重复：
 * - [sharedClient]：所有 TMDB 请求复用同一 OkHttp 连接池 / 线程池（调优顶到 TMDB 20 并发上限）。
 * - [buildTmdbClient]：按 `apiKey@baseUrl` 缓存 [TmdbClient] 实例，保证限流 / 重试拦截器状态跨请求共享。
 * - [coalesce]：相同 key 的并发网络请求合并为一次（批量匹配同剧集多分集场景）。
 *
 * DI：`@Singleton` + `@Inject constructor`，Hilt 直接构造，两个仓库共享同一实例。
 *
 * 安全：apiKey 仅在此读取用于构造 [TmdbClient]，不落盘、不进入日志。
 */
@Singleton
class TmdbClientProvider @Inject constructor(
    private val settings: SettingsRepository,
) {

    /**
     * 共享 OkHttpClient：所有 TMDB 请求复用同一连接池/线程池，避免每次请求新建 client 造成资源泄漏。
     *
     * 调优（提速关键）：
     * - 默认 OkHttp 单 host 仅 5 个并发连接，是吞吐量的主要瓶颈；TMDB 限制每 IP 20 个并发连接，
     *   这里把单 host 并发与空闲连接池都顶到 20，让 40 req/s 的限流能真正跑满。
     * - 慢速反代（Cloudflare Workers）下放宽超时，避免请求过早失败；同时保留上限防止坏连接长时间挂起。
     */
    private val sharedClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 20
                },
            )
            .build()
    }

    /**
     * 并发请求合并作用域：仅用于把「相同 key 的并发网络请求」合并为一次（见 [coalesce]）。
     * 用 SupervisorJob 保证单个请求失败不影响其它在飞的请求。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 在飞的相同 key 请求（[coalesce] 用），避免批量匹配同一剧集时大量重复 `getTv`/`getSeason`/search。
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /**
     * 把相同 [key] 的并发网络请求合并成一次：第一个到达的发起请求，其余 `await` 同一结果。
     * 命中 Room/会话缓存的路径不进这里（调用方已先查缓存），故仅合并真正打网络的瞬间并发。
     *
     * 用 `CoroutineStart.LAZY` + `putIfAbsent` 实现无锁合并：竞争失败方取消自己未启动的协程并复用胜方结果。
     *
     * B3 修复：原实现在 `finally { inFlight.remove(key, deferred) }` 中，当调用方协程被取消
     * （`deferred.await()` 抛 CancellationException）时也会移除 entry——但底层 ioScope.async
     * 仍在运行（SupervisorJob 独立于调用方）。后续相同 key 的请求会 miss 并发起新请求，
     * 浪费正在进行的网络请求。修复：仅在 deferred 自身完成（非调用方取消）时才移除 entry。
     */
    suspend fun <T> coalesce(key: String, fetch: suspend () -> T): T {
        inFlight[key]?.let { @Suppress("UNCHECKED_CAST") return (it as Deferred<T>).await() }
        val deferred = ioScope.async(start = CoroutineStart.LAZY) { fetch() }
        val prev = inFlight.putIfAbsent(key, deferred as Deferred<Any?>)
        if (prev != null) {
            deferred.cancel() // LAZY 尚未启动，安全取消
            @Suppress("UNCHECKED_CAST") return (prev as Deferred<T>).await()
        }
        try {
            return deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // B3 修复：调用方协程被取消时，不移除 inFlight entry——底层 async 仍在 ioScope 运行，
            // 后续相同 key 的请求仍可复用。entry 会在 deferred 完成后由下面的 invokeOnCompletion 清理。
            throw e
        } finally {
            // 仅当 deferred 自身已完成（非调用方取消）时才清理 entry。
            if (deferred.isCompleted) {
                inFlight.remove(key, deferred as Deferred<Any?>)
            }
        }
    }

    /**
     * 复用同一 [TmdbClient] 实例：其内部的限流 / 重试拦截器状态（[xa.refile.core.tmdb.TmdbRateLimitInterceptor]
     * 的时间戳滑动窗口）必须跨请求共享才有效。若每次请求都重建 client（旧实现），限流器形同虚设，
     * 批量匹配会瞬间打满 TMDB / 反代，触发 429 + 指数退避；经慢速反代（Cloudflare Workers）后整体明显变慢。
     *
     * 故按 `apiKey@baseUrl` 缓存 client，仅在 API Key 或反代地址变化时重建。锁仅用于保护缓存写入，
     * 命中路径（`synchronized` 读）开销极低。
     *
     * @throws IllegalStateException apiKey 为空时（调用方捕获提示用户）。
     */
    suspend fun buildTmdbClient(): TmdbClient {
        val apiKey = settings.apiKey.first()
        if (apiKey.isBlank()) throw IllegalStateException("请先在设置中填入 TMDB API Key")
        // 用户填了反代地址时，拼成 `proxyUrl + 官方API地址`（Cloudflare Workers Proxy 用法）；
        // 否则直连官方默认 baseUrl。
        val proxy = settings.tmdbProxyUrl.first().takeIf { it.isNotBlank() }
        val baseUrl = if (proxy != null) {
            proxy.trimEnd('/') + "/" + TmdbClient.DEFAULT_BASE_URL
        } else {
            TmdbClient.DEFAULT_BASE_URL
        }
        val key = "$apiKey@$baseUrl"

        // 命中缓存直接返回，复用共享的限流 / 重试状态。
        val hit = synchronized(clientLock) {
            val c = cachedClient
            if (c != null && cachedClientKey == key) c else null
        }
        if (hit != null) return hit

        // 未命中：新建（新 Retrofit + 新拦截器链），再原子写入缓存（双检锁防并发重复建）。
        val client = TmdbClient.create(sharedClient, apiKey, baseUrl)
        synchronized(clientLock) {
            val c = cachedClient
            if (c != null && cachedClientKey == key) {
                return c // 并发下已有同 key 的 client 建好，复用之
            }
            cachedClient = client
            cachedClientKey = key
        }
        return client
    }

    private val clientLock = Any()
    @Volatile private var cachedClient: TmdbClient? = null
    @Volatile private var cachedClientKey: String? = null
}
