package xa.refile.core.backup

import okhttp3.OkHttpClient

/**
 * [HostsDns] 集成辅助工厂（spec §5.3.1）。
 *
 * 便于 app 层把 TMDB client 的 OkHttp 换成带 hosts 的：在 base client 上 `dns(HostsDns(config))`
 * 重建 client，其余配置（超时、拦截器等）沿用 base。
 */
object HostsDnsFactory {

    /**
     * 在 [base] 上挂载 [HostsDns]，返回新建的 [OkHttpClient]。
     *
     * @param config hosts 配置。
     * @param base 基础 client，复用其 builder 配置；默认新建空 client。
     * @return 装好 hosts DNS 的新 client。
     */
    fun createOkHttpClientWithHosts(
        config: HostsConfig,
        base: OkHttpClient = OkHttpClient(),
    ): OkHttpClient = base.newBuilder()
        .dns(HostsDns(config))
        .build()
}
