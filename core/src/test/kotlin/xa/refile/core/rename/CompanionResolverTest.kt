package xa.refile.core.rename

import com.google.common.truth.Truth.assertThat
import xa.refile.core.webdav.WebDavClient
import xa.refile.core.webdav.WebDavEntry
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
 * P0-5 修复（审查报告 2026-09-25）：[CompanionResolver.resolve] 改为接收 PROPFIND
 * entries 的纯函数（PROPFIND 由调用方负责并缓存）。测试用 MockWebServer + [WebDavClient]
 * 解析 multistatus 得到 entries 再传入，既覆盖 PROPFIND 解析链路，也覆盖伴随匹配规则
 * （同名、大小写不敏感、中文/空格 href 百分号解码）。
 */
class CompanionResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            baseUrl = server.url("/").toString(),
            username = "user",
            password = "pass",
            client = OkHttpClient(),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** 从 MockWebServer 取一条 PROPFIND 207 multistatus 并解析为 entries（同时验证请求形态）。 */
    private suspend fun propfindEntries(body: String): List<WebDavEntry> {
        server.enqueue(
            MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body),
        )
        val entries = client.propfind("/", 1)
        // 验证 PROPFIND Depth 1 被发送
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("PROPFIND")
        assertThat(req.getHeader("Depth")).isEqualTo("1")
        return entries
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
        val entries = propfindEntries(dirMultistatus)

        val companions = CompanionResolver.resolve("a.mkv", "/target/a.mkv", entries)

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
        val entries = propfindEntries(noCompanions)

        val companions = CompanionResolver.resolve("a.mkv", "/target/a.mkv", entries)

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
        val entries = propfindEntries(mixed)

        val companions = CompanionResolver.resolve("a.mkv", "/target/a.mkv", entries)

        // 仅 a.srt（base 与主文件同名），不含 c.srt（base 不同）
        assertThat(companions).hasSize(1)
        assertThat(companions[0].sourcePath).isEqualTo("/a.srt")
        assertThat(companions[0].targetPath).isEqualTo("/target/a.srt")
    }

    /** P2 修复（报告 #12）：大小写不敏感匹配 —— 主文件 A.mkv 应匹配伴随 a.srt / A.NFO。 */
    @Test fun `resolve matches companions case-insensitively`() = runTest {
        val caseMixed = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            |  <D:response><D:href>/</D:href><D:propstat><D:prop>
            |    <D:resourcetype><D:collection/></D:resourcetype>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/A.mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>A.mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/a.srt</D:href><D:propstat><D:prop>
            |    <D:displayname>a.srt</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/A.nfo</D:href><D:propstat><D:prop>
            |    <D:displayname>A.nfo</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/B.srt</D:href><D:propstat><D:prop>
            |    <D:displayname>B.srt</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |</D:multistatus>""".trimMargin()
        val entries = propfindEntries(caseMixed)

        val companions = CompanionResolver.resolve("/A.mkv", "/target/renamed.mkv", entries)

        // a.srt 与 A.nfo 大小写不敏感匹配成功；B.srt 不匹配
        assertThat(companions).hasSize(2)
        assertThat(companions.map { it.sourcePath }).containsExactly("/a.srt", "/A.nfo")
        assertThat(companions.map { it.targetPath })
            .containsExactly("/target/renamed.srt", "/target/renamed.nfo")
    }

    /**
     * P0-5 修复（审查报告 2026-09-25 P0-5）回归测试：displayName 缺失时经 href 回退取文件名，
     * 基名大小写混合仍应匹配（app 层旧副本此处为大小写敏感比较，曾漏匹配）。
     */
    @Test fun `resolve matches case-mixed base via href fallback when displayname missing`() = runTest {
        val hrefOnly = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            |  <D:response><D:href>/Movies/</D:href><D:propstat><D:prop>
            |    <D:resourcetype><D:collection/></D:resourcetype>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/Movies/Movie.mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>Movie.mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/Movies/movie.srt</D:href><D:propstat><D:prop>
            |    <D:getcontentlength>1000</D:getcontentlength>
            |  </D:prop></D:propstat></D:response>
            |</D:multistatus>""".trimMargin()
        val entries = propfindEntries(hrefOnly)

        val companions = CompanionResolver.resolve("/Movies/Movie.mkv", "/Movies/Renamed.mkv", entries)

        // movie.srt 无 displayname，经 href 回退取名，基名与 Movie 大小写不敏感匹配成功
        assertThat(companions).hasSize(1)
        assertThat(companions[0].sourcePath).isEqualTo("/Movies/movie.srt")
        assertThat(companions[0].targetPath).isEqualTo("/Movies/Renamed.srt")
    }

    /**
     * P0-5 修复（审查报告 2026-09-25 P0-5）回归测试：中文 + 空格的伴随文件 href 为
     * 百分号编码（`%E7%94%B5%E5%BD%B1%20(2024).srt`），href 回退需 URLDecoder.decode
     * 完整解码后才能与已解码主文件基名匹配（app 层旧副本仅替换 %20，曾漏匹配中文）。
     */
    @Test fun `resolve decodes percent-encoded Chinese href with space`() = runTest {
        val chinese = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            |  <D:response><D:href>/Movies/</D:href><D:propstat><D:prop>
            |    <D:resourcetype><D:collection/></D:resourcetype>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/Movies/%E7%94%B5%E5%BD%B1%20(2024).mkv</D:href><D:propstat><D:prop>
            |    <D:displayname>电影 (2024).mkv</D:displayname>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/Movies/%E7%94%B5%E5%BD%B1%20(2024).srt</D:href><D:propstat><D:prop>
            |    <D:getcontentlength>2000</D:getcontentlength>
            |  </D:prop></D:propstat></D:response>
            |  <D:response><D:href>/Movies/%E5%8F%A6%E4%B8%80%E9%83%A8.srt</D:href><D:propstat><D:prop>
            |    <D:getcontentlength>3000</D:getcontentlength>
            |  </D:prop></D:propstat></D:response>
            |</D:multistatus>""".trimMargin()
        val entries = propfindEntries(chinese)

        val companions = CompanionResolver.resolve("/Movies/电影 (2024).mkv", "/目标/电影 (2024).mkv", entries)

        // 仅解码后同名（电影 (2024)）的 srt 匹配；另一部.srt（另一部）不匹配
        assertThat(companions).hasSize(1)
        assertThat(companions[0].sourcePath).isEqualTo("/Movies/电影 (2024).srt")
        assertThat(companions[0].targetPath).isEqualTo("/目标/电影 (2024).srt")
    }
}
