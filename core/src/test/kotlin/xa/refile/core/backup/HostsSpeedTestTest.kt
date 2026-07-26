package xa.refile.core.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [HostsSpeedTest] 单元测试。
 *
 * 测试策略：[HostsSpeedTest.testIp] 内部构造的 HTTPS 请求（带自定义 DNS pin 到给定 IP）
 * 经应用拦截器重写为 HTTP 指向 MockWebServer，从而无需 HTTPS 自签证书即可测：
 * - 状态码 2xx/3xx 视为可用、4xx/5xx 视为不可用。
 * - 连接异常（[SocketPolicy.DISCONNECT_AT_START]）返回 errorMessage 且不可用。
 * - 并行测速结果顺序与输入一致。
 * - 选最快 IP 的逻辑。
 *
 * DNS pin 路由本身由 [HostsDnsTest] 覆盖，本测试只关注状态码/延迟/异常的判定逻辑。
 */
class HostsSpeedTestTest {

    private lateinit var server: MockWebServer
    private lateinit var speedTest: HostsSpeedTest

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // 重定向拦截器：把 testIp 发起的 https://{hostname}/{path} 请求重写为
        // http://{server.hostName}:{server.port}/{path}，使 MockWebServer 能直接处理。
        val redirectInterceptor = Interceptor { chain ->
            val orig = chain.request().url
            val newUrl = orig.newBuilder()
                .scheme("http")
                .host(server.hostName)
                .port(server.port)
                .build()
            chain.proceed(chain.request().newBuilder().url(newUrl).build())
        }
        val baseClient = OkHttpClient.Builder()
            .addInterceptor(redirectInterceptor)
            .build()
        speedTest = HostsSpeedTest(baseClient = baseClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `testIp 200 返回成功可用且延迟非空`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = speedTest.testIp(HostPresets.TMDB_API, "1.2.3.4")

        assertThat(result.ip).isEqualTo("1.2.3.4")
        assertThat(result.isAvailable).isTrue()
        assertThat(result.statusCode).isEqualTo(200)
        assertThat(result.latencyMs).isNotNull()
        assertThat(result.errorMessage).isNull()
    }

    @Test
    fun `testIp 3xx 视为可用`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302))
        val result = speedTest.testIp(HostPresets.TMDB_API, "1.2.3.4")

        assertThat(result.isAvailable).isTrue()
        assertThat(result.statusCode).isEqualTo(302)
    }

    @Test
    fun `testIp 4xx 视为可用（连通成功，仅 5xx 视为不可用）`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = speedTest.testIp(HostPresets.TMDB_API, "1.2.3.4")

        assertThat(result.isAvailable).isTrue()
        assertThat(result.statusCode).isEqualTo(404)
        assertThat(result.errorMessage).isNull()
    }

    @Test
    fun `testIp 5xx 视为不可用`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = speedTest.testIp(HostPresets.TMDB_API, "1.2.3.4")

        assertThat(result.isAvailable).isFalse()
        assertThat(result.statusCode).isEqualTo(500)
    }

    @Test
    fun `testIp 连接异常返回 errorMessage 且 statusCode 与 latencyMs 为 null`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = speedTest.testIp(HostPresets.TMDB_API, "1.2.3.4")

        assertThat(result.isAvailable).isFalse()
        assertThat(result.statusCode).isNull()
        assertThat(result.latencyMs).isNull()
        assertThat(result.errorMessage).isNotNull()
    }

    @Test
    fun `testAllIps 并行测试返回与输入顺序一致的结果`() = runTest {
        val ips = listOf("1.1.1.1", "2.2.2.2", "3.3.3.3")
        ips.forEach { _ -> server.enqueue(MockResponse().setResponseCode(200)) }

        val results = speedTest.testAllIps(HostPresets.TMDB_API, ips)

        assertThat(results).hasSize(3)
        assertThat(results.map { it.ip }).containsExactlyElementsIn(ips).inOrder()
        results.forEach {
            assertThat(it.isAvailable).isTrue()
            assertThat(it.statusCode).isEqualTo(200)
        }
    }

    @Test
    fun `testAllIps 空列表返回空结果`() = runTest {
        val results = speedTest.testAllIps(HostPresets.TMDB_API, emptyList())
        assertThat(results).isEmpty()
    }

    @Test
    fun `pickFastest 单 IP 可用返回该 IP`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val fastest = speedTest.pickFastest(HostPresets.TMDB_API, listOf("1.1.1.1"))

        assertThat(fastest).isNotNull()
        assertThat(fastest!!.ip).isEqualTo("1.1.1.1")
        assertThat(fastest.isAvailable).isTrue()
    }

    @Test
    fun `pickFastest 多 IP 返回延迟最低可用的结果`() = runTest {
        // 两个 IP 都成功；不假定响应分配顺序，仅断言选出的最快可用。
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val fastest = speedTest.pickFastest(HostPresets.TMDB_API, listOf("1.1.1.1", "2.2.2.2"))

        assertThat(fastest).isNotNull()
        assertThat(fastest!!.isAvailable).isTrue()
        assertThat(fastest.latencyMs).isNotNull()
        assertThat(listOf("1.1.1.1", "2.2.2.2")).contains(fastest.ip)
    }

    @Test
    fun `pickFastest 全部失败返回 null`() = runTest {
        listOf("1.1.1.1", "2.2.2.2").forEach {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        }

        val fastest = speedTest.pickFastest(HostPresets.TMDB_API, listOf("1.1.1.1", "2.2.2.2"))

        assertThat(fastest).isNull()
    }

    @Test
    fun `pickFastest 空列表返回 null`() = runTest {
        val fastest = speedTest.pickFastest(HostPresets.TMDB_API, emptyList())
        assertThat(fastest).isNull()
    }
}
