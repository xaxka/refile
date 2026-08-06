package xa.refile.data.repository

import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 搜索缓存仓库（Task 20 拆分自原 `TmdbCacheRepository` 的搜索缓存职责）。
 *
 * 搜索类请求（[searchMovie]/[searchTv]）与按外部 ID 精确查找（[findByImdbId]/[findByTvdbId]）
 * 不持久化到 Room（搜索结果随用户查询变化、与详情缓存键维度不同），但维护会话级内存缓存
 * （[sessionCache]，Task 3.1/3.2），避免匹配同一剧集的多个分集时重复发 search 请求。
 *
 * 会话缓存键：
 * - search：`"{MOVIE|TV}:{query}:{year|null}:{language}"`。
 * - find：`"{FIND_IMDB|FIND_TVDB}:{id}:{mediaType|null}:{language}"`，null 结果用空列表 sentinel 区分
 *   「未查过」与「查过但未命中」。
 *
 * 仅存活于本仓库实例生命周期，由 [clearSessionCache] 清空（重新匹配/重置文件时调用）。
 *
 * DI：`@Singleton` + `@Inject constructor`。共享的 OkHttpClient / TmdbClient 缓存 / 并发请求合并
 * 由 [TmdbClientProvider] 提供。详情类请求见 [TmdbDetailRepository]。
 *
 * 安全：language 由调用方传入；apiKey 经 [TmdbClientProvider] 读取，不进 UI 状态/日志。
 */
@Singleton
class TmdbSearchRepository @Inject constructor(
    private val clientProvider: TmdbClientProvider,
) {

    /**
     * 会话级内存缓存（Task 3.1/3.2）：搜索 / find 结果在内存中按业务键缓存，避免匹配同一剧集的
     * 多个分集时重复发请求。
     *
     * 这两个方法在 Dispatchers.IO 协程中被调用，多个文件可能并行匹配，故所有访问均通过
     * `synchronized(sessionCache) {}` 串行化 get/put，保证线程安全。
     */
    private val sessionCache = mutableMapOf<String, List<MediaMetadata>>()

    /**
     * 搜索电影：先查 [sessionCache] 命中则返回副本，否则走网络并回写缓存（Task 3.1/3.2）。
     *
     * 缓存键：`"MOVIE:{query.trim()}:{year?:"null"}:{language}"`。
     */
    suspend fun searchMovie(
        query: String,
        year: Int? = null,
        language: String = "zh-CN",
    ): List<MediaMetadata> {
        val cacheKey = "$CACHE_MOVIE:${query.trim()}:${year ?: "null"}:$language"
        synchronized(sessionCache) {
            sessionCache[cacheKey]?.let { return it.toList() }
        }
        val fresh = clientProvider.coalesce("SEARCH_MOVIE:$cacheKey") {
            clientProvider.buildTmdbClient().searchMovie(query, year, language)
        }
        synchronized(sessionCache) {
            sessionCache[cacheKey] = fresh.toList()
        }
        return fresh
    }

    /**
     * 搜索剧集：先查 [sessionCache] 命中则返回副本，否则走网络并回写缓存（Task 3.1/3.2）。
     *
     * 缓存键：`"TV:{query.trim()}:{year?:"null"}:{language}"`。
     */
    suspend fun searchTv(
        query: String,
        year: Int? = null,
        language: String = "zh-CN",
    ): List<MediaMetadata> {
        val cacheKey = "$CACHE_TV:${query.trim()}:${year ?: "null"}:$language"
        synchronized(sessionCache) {
            sessionCache[cacheKey]?.let { return it.toList() }
        }
        val fresh = clientProvider.coalesce("SEARCH_TV:$cacheKey") {
            clientProvider.buildTmdbClient().searchTv(query, year, language)
        }
        synchronized(sessionCache) {
            sessionCache[cacheKey] = fresh.toList()
        }
        return fresh
    }

    /**
     * P2.4：按 IMDb ID 精确查找（`/find/{external_id}?external_source=imdb_id`）。
     *
     * 与 search 一样走 [sessionCache]（同一 IMDb ID 不会重复联网），但不持久化到 Room
     * （结果随 mediaType/language 维度变化，且命中后立刻走 [TmdbDetailRepository.getMovie]/
     * [TmdbDetailRepository.getTv] 详情缓存）。
     *
     * 缓存键：`"FIND_IMDB:{imdbId}:{mediaType?:"null"}:{language}"`。
     * 命中返回轻量 [MediaMetadata]（含 tmdbId/name/year），未命中返回 null（不缓存 null）。
     */
    suspend fun findByImdbId(
        imdbId: String,
        mediaType: MediaType?,
        language: String = "zh-CN",
    ): MediaMetadata? {
        val cacheKey = "$CACHE_FIND_IMDB:$imdbId:${mediaType ?: "null"}:$language"
        synchronized(sessionCache) {
            // null 不缓存：用 sentinel 区分「未查过」与「查过但未命中」
            sessionCache[cacheKey]?.let { return it.firstOrNull() }
        }
        val fresh = clientProvider.coalesce(cacheKey) {
            clientProvider.buildTmdbClient().findByImdbId(imdbId, mediaType, language)
        }
        synchronized(sessionCache) {
            sessionCache[cacheKey] = fresh?.let { listOf(it) } ?: emptyList()
        }
        return fresh
    }

    /**
     * P3.0：按 TVDB ID 精确查找（`/find/{external_id}?external_source=tvdb_id`）。
     *
     * 与 [findByImdbId] 一样走 [sessionCache]（同一 TVDB ID 不会重复联网），不持久化到 Room
     * （命中后立刻走 [TmdbDetailRepository.getMovie]/[TmdbDetailRepository.getTv] 详情缓存）。
     *
     * 缓存键：`"FIND_TVDB:{tvdbId}:{mediaType?:"null"}:{language}"`。
     * 命中返回轻量 [MediaMetadata]，未命中返回 null（不缓存 null）。
     */
    suspend fun findByTvdbId(
        tvdbId: Int,
        mediaType: MediaType?,
        language: String = "zh-CN",
    ): MediaMetadata? {
        val cacheKey = "$CACHE_FIND_TVDB:$tvdbId:${mediaType ?: "null"}:$language"
        synchronized(sessionCache) {
            sessionCache[cacheKey]?.let { return it.firstOrNull() }
        }
        val fresh = clientProvider.coalesce(cacheKey) {
            clientProvider.buildTmdbClient().findByTvdbId(tvdbId, mediaType, language)
        }
        synchronized(sessionCache) {
            sessionCache[cacheKey] = fresh?.let { listOf(it) } ?: emptyList()
        }
        return fresh
    }

    /**
     * 清空会话级搜索结果内存缓存（Task 3.1/3.2）。
     *
     * 供 MatchViewModel 在重新匹配/重置文件时调用，避免旧搜索结果残留影响下一次匹配。
     * 在普通上下文调用即可，内部已 synchronized。
     */
    fun clearSessionCache() {
        synchronized(sessionCache) {
            sessionCache.clear()
        }
    }

    private companion object {
        const val CACHE_MOVIE = "MOVIE"
        const val CACHE_TV = "TV"
        const val CACHE_FIND_IMDB = "FIND_IMDB"
        const val CACHE_FIND_TVDB = "FIND_TVDB"
    }
}
