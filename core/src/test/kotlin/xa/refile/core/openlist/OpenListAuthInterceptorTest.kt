package xa.refile.core.openlist

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [OpenListAuthInterceptor] 单测：验证 token 直接放入 `Authorization` 头（无 `Bearer` 前缀），
 * 以及 token 为空时放行原请求（登录/匿名场景）。
 */
class OpenListAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun request(token: String?, client: OkHttpClient) {
        val req = okhttp3.Request.Builder()
            .url(server.url("/").toString())
            .build()
        client.newCall(req).execute().use { /* 触发请求 */ }
    }

    @Test fun `adds raw token to Authorization header without Bearer prefix`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val holder = TokenHolder().apply { token = "jwt-raw" }
        val client = OkHttpClient.Builder()
            .addInterceptor(OpenListAuthInterceptor(holder))
            .build()
        request(token = "jwt-raw", client = client)
        val req = server.takeRequest()
        // 规范 BearerAuth headerPrefix 为空：token 直接作为 Authorization 值，不带 "Bearer "。
        assertThat(req.getHeader("Authorization")).isEqualTo("jwt-raw")
    }

    @Test fun `omits Authorization header when token is null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val holder = TokenHolder().apply { token = null }
        val client = OkHttpClient.Builder()
            .addInterceptor(OpenListAuthInterceptor(holder))
            .build()
        request(token = null, client = client)
        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isNull()
    }
}
