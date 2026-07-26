package xa.refile.core.webdav

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun newClient(
        user: String? = "user",
        pass: String? = "pass",
        client: OkHttpClient = OkHttpClient(),
    ): WebDavClient = WebDavClient(
        baseUrl = server.url("/").toString(),
        username = user,
        password = pass,
        client = client,
    )

    private val multistatus = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
        |  <D:response><D:href>/Movies/</D:href><D:propstat><D:prop>
        |    <D:displayname>Movies</D:displayname><D:resourcetype><D:collection/></D:resourcetype>
        |  </D:prop></D:propstat></D:response>
        |  <D:response><D:href>/Movies/a.mkv</D:href><D:propstat><D:prop>
        |    <D:displayname>a.mkv</D:displayname><D:getcontentlength>100</D:getcontentlength>
        |  </D:prop></D:propstat></D:response>
        |</D:multistatus>""".trimMargin()

    private val rootMultistatus = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
        |  <D:response><D:href>/</D:href><D:propstat><D:prop>
        |    <D:displayname>Root</D:displayname><D:resourcetype><D:collection/></D:resourcetype>
        |  </D:prop></D:propstat></D:response>
        |</D:multistatus>""".trimMargin()

    @Test fun `propfind depth 1 sends PROPFIND with Depth and Content-Type headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(multistatus),
        )

        val entries = newClient().propfind("/Movies", 1)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("PROPFIND")
        // PROPFIND 始终作用于目录，URL 强制末尾斜杠（避免服务器 301 重定向）。
        assertThat(req.path).isEqualTo("/Movies/")
        assertThat(req.getHeader("Depth")).isEqualTo("1")
        assertThat(req.getHeader("Content-Type")).startsWith("application/xml")
        // 首请求不应携带凭据（AuthInterceptor 协商式）
        assertThat(req.getHeader("Authorization")).isNull()

        assertThat(entries).hasSize(2)
        assertThat(entries[0].displayName).isEqualTo("Movies")
        assertThat(entries[0].isCollection).isTrue()
        assertThat(entries[1].displayName).isEqualTo("a.mkv")
        assertThat(entries[1].contentLength).isEqualTo(100L)
        assertThat(entries[1].isCollection).isFalse()
    }

    @Test fun `propfind depth 0 returns single resource`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(rootMultistatus))
        val entries = newClient().propfind("/", 0)

        val req = server.takeRequest()
        assertThat(req.getHeader("Depth")).isEqualTo("0")
        assertThat(entries).hasSize(1)
        assertThat(entries[0].displayName).isEqualTo("Root")
        assertThat(entries[0].isCollection).isTrue()
    }

    @Test fun `propfind appends trailing slash to subdirectory path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        newClient().propfind("/Movies", 1)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Movies/")
    }

    @Test fun `propfind non success throws WebDavException with code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            newClient().propfind("/Movies", 1)
            fail("Expected WebDavException")
        } catch (e: WebDavException) {
            assertThat(e.code).isEqualTo(403)
        }
    }

    @Test fun `move sends MOVE with encoded Destination and Overwrite F`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val result = newClient().move("/from/a b.mkv", "/to/目标.mkv", overwrite = false)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("MOVE")
        assertThat(req.path).isEqualTo("/from/a%20b.mkv")
        assertThat(req.getHeader("Overwrite")).isEqualTo("F")
        val expectedDest = server.url("/").toString().trimEnd('/') +
            "/to/%E7%9B%AE%E6%A0%87.mkv"
        assertThat(req.getHeader("Destination")).isEqualTo(expectedDest)
        assertThat(result).isTrue()
    }

    @Test fun `move overwrite true omits Overwrite header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = newClient().move("/a", "/b", overwrite = true)

        val req = server.takeRequest()
        // dav4jvm 的 move 仅支持发送 Overwrite: F 或不发送；overwrite=true 时不发送
        // Overwrite 头（交由服务器默认行为）。生产代码所有 move 调用均为 overwrite=false。
        assertThat(req.getHeader("Overwrite")).isNull()
        assertThat(result).isTrue()
    }

    @Test fun `move failure returns false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412))
        val result = newClient().move("/a", "/b")
        assertThat(result).isFalse()
    }

    @Test fun `mkcol sends MKCOL and 201 returns true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val result = newClient().mkcol("/NewDir")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("MKCOL")
        assertThat(req.path).isEqualTo("/NewDir")
        assertThat(result).isTrue()
    }

    @Test fun `mkcol 405 returns idempotent success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405))
        val result = newClient().mkcol("/Existing")
        assertThat(result).isTrue()
    }

    @Test fun `mkcol other failure code returns false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = newClient().mkcol("/Forbidden")
        assertThat(result).isFalse()
    }

    @Test fun `testConnection success returns Success with entry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(rootMultistatus))
        val result = newClient().testConnection("/")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("PROPFIND")
        assertThat(req.getHeader("Depth")).isEqualTo("0")
        assertThat(result).isInstanceOf(ConnectionResult.Success::class.java)
        val entry = (result as ConnectionResult.Success).entry
        assertThat(entry).isNotNull()
        assertThat(entry!!.displayName).isEqualTo("Root")
    }

    @Test fun `testConnection 401 without credentials returns AuthFailure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"r\""),
        )
        val result = newClient(user = null, pass = null).testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.AuthFailure::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `testConnection 401 with wrong creds retries once then AuthFailure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"r\""),
        )
        server.enqueue(MockResponse().setResponseCode(401))

        val result = newClient(user = "wrong", pass = "creds").testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.AuthFailure::class.java)
        // 首请求无凭据 + 1 次重试
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `testConnection 405 returns NotWebDav`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405))
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.NotWebDav::class.java)
    }
}
