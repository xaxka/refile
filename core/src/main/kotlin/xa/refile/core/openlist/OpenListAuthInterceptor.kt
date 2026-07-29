package xa.refile.core.openlist

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 持有当前 JWT token 的可变容器，供 [OpenListAuthInterceptor] 与 [OpenListClient] 共享。
 *
 * token 在 [OpenListClient.login] 成功后写入；fs 操作经 [OpenListAuthInterceptor] 读取并附加到请求。
 * 使用 [@Volatile] 保证多协程（IO 调度器）下的可见性。
 */
internal class TokenHolder {
    @Volatile
    var token: String? = null
}

/**
 * OpenList JWT 认证拦截器（对标规范 BearerAuth）。
 *
 * 规范明确：登录获得的 JWT token **直接**放入 `Authorization` 头，**不带 `Bearer` 前缀**
 * （`headerPrefix: ''`）。本拦截器据此实现：当 [TokenHolder.token] 非空时附加该头，否则放行原请求
 * （登录本身与匿名访问均无 token）。
 *
 * token 过期/失效时服务器返回 401，由 [OpenListClient] 捕获后重新登录并重试一次（不在本拦截器内
 * 触发重登录，避免与 Retrofit 调用链形成循环）。
 *
 * @param tokenHolder 当前 token 共享容器。
 */
internal class OpenListAuthInterceptor(
    private val tokenHolder: TokenHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenHolder.token
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", token)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
