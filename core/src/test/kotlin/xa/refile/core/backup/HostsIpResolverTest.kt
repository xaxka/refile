package xa.refile.core.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [HostsIpResolver] 单元测试（测试反馈 Item 13）。
 *
 * 测试策略：用 MockWebServer 模拟 DoH JSON API 响应，验证：
 * - 正常 A 记录解析返回去重 IPv4 列表。
 * - 过滤非 A 记录（如 CNAME type=5）。
 * - 过滤非法 IPv4 格式。
 * - 首个 DoH 服务失败时回退到第二个。
 * - 所有服务失败返回空列表。
 * - Status 非 0 返回空列表。
 *
 * [HostsIpResolver] 构造函数接受 [dohProviders] 参数，测试中注入 MockWebServer URL。
 */
class HostsIpResolverTest {

    private lateinit var primaryServer: MockWebServer
    private lateinit var fallbackServer: MockWebServer

    @Before
    fun setUp() {
        primaryServer = MockWebServer()
        primaryServer.start()
        fallbackServer = MockWebServer()
        fallbackServer.start()
    }

    @After
    fun tearDown() {
        primaryServer.shutdown()
        fallbackServer.shutdown()
    }

    @Test
    fun `resolve 正常 A 记录返回去重 IPv4 列表`() = runTest {
        val dohResponse = """{"Status":0,"Answer":[
            {"name":"api.themoviedb.org","type":1,"TTL":300,"data":"13.224.103.18"},
            {"name":"api.themoviedb.org","type":1,"TTL":300,"data":"13.224.103.19"},
            {"name":"api.themoviedb.org","type":1,"TTL":300,"data":"13.224.103.18"}
        ]}"""
        primaryServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/dns-json")
                .setBody(dohResponse),
        )

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        val ips = resolver.resolve("api.themoviedb.org")

        assertThat(ips).containsExactly("13.224.103.18", "13.224.103.19").inOrder()
    }

    @Test
    fun `resolve 过滤非 A 记录`() = runTest {
        // type=5 是 CNAME，应被过滤；type=1 是 A 记录
        val dohResponse = """{"Status":0,"Answer":[
            {"name":"api.themoviedb.org","type":5,"TTL":300,"data":"some.alias.com"},
            {"name":"api.themoviedb.org","type":1,"TTL":300,"data":"1.2.3.4"}
        ]}"""
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        val ips = resolver.resolve("api.themoviedb.org")
        assertThat(ips).containsExactly("1.2.3.4")
    }

    @Test
    fun `resolve 过滤非法 IPv4 格式`() = runTest {
        val dohResponse = """{"Status":0,"Answer":[
            {"name":"x","type":1,"TTL":300,"data":"not.an.ip.address"},
            {"name":"x","type":1,"TTL":300,"data":"999.999.999.999"},
            {"name":"x","type":1,"TTL":300,"data":"10.0.0.1"}
        ]}"""
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        val ips = resolver.resolve("example.com")
        assertThat(ips).containsExactly("10.0.0.1")
    }

    @Test
    fun `resolve 首个 DoH 失败回退到第二个`() = runTest {
        // 第一个 server 返回 500
        primaryServer.enqueue(MockResponse().setResponseCode(500))
        // 第二个 server 返回正常结果
        val dohResponse = """{"Status":0,"Answer":[
            {"name":"x","type":1,"TTL":300,"data":"5.6.7.8"}
        ]}"""
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(
                primaryServer.url("/resolve").toString(),
                fallbackServer.url("/resolve").toString(),
            ),
        )

        val ips = resolver.resolve("example.com")
        assertThat(ips).containsExactly("5.6.7.8")
        assertThat(primaryServer.requestCount).isEqualTo(1)
        assertThat(fallbackServer.requestCount).isEqualTo(1)
    }

    @Test
    fun `resolve 所有 DoH 失败返回空列表`() = runTest {
        primaryServer.enqueue(MockResponse().setResponseCode(500))
        fallbackServer.enqueue(MockResponse().setResponseCode(503))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(
                primaryServer.url("/resolve").toString(),
                fallbackServer.url("/resolve").toString(),
            ),
        )

        val ips = resolver.resolve("example.com")
        assertThat(ips).isEmpty()
    }

    @Test
    fun `resolve Status 非 0 返回空列表`() = runTest {
        // Status 2 = SERVFAIL
        val dohResponse = """{"Status":2,"Answer":[]}"""
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        val ips = resolver.resolve("example.com")
        assertThat(ips).isEmpty()
    }

    @Test
    fun `resolve 无 Answer 字段返回空列表`() = runTest {
        val dohResponse = """{"Status":0}"""
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        val ips = resolver.resolve("example.com")
        assertThat(ips).isEmpty()
    }

    @Test
    fun `resolve 请求携带 name 和 type 参数`() = runTest {
        val dohResponse = """{"Status":0,"Answer":[{"name":"x","type":1,"TTL":300,"data":"1.1.1.1"}]}"""
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody(dohResponse))

        val resolver = HostsIpResolver(
            baseClient = OkHttpClient(),
            dohProviders = listOf(primaryServer.url("/resolve").toString()),
        )

        resolver.resolve("api.themoviedb.org")

        val req = primaryServer.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        // 请求路径应包含 name=api.themoviedb.org 和 type=A
        assertThat(req.path).contains("name=api.themoviedb.org")
        assertThat(req.path).contains("type=A")
    }
}
