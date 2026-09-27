package xa.refile.core.tmdb

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * TMDB 限流与重试拦截器（计划 §5.4 / Task 2.2.3，红线：尊重官方限速 + 429 退避）。
 *
 * 官方限速（developer.themoviedb.org/docs/rate-limiting，管理员确认）：约 50 req/s、每 IP 20 个并发连接；
 * 旧的「40 req / 10s」已于 2019 年取消。此处取 40 并发上限留安全余量，触发 429 时由 [TmdbRetryInterceptor]
 * 读 Retry-After 退避重试（自愈）。
 *
 * P3 修复：原实现用 ConcurrentLinkedDeque + Thread.sleep（滑动窗口），
 * 在 OkHttp Dispatcher 线程上 sleep 会长时间占用线程池（sharedClient maxRequests=64），
 * 当多个请求同时限流时可阻塞全部 64 个线程，导致其他 host 请求也排队。
 * 改用 Semaphore 限制同时在途的请求数，获得令牌即放行、释放令牌即唤醒等待者，
 * 避免长时间 sleep 占线程。
 * 当每请求耗时 ~200ms 时，40 个并发 slot ≈ 200 req/s，但 OkHttp 的 maxRequestsPerHost=20
 * 已将实际并发限制到 20，本信号量仅做二次保护，防止突发打满被 429。
 */

/**
 * 信号量式限流拦截器：限制同时在途的请求数为 [maxConcurrent]。
 *
 * 与滑动窗口不同，信号量不会在 thread 上 sleep——线程在 `acquire()` 上 park（JVM 级别），
 * 被释放后立即唤醒。这不会占住 OkHttp Dispatcher 线程做无用 sleep。
 */
class TmdbRateLimitInterceptor(
    private val maxConcurrent: Int = DEFAULT_MAX_REQUESTS,
    @Suppress("unused") private val windowMillis: Long = DEFAULT_WINDOW_MILLIS, // 保留兼容旧构造签名
    private val sleeper: Sleeper = RealSleeper,
) : Interceptor {

    private val semaphore = Semaphore(maxConcurrent)

    override fun intercept(chain: Interceptor.Chain): Response {
        semaphore.acquireUninterruptibly()
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } finally {
            semaphore.release()
        }
    }

    /** 测试用：当前可用的并发令牌数。 */
    internal fun availablePermits(): Int = semaphore.availablePermits()

    companion object {
        const val DEFAULT_MAX_REQUESTS = 40
        const val DEFAULT_WINDOW_MILLIS = 1_000L
    }
}

/**
 * 429 退避重试拦截器：响应 429 时读取 `Retry-After` 头（秒）后 sleep 重试；
 * 无该头则指数退避（1s, 2s, 4s）。最多重试 [maxRetries] 次。
 *
 * 也会处理 503（Service Unavailable）的 Retry-After。
 *
 * P2-1（审查报告 2026-09-25）：Retry-After 钳制 + 总重试预算。
 * - 原样采纳服务器的 Retry-After 时，异常服务器（或反代配置错误）返回超大值
 *   （如数小时）会让请求线程挂起，UI 表现为永久 loading。现钳制到
 *   [maxRetryAfterMillis]（默认 60s），超出部分按上限退避——下轮请求会重新
 *   感知实际限流状态，不会损失正确性。
 * - 累计退避超过 [totalBudgetMillis]（默认 5 分钟）后停止重试，返回最后一次
 *   429/503 响应由上层按失败上报，不再无限等待。
 */
class TmdbRetryInterceptor(
    private val maxRetries: Int = 3,
    private val maxRetryAfterMillis: Long = DEFAULT_MAX_RETRY_AFTER_MILLIS,
    private val totalBudgetMillis: Long = DEFAULT_TOTAL_BUDGET_MILLIS,
    private val sleeper: Sleeper = RealSleeper,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var totalSleptMillis = 0L
        while (true) {
            val response = chain.proceed(chain.request())
            if (response.code != 429 && response.code != 503) {
                return response
            }
            if (attempt >= maxRetries) {
                return response
            }
            val retryAfterSeconds = parseRetryAfter(response)
            val sleepMillis = if (retryAfterSeconds != null) {
                // P2-1：Retry-After 钳制到 [0, max]——防超大值挂起请求线程数小时，
                // 也防异常负值触发 Thread.sleep(IllegalArgumentException)。
                TimeUnit.SECONDS.toMillis(retryAfterSeconds).coerceIn(0, maxRetryAfterMillis)
            } else {
                exponentialBackoffMillis(attempt)
            }
            // P2-1：总重试预算——本次退避会超预算时停止重试，按失败上报。
            if (totalSleptMillis + sleepMillis > totalBudgetMillis) {
                return response
            }
            response.close()
            sleeper.sleep(sleepMillis)
            totalSleptMillis += sleepMillis
            attempt++
        }
    }

    private fun parseRetryAfter(response: Response): Long? {
        val header = response.header("Retry-After") ?: return null
        // Retry-After 可能是 delta-seconds 或 HTTP-date；只处理秒。
        return header.trim().toLongOrNull()
    }

    private fun exponentialBackoffMillis(attempt: Int): Long {
        // attempt 0 -> 1s, 1 -> 2s, 2 -> 4s, ...
        return TimeUnit.SECONDS.toMillis(1L shl attempt)
    }

    companion object {
        /** P2-1：单次 Retry-After 退避上限（默认 60s）。 */
        const val DEFAULT_MAX_RETRY_AFTER_MILLIS = 60_000L

        /** P2-1：累计退避预算（默认 5 分钟），超限停止重试按失败上报。 */
        const val DEFAULT_TOTAL_BUDGET_MILLIS = 300_000L
    }
}

/**
 * 注入 API key 与 Accept 头的拦截器（计划 §5.4）。
 *
 * 红线：所有请求带 `Accept: application/json`。
 */
class TmdbApiKeyInterceptor(
    private val apiKey: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()
        val request = original.newBuilder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}

/** 可注入的 sleep 抽象（测试可替换为 fake）。 */
fun interface Sleeper {
    fun sleep(millis: Long)
}

object RealSleeper : Sleeper {
    override fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
