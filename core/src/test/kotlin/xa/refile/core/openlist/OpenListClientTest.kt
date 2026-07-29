package xa.refile.core.openlist

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xa.refile.core.webdav.ConnectionResult

/**
 * [OpenListClient] 单测（对标 [xa.refile.core.webdav.WebDavClientTest] 的 MockWebServer 模式）。
 *
 * 覆盖：登录（规范 /api/auth/login，含 2FA / HTTP 400 / code 错误）、propfind（depth 0/1、空目录、
 * 失败、401 续期重试）、move（同目录改名 / 跨目录 + 改名 / 跨目录同名 / 源==目标 / 失败 / 401 续期）、
 * mkcol（成功 / 已存在幂等 / 其它失败 / 401 续期）、testConnection（成功凭据 / 匿名 / 登录失败 /
 * 401 / 405 / 500 / 网络错误），以及 callApi 的 HTTP 非 2xx 与错误体解析分支。
 */
class OpenListClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun newClient(
        user: String? = "admin",
        pass: String? = "pw",
        otp: String? = null,
        baseUrl: String = server.url("/").toString(),
    ): OpenListClient = OpenListClient.create(
        baseUrl = baseUrl,
        username = user,
        password = pass,
        otpCode = otp,
    )

    // 登录成功：{"code":200,"message":"success","data":{"token":"<token>"}}
    private fun enqueueLogin(token: String = "tok-1", code: Int = 200) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":$code,"message":"success","data":{"token":"$token"}}""",
            ),
        )
    }

    private fun enqueueList(content: String = "[]") {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":200,"message":"success","data":{"content":$content,"total":0,"write":true,"provider":"Local"}}""",
            ),
        )
    }

    private fun enqueueOp(code: Int = 200, message: String = "success") {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":$code,"message":"$message","data":null}""",
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // login（规范 /api/auth/login）
    // -----------------------------------------------------------------------------------------

    @Test fun `login success returns token and sends json body without Authorization header`() = runTest {
        enqueueLogin(token = "jwt-xyz")

        val token = newClient().login()

        assertThat(token).isEqualTo("jwt-xyz")
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/auth/login")
        assertThat(req.getHeader("Content-Type")).startsWith("application/json")
        // 登录端点 security 为空，且尚无 token → 不应携带 Authorization。
        assertThat(req.getHeader("Authorization")).isNull()
        val body = req.body.readUtf8()
        assertThat(body).contains("\"username\":\"admin\"")
        assertThat(body).contains("\"password\":\"pw\"")
        assertThat(body).doesNotContain("\"otp_code\"")
    }

    @Test fun `login with otp_code includes field in body`() = runTest {
        enqueueLogin()
        newClient(otp = "123456").login()
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"otp_code\":\"123456\"")
    }

    @Test fun `login anonymous returns empty string without request`() = runTest {
        val token = newClient(user = null, pass = null).login()
        assertThat(token).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `login http 400 throws AuthException with spec message`() = runTest {
        // 规范：HTTP 400 + ErrorResponse {code:400,message:"Invalid request parameters",data:null}
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"code":400,"message":"Invalid request parameters","data":null}""",
            ),
        )
        try {
            newClient().login()
            fail("Expected OpenListAuthException")
        } catch (e: OpenListAuthException) {
            assertThat(e.code).isEqualTo(400)
            assertThat(e.message).isEqualTo("Invalid request parameters")
        }
    }

    @Test fun `login code not 200 with http 200 throws AuthException`() = runTest {
        // OpenList 实际行为：HTTP 200 + code 401（wrong password）
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":401,"message":"wrong password","data":null}""",
            ),
        )
        try {
            newClient().login()
            fail("Expected OpenListAuthException")
        } catch (e: OpenListAuthException) {
            assertThat(e.code).isEqualTo(401)
            assertThat(e.message).isEqualTo("wrong password")
        }
    }

    @Test fun `login success but missing token throws AuthException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":200,"message":"success","data":{"token":null}}""",
            ),
        )
        try {
            newClient().login()
            fail("Expected OpenListAuthException")
        } catch (e: OpenListAuthException) {
            assertThat(e.message).contains("no token")
        }
    }

    // -----------------------------------------------------------------------------------------
    // propfind
    // -----------------------------------------------------------------------------------------

    private val listContent = """[
        |  {"name":"Sub","size":0,"is_dir":true,"modified":"2024-01-01T00:00:00Z","type":1},
        |  {"name":"a.mkv","size":1024,"is_dir":false,"modified":"2024-02-02T00:00:00Z","type":0,"sign":"s","thumb":"t"}
        |]""".trimMargin()

    @Test fun `propfind depth 1 returns self and children with auth header`() = runTest {
        enqueueLogin()
        enqueueList(listContent)

        val entries = newClient().propfind("/Movies", 1)

        // 首请求 = login（无 Authorization），次请求 = list（带 Authorization=token）
        server.takeRequest() // login
        val listReq = server.takeRequest()
        assertThat(listReq.path).isEqualTo("/api/fs/list")
        assertThat(listReq.getHeader("Authorization")).isEqualTo("tok-1")
        assertThat(listReq.body.readUtf8()).contains("\"path\":\"/Movies\"")

        assertThat(entries).hasSize(3)
        assertThat(entries[0].displayName).isEqualTo("Movies")
        assertThat(entries[0].isCollection).isTrue()
        assertThat(entries[0].href).isEqualTo("/Movies")
        assertThat(entries[1].displayName).isEqualTo("Sub")
        assertThat(entries[1].isCollection).isTrue()
        assertThat(entries[2].displayName).isEqualTo("a.mkv")
        assertThat(entries[2].isCollection).isFalse()
        assertThat(entries[2].contentLength).isEqualTo(1024L)
        assertThat(entries[2].href).isEqualTo("/Movies/a.mkv")
        assertThat(entries[2].lastModified).isEqualTo("2024-02-02T00:00:00Z")
    }

    @Test fun `propfind depth 0 returns only self`() = runTest {
        enqueueLogin()
        enqueueList(listContent)
        val entries = newClient().propfind("/Movies", 0)
        assertThat(entries).hasSize(1)
        assertThat(entries[0].displayName).isEqualTo("Movies")
        assertThat(entries[0].isCollection).isTrue()
    }

    @Test fun `propfind empty directory returns only self`() = runTest {
        enqueueLogin()
        enqueueList("null") // content: null
        val entries = newClient().propfind("/Empty", 1)
        assertThat(entries).hasSize(1)
        assertThat(entries[0].displayName).isEqualTo("Empty")
    }

    @Test fun `propfind normalizes relative path with leading slash`() = runTest {
        enqueueLogin()
        enqueueList()
        newClient().propfind("Movies", 1)
        server.takeRequest() // login
        val req = server.takeRequest()
        assertThat(req.body.readUtf8()).contains("\"path\":\"/Movies\"")
    }

    @Test fun `propfind non success code throws OpenListException`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":500,"message":"object not found","data":null}""",
        ))
        try {
            newClient().propfind("/Missing", 1)
            fail("Expected OpenListException")
        } catch (e: OpenListException) {
            assertThat(e.code).isEqualTo(500)
            assertThat(e).isNotInstanceOf(OpenListAuthException::class.java)
        }
    }

    @Test fun `propfind 401 triggers relogin and retry then succeeds`() = runTest {
        enqueueLogin("tok-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        enqueueLogin("tok-2")
        enqueueList(listContent)

        val entries = newClient().propfind("/Movies", 1)

        assertThat(entries).hasSize(3)
        // login(1) → list(401) → login(2) → list(200)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `propfind 401 with bad relogin throws AuthException`() = runTest {
        enqueueLogin("tok-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        // 续期登录失败（凭据失效）
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":400,"message":"wrong password","data":null}""",
        ))
        try {
            newClient().propfind("/Movies", 1)
            fail("Expected OpenListAuthException")
        } catch (e: OpenListAuthException) {
            assertThat(e.code).isEqualTo(400)
        }
    }

    @Test fun `propfind anonymous 401 retries once then throws AuthException`() = runTest {
        // 匿名：ensureLoggedIn 跳过；list 401 → 续期(匿名 no-op) → 重试 list → 仍 401 → 抛出
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        try {
            newClient(user = null, pass = null).propfind("/Secret", 1)
            fail("Expected OpenListAuthException")
        } catch (e: OpenListAuthException) {
            assertThat(e.code).isEqualTo(401)
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    // -----------------------------------------------------------------------------------------
    // move
    // -----------------------------------------------------------------------------------------

    @Test fun `move same parent calls rename and returns true`() = runTest {
        enqueueLogin()
        enqueueOp()
        val ok = newClient().move("/Movies/a.mkv", "/Movies/b.mkv", overwrite = false)

        assertThat(ok).isTrue()
        server.takeRequest() // login
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/api/fs/rename")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"path\":\"/Movies/a.mkv\"")
        assertThat(body).contains("\"name\":\"b.mkv\"")
        assertThat(req.getHeader("Authorization")).isEqualTo("tok-1")
    }

    @Test fun `move cross dir with rename calls move then rename`() = runTest {
        enqueueLogin()
        enqueueOp() // move
        enqueueOp() // rename
        val ok = newClient().move("/Movies/a.mkv", "/Backup/b.mkv", overwrite = false)

        assertThat(ok).isTrue()
        server.takeRequest() // login
        val moveReq = server.takeRequest()
        assertThat(moveReq.path).isEqualTo("/api/fs/move")
        val moveBody = moveReq.body.readUtf8()
        assertThat(moveBody).contains("\"src_dir\":\"/Movies\"")
        assertThat(moveBody).contains("\"dst_dir\":\"/Backup\"")
        assertThat(moveBody).contains("\"names\":[\"a.mkv\"]")
        val renameReq = server.takeRequest()
        assertThat(renameReq.path).isEqualTo("/api/fs/rename")
        val renameBody = renameReq.body.readUtf8()
        assertThat(renameBody).contains("\"path\":\"/Backup/a.mkv\"")
        assertThat(renameBody).contains("\"name\":\"b.mkv\"")
    }

    @Test fun `move cross dir same name calls only move`() = runTest {
        enqueueLogin()
        enqueueOp()
        val ok = newClient().move("/Movies/a.mkv", "/Backup/a.mkv", overwrite = false)

        assertThat(ok).isTrue()
        assertThat(server.requestCount).isEqualTo(2) // login + move（无 rename）
        server.takeRequest() // login
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/api/fs/move")
    }

    @Test fun `move source equals target returns true without request`() = runTest {
        val ok = newClient().move("/a.mkv", "/a.mkv", overwrite = false)
        assertThat(ok).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `move failure returns false`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":500,"message":"object already exists","data":null}""",
        ))
        val ok = newClient().move("/Movies/a.mkv", "/Movies/b.mkv", overwrite = false)
        assertThat(ok).isFalse()
    }

    @Test fun `move 401 retries relogin then succeeds`() = runTest {
        enqueueLogin("tok-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        enqueueLogin("tok-2")
        enqueueOp()
        val ok = newClient().move("/Movies/a.mkv", "/Movies/b.mkv", overwrite = false)
        assertThat(ok).isTrue()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `move network error returns false`() = runTest {
        // 指向不可达端口 → IOException → move 返回 false
        val client = newClient(baseUrl = "http://127.0.0.1:1/")
        val ok = client.move("/a", "/b", overwrite = false)
        assertThat(ok).isFalse()
    }

    @Test fun `move auth failure after relogin returns false`() = runTest {
        // 覆盖 move 的 OpenListAuthException 分支：401 后续期登录失败 → false
        enqueueLogin("tok-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":400,"message":"wrong password","data":null}""",
        ))
        val ok = newClient().move("/Movies/a.mkv", "/Movies/b.mkv", overwrite = false)
        assertThat(ok).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // mkcol
    // -----------------------------------------------------------------------------------------

    @Test fun `mkcol success returns true and sends mkdir body`() = runTest {
        enqueueLogin()
        enqueueOp()
        val ok = newClient().mkcol("/NewDir")
        assertThat(ok).isTrue()
        server.takeRequest() // login
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/api/fs/mkdir")
        assertThat(req.body.readUtf8()).contains("\"path\":\"/NewDir\"")
    }

    @Test fun `mkcol already exists returns idempotent true`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":500,"message":"object already exists","data":null}""",
        ))
        val ok = newClient().mkcol("/Existing")
        assertThat(ok).isTrue()
    }

    @Test fun `mkcol other failure returns false`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":500,"message":"permission denied","data":null}""",
        ))
        val ok = newClient().mkcol("/Forbidden")
        assertThat(ok).isFalse()
    }

    @Test fun `mkcol http 401 triggers relogin retry then succeeds`() = runTest {
        // 覆盖 callApi 的 HTTP 非 2xx + 401 分支与 parseErrorMessage JSON 分支
        enqueueLogin("tok-1")
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":401,"message":"unauthorized","data":null}""",
            ),
        )
        enqueueLogin("tok-2")
        enqueueOp()
        val ok = newClient().mkcol("/NewDir")
        assertThat(ok).isTrue()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `mkcol auth failure after retry returns false`() = runTest {
        enqueueLogin("tok-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":400,"message":"wrong password","data":null}""",
        ))
        val ok = newClient().mkcol("/NewDir")
        assertThat(ok).isFalse()
    }

    @Test fun `mkcol network error returns false`() = runTest {
        // 覆盖 mkcol 的 IOException 分支
        val client = newClient(baseUrl = "http://127.0.0.1:1/")
        val ok = client.mkcol("/NewDir")
        assertThat(ok).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // testConnection
    // -----------------------------------------------------------------------------------------

    @Test fun `testConnection with credentials logs in and lists returns Success`() = runTest {
        enqueueLogin()
        enqueueList()
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.Success::class.java)
        // login + list
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `testConnection anonymous skips login and lists returns Success`() = runTest {
        enqueueList()
        val result = newClient(user = null, pass = null).testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.Success::class.java)
        assertThat(server.requestCount).isEqualTo(1) // 仅 list
        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isNull()
    }

    @Test fun `testConnection login failure returns AuthFailure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":400,"message":"wrong password","data":null}""",
        ))
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.AuthFailure::class.java)
    }

    @Test fun `testConnection list 401 returns AuthFailure`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":401,"message":"unauthorized","data":null}""",
        ))
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.AuthFailure::class.java)
    }

    @Test fun `testConnection list 405 returns NotWebDav`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":405,"message":"not supported","data":null}""",
        ))
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.NotWebDav::class.java)
    }

    @Test fun `testConnection list 500 returns HttpError`() = runTest {
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"code":500,"message":"object not found","data":null}""",
        ))
        val result = newClient().testConnection("/Missing")
        assertThat(result).isInstanceOf(ConnectionResult.HttpError::class.java)
        assertThat((result as ConnectionResult.HttpError).code).isEqualTo(500)
    }

    @Test fun `testConnection http 502 non json body returns HttpError`() = runTest {
        // 覆盖 callApi HTTP 非 2xx(非401) + parseErrorMessage 非 JSON 分支
        enqueueLogin()
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        val result = newClient().testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.HttpError::class.java)
        assertThat((result as ConnectionResult.HttpError).code).isEqualTo(502)
    }

    @Test fun `testConnection network error returns NetworkError`() = runTest {
        val client = newClient(baseUrl = "http://127.0.0.1:1/")
        val result = client.testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.NetworkError::class.java)
    }

    @Test fun `client tolerates baseUrl without trailing slash`() = runTest {
        // 覆盖 ensureTrailingSlash 的 else 分支：baseUrl 缺末尾 '/' 时自动补齐
        enqueueLogin()
        enqueueList()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val client = OpenListClient.create(baseUrl = baseUrl, username = "admin", password = "pw")
        val result = client.testConnection("/")
        assertThat(result).isInstanceOf(ConnectionResult.Success::class.java)
    }
}
