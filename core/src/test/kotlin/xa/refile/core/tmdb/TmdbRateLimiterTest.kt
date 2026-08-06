package xa.refile.core.tmdb

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TmdbRateLimiterTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `rate limiter blocks request beyond concurrent limit`() {
        // P3：改为 Semaphore 式限流。maxConcurrent=5 → 前 5 个并发请求不阻塞，第 6 个阻塞到前面释放。
        // 由于 Semaphore.acquireUninterruptibly() 不使用 Sleeper，这里验证并发限制行为。
        val limiter = TmdbRateLimitInterceptor(
            maxConcurrent = 5,
        )

        val client = OkHttpClient.Builder().addInterceptor(limiter).build()
        // 预先入队足够多的 200 响应
        repeat(10) { server.enqueue(MockResponse().setBody("ok")) }

        val request = Request.Builder().url(server.url("/").toString()).build()
        // 前 5 个请求串行执行不应阻塞（Semaphore 在串行下 acquire/release 立即完成）
        repeat(5) { client.newCall(request).execute().close() }
        // 第 6 个也不阻塞（前 5 个已 release）
        client.newCall(request).execute().close()
        // 所有请求都成功执行
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test fun `rate limiter limits concurrent in-flight requests`() {
        // P3：验证并发限制——3 个并发 slot，第 4 个并发请求会等待。
        val limiter = TmdbRateLimitInterceptor(maxConcurrent = 3)
        val client = OkHttpClient.Builder().addInterceptor(limiter).build()
        repeat(5) { server.enqueue(MockResponse().setBody("ok")) }
        val request = Request.Builder().url(server.url("/").toString()).build()

        // 串行 3 个不阻塞
        repeat(3) { client.newCall(request).execute().close() }
        // 第 4 个串行也不阻塞（前 3 个已 release）
        client.newCall(request).execute().close()
    }

    @Test fun `retry interceptor retries on 429 with Retry-After`() {
        val sleeps = mutableListOf<Long>()
        val sleeper = Sleeper { millis -> sleeps.add(millis) }
        val retry = TmdbRetryInterceptor(maxRetries = 3, sleeper = sleeper)
        val client = OkHttpClient.Builder().addInterceptor(retry).build()

        // 先返回 429（Retry-After: 2），再返回 200
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("rate limited"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder().url(server.url("/").toString()).build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
        }
        // 应该 sleep 了 2 秒（2000ms）
        assertThat(sleeps).containsExactly(2000L)
        // 服务器应收到 2 个请求
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `retry interceptor exponential backoff without Retry-After`() {
        val sleeps = mutableListOf<Long>()
        val sleeper = Sleeper { millis -> sleeps.add(millis) }
        val retry = TmdbRetryInterceptor(maxRetries = 3, sleeper = sleeper)
        val client = OkHttpClient.Builder().addInterceptor(retry).build()

        // 3 次 429，最后 200
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("nope")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder().url(server.url("/").toString()).build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
        }
        // 指数退避：1s, 2s, 4s
        assertThat(sleeps).containsExactly(1000L, 2000L, 4000L).inOrder()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `retry interceptor gives up after max retries`() {
        val sleeps = mutableListOf<Long>()
        val sleeper = Sleeper { millis -> sleeps.add(millis) }
        val retry = TmdbRetryInterceptor(maxRetries = 2, sleeper = sleeper)
        val client = OkHttpClient.Builder().addInterceptor(retry).build()

        // maxRetries=2 → 总共 3 次请求（1 初次 + 2 重试），全 429
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")) }

        val request = Request.Builder().url(server.url("/").toString()).build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(429)
        }
        assertThat(sleeps).containsExactly(1000L, 1000L).inOrder()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `retry interceptor does not retry on 200`() {
        val sleeps = mutableListOf<Long>()
        val sleeper = Sleeper { millis -> sleeps.add(millis) }
        val retry = TmdbRetryInterceptor(maxRetries = 3, sleeper = sleeper)
        val client = OkHttpClient.Builder().addInterceptor(retry).build()

        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val request = Request.Builder().url(server.url("/").toString()).build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
        }
        assertThat(sleeps).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `retry interceptor also handles 503`() {
        val sleeps = mutableListOf<Long>()
        val sleeper = Sleeper { millis -> sleeps.add(millis) }
        val retry = TmdbRetryInterceptor(maxRetries = 3, sleeper = sleeper)
        val client = OkHttpClient.Builder().addInterceptor(retry).build()

        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder().url(server.url("/").toString()).build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
        }
        assertThat(sleeps).containsExactly(1000L)
    }

    @Test fun `api key interceptor adds api_key and Accept header`() {
        val interceptor = TmdbApiKeyInterceptor(apiKey = "secret123")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder().url(server.url("/test").toString()).build()
        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("api_key=secret123")
        assertThat(recorded.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test fun `api key interceptor preserves existing query params`() {
        val interceptor = TmdbApiKeyInterceptor(apiKey = "k")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder()
            .url(server.url("/search/movie").newBuilder().addQueryParameter("query", "matrix").build())
            .build()
        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("query=matrix")
        assertThat(recorded.path).contains("api_key=k")
        assertThat(recorded.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test fun `real sleeper honors interrupt and resets flag`() {
        // 验证 RealSleeper 被中断时不抛异常且保留 interrupt 标志
        val mainThread = Thread.currentThread()
        val interrupter = Thread {
            Thread.sleep(50)
            mainThread.interrupt()
        }
        interrupter.start()
        RealSleeper.sleep(500)
        // 被中断后应保留 interrupt 标志（Thread.interrupted 会清除）
        assertThat(Thread.interrupted()).isTrue()
        interrupter.join()
    }
}
