package xa.refile.core.rename

import com.google.common.truth.Truth.assertThat
import xa.refile.core.webdav.WebDavClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [CompanionResolver] 单元测试。
 *
 * 用 MockWebServer 返回目录的 PROPFIND multistatus，验证仅与主文件同名（去扩展名）
 * 的伴随文件（字幕/nfo/图片）被解析为伴随重命名，且目标路径正确。
 */
class CompanionResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: CompanionResolver

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = WebDavClient(
            baseUrl = server.url("/").toString(),
            username = "user",
            password = "pass",
            client = OkHttpClient(),
        )
        resolver = CompanionResolver(client)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** 目录含 a.mkv + a.srt + a.nfo + b.mkv 的 multistatus。 */
    private val dirMultistatus = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
        |  <D:response><D:href>/</D:href><D:propstat><D:prop>
        |    <D:displayname>Root</D:displayname><D:resourcetype><D:collection/></D:resourcetype>
        |  </D:prop></D:propstat></D:response>
        |  <D:response><D:href>/a.mkv</D:href><D:propstat><D:prop>
        |    <D:displayname>a.mkv</D:displayname>
        |  </D:prop></D:propstat></D:response>
        |  <D:response><D:href>/a.srt</D:href><D:propstat><D:prop>
        |    <D:displayname>a.srt</D:displayname>
        |  </D:prop></D:propstat></D:response>
        |  <D:response><D:href>/a.nfo</D:href><D:propstat><D:prop>
        |    <D:displayname>a.nfo</D:displayname>
        |  </D:prop></D:propstat></D:response>
        |  <D:response><D:href>/b.mkv</D:href><D:propstat><D:prop>
        |    <D:displayname>b.mkv</D:displayname>
        |  </D:prop></D:propstat></D:response>
        |</D:multistatus>""".trimMargin()

    @Test fun `resolve returns companions for same base name`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(dirMultistatus),
        )

        val companions = resolver.resolve("a.mkv", "/target/a.mkv")

        // 验证 PROPFIND Depth 1 被发送
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("PROPFIND")
        assertThat(req.getHeader("Depth")).isEqualTo("1")

        // 仅 a.srt 与 a.nfo，不含主文件 a.mkv 自身，不含 b.mkv
        assertThat(companions).hasSize(2)
        val sources = companions.map { it.sourcePath }
        val targets = companions.map { it.targetPath }
        assertThat(sources).containsExactly("/a.srt", "/a.nfo")
        assertThat(targets).containsExactly("/target/a.srt", "/target/a.nfo")
        // 不应包含主文件或不同名文件
        assertThat(sources).doesNotContain("/a.mkv")
        assertThat(sources).doesNotContain("/b.mkv")
    }

    @Test fun `resolve returns empty when no companions`() = runTest {
        val noCompanions = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            |  <D:response><D:href>/</D:href><D:propstat><D:prop>
            |    <D:resourcetype><D:collection/></D:resourcetype>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/a.mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>a.mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/b.mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>b.mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |</D:multistatus>""".trimMargin()
        server.enqueue(MockResponse().setResponseCode(207).setBody(noCompanions))

        val companions = resolver.resolve("a.mkv", "/target/a.mkv")

        assertThat(companions).isEmpty()
    }

    @Test fun `resolve skips non-matching base name`() = runTest {
        val mixed = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            |  <D:response><D:href>/</D:href><D:propstat><D:prop>
            |    <D:resourcetype><D:collection/></D:resourcetype>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/a.mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>a.mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/a.srt</D:href><D:propstat><D:prop>
            |    <D:displayname>a.srt</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/c.srt</D:href><D:propstat><D:prop>
            |    <D:displayname>c.srt</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |</D:multistatus>""".trimMargin()
        server.enqueue(MockResponse().setResponseCode(207).setBody(mixed))

        val companions = resolver.resolve("a.mkv", "/target/a.mkv")

        // 仅 a.srt（base 与主文件同名），不含 c.srt（base 不同）
        assertThat(companions).hasSize(1)
        assertThat(companions[0].sourcePath).isEqualTo("/a.srt")
        assertThat(companions[0].targetPath).isEqualTo("/target/a.srt")
    }
}
