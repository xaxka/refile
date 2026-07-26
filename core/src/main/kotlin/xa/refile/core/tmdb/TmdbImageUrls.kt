package xa.refile.core.tmdb

/**
 * TMDB 图片基址拼接（计划 §5.4 / Task 2.2.4）。
 *
 * 红线：图片仅来自 `https://image.tmdb.org/t/p/`，不接入其他图片源。
 */
object TmdbImages {

    const val BASE_URL = "https://image.tmdb.org/t/p/"

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
        return BASE_URL + size.trimEnd('/') + "/" + path.trimStart('/')
    }
}
