package xa.refile.core.tmdb

/**
 * TMDB 图片基址拼接（计划 §5.4 / Task 2.2.4）。
 *
 * 红线：图片仅来自 `https://image.tmdb.org/t/p/`，不接入其他图片源。
 *
 * 反代支持：用户在设置页填入 Cloudflare Workers Proxy 地址后（见
 * [xa.refile.data.prefs.SettingsRepository.tmdbProxyUrl]），应用启动时会把该地址写入
 * [proxyUrl]。后续所有图片 URL 都会以 `proxyUrl + BASE_URL + size + path` 形式发出，
 * 绕过国内 DNS 污染。留空则直连官方图片源。
 */
object TmdbImages {

    const val BASE_URL = "https://image.tmdb.org/t/p/"

    /**
     * 反代地址前缀（如 `https://your-worker.workers.dev/`）。
     *
     * 由 app 层在启动与设置变更时写入，[build] 读取时拼在 [BASE_URL] 之前。
     * 用 `@Volatile` 保证多线程可见性；本对象无状态字段，写入是幂等的。
     */
    @Volatile
    var proxyUrl: String = ""

    /** 海报：默认 w342。path 为 TMDB 返回的 `poster_path`（以 `/` 开头），null 返回 null。 */
    fun poster(size: String = "w342", path: String?): String? = build(size, path)

    /** 背景图：默认 w780。 */
    fun backdrop(size: String = "w780", path: String?): String? = build(size, path)

    /** 剧集 still：默认 w300。 */
    fun still(size: String = "w300", path: String?): String? = build(size, path)

    /** 头像/Logo：默认 original。 */
    fun original(path: String?): String? = build("original", path)

    private fun build(size: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val prefix = proxyUrl.trimEnd('/')
        val fullBase = if (prefix.isEmpty()) BASE_URL else "$prefix/$BASE_URL"
        return fullBase + size.trimEnd('/') + "/" + path.trimStart('/')
    }
}
