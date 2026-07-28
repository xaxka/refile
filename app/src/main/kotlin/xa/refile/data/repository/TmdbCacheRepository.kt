package xa.refile.data.repository

import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.tmdb.EpisodeGroupDetail
import xa.refile.core.tmdb.SeasonDetail
import xa.refile.core.tmdb.TmdbClient
import xa.refile.data.db.TmdbCacheDao
import xa.refile.data.db.TmdbCacheEntity
import xa.refile.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 响应缓存仓库（Task 2.3.4）。
 *
 * 包装 [TmdbClient]：详情类请求（[getMovie]/[getTv]/[getSeason]/[getEpisodeGroup]）先查 Room
 * 缓存命中且未过期则直接反序列化返回，否则走网络并回写缓存；搜索类请求（[searchMovie]/[searchTv]）
 * 不持久化到 Room（结果随用户查询变化、且与详情缓存键维度不同），但维护会话级内存缓存
 * （[sessionCache]，Task 3.1/3.2），避免匹配同一剧集的多个分集时重复发 search 请求。
 *
 * 持久缓存键：`"{mediaType}:{tmdbId}:{language}[:{season}]"`，TTL 7 天。命中后由本仓库按 `cachedAt`
 * 判定过期，过期视为未命中并覆盖回写。
 *
 * 会话缓存键：`"{MOVIE|TV}:{query}:{year|null}:{language}"`，仅存活于本仓库实例生命周期，
 * 由 [clearSessionCache] 清空（重新匹配/重置文件时调用）。
 *
 * DI：[TmdbClient] 无法作为稳定单例提供（其构造依赖 DataStore 中的 apiKey/baseUrl，属动态
 * 设置），故本仓库注入 [SettingsRepository] 自行按需构造 [TmdbClient]（与 MatchViewModel 原逻辑
 * 一致：读 baseUrl → [TmdbClient.create]）。apiKey 为空时抛 [IllegalStateException]，
 * 由调用方捕获提示用户。
 */
@Singleton
class TmdbCacheRepository @Inject constructor(
    private val dao: TmdbCacheDao,
    private val settings: SettingsRepository,
) {

    /**
     * 共享 OkHttpClient：所有 TMDB 请求复用同一连接池/线程池，避免每次请求新建 client 造成资源泄漏。
     *
     * 调优（提速关键）：
     * - 默认 OkHttp 单 host 仅 5 个并发连接，是吞吐量的主要瓶颈；TMDB 限制每 IP 20 个并发连接，
     *   这里把单 host 并发与空闲连接池都顶到 20，让 40 req/s 的限流能真正跑满。
     * - 慢速反代（Cloudflare Workers）下放宽超时，避免请求过早失败；同时保留上限防止坏连接长时间挂起。
     */
    private val sharedClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 20
                },
            )
            .build()
    }

    /**
     * 并发请求合并作用域：仅用于把「相同 key 的并发网络请求」合并为一次（见 [coalesce]）。
     * 用 SupervisorJob 保证单个请求失败不影响其它在飞的请求。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 在飞的相同 key 请求（[coalesce] 用），避免批量匹配同一剧集时大量重复 `getTv`/`getSeason`/search。
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /**
     * 把相同 [key] 的并发网络请求合并成一次：第一个到达的发起请求，其余 `await` 同一结果。
     * 命中 Room/会话缓存的路径不进这里（调用方已先查缓存），故仅合并真正打网络的瞬间并发。
     *
     * 用 `CoroutineStart.LAZY` + `putIfAbsent` 实现无锁合并：竞争失败方取消自己未启动的协程并复用胜方结果。
     */
    private suspend fun <T> coalesce(key: String, fetch: suspend () -> T): T {
        inFlight[key]?.let { @Suppress("UNCHECKED_CAST") return (it as Deferred<T>).await() }
        val deferred = ioScope.async(start = CoroutineStart.LAZY) { fetch() }
        val prev = inFlight.putIfAbsent(key, deferred as Deferred<Any?>)
        if (prev != null) {
            deferred.cancel() // LAZY 尚未启动，安全取消
            @Suppress("UNCHECKED_CAST") return (prev as Deferred<T>).await()
        }
        try {
            return deferred.await()
        } finally {
            inFlight.remove(key, deferred as Deferred<Any?>)
        }
    }

    /**
     * 复用同一 [TmdbClient] 实例：其内部的限流 / 重试拦截器状态（[xa.refile.core.tmdb.TmdbRateLimitInterceptor]
     * 的时间戳滑动窗口）必须跨请求共享才有效。若每次请求都重建 client（旧实现），限流器形同虚设，
     * 批量匹配会瞬间打满 TMDB / 反代，触发 429 + 指数退避；经慢速反代（Cloudflare Workers）后整体明显变慢。
     *
     * 故按 `apiKey@baseUrl` 缓存 client，仅在 API Key 或反代地址变化时重建。锁仅用于保护缓存写入，
     * 命中路径（`synchronized` 读）开销极低。
     */
    private val clientLock = Any()
    @Volatile private var cachedClient: TmdbClient? = null
    @Volatile private var cachedClientKey: String? = null

    /** 缓存有效期：7 天（毫秒）。 */
    private val ttlMillis: Long = 7L * 24L * 60L * 60L * 1000L

    /**
     * 缓存 JSON 实例：容错（未知字段忽略，便于 CachedMediaMetadata 字段演进向前兼容），
     * 写默认值保证空集合/空 map 也能正确往返。
     */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * 会话级内存缓存（Task 3.1/3.2）：搜索结果在内存中按 `"$type:$query:$year:$language"` 缓存，
     * 避免匹配同一剧集的多个分集时重复发 search 请求。
     *
     * 这两个方法在 Dispatchers.IO 协程中被调用，多个文件可能并行匹配，故所有访问均通过
     * `synchronized(sessionCache) {}` 串行化 get/put，保证线程安全。
     */
    private val sessionCache = mutableMapOf<String, List<MediaMetadata>>()

    /** 电影详情：键 `MOVIE:$tmdbId:$language`。 */
    suspend fun getMovie(tmdbId: Int, language: String): MediaMetadata =
        cached(CACHE_MOVIE, tmdbId, language, seasonNumber = null, key = movieKey(tmdbId, language)) {
            buildTmdbClient().getMovie(tmdbId, language)
        }

    /** 剧集详情：键 `TV:$tmdbId:$language`。 */
    suspend fun getTv(tmdbId: Int, language: String): MediaMetadata =
        cached(CACHE_TV, tmdbId, language, seasonNumber = null, key = tvKey(tmdbId, language)) {
            buildTmdbClient().getTv(tmdbId, language)
        }

    /** 季详情：键 `SEASON:$tvId:$season:$language`。 */
    suspend fun getSeason(tvId: Int, seasonNumber: Int, language: String): SeasonDetail =
        cachedSerializable(
            CACHE_SEASON,
            tmdbId = tvId,
            language = language,
            seasonNumber = seasonNumber,
            key = seasonKey(tvId, seasonNumber, language),
            fetch = { buildTmdbClient().getSeason(tvId, seasonNumber, language) },
            serializer = SeasonDetail.serializer(),
            // 真实季详情必有非 0 id；TMDB status 错误对象反序列化后 id=0 视为无效，不缓存。
            isValid = { it.id != 0 },
        )

    /** Episode Group 详情：键 `EPISODE_GROUP:$id`（无 language 维度）。id 为十六进制字符串。 */
    suspend fun getEpisodeGroup(id: String): EpisodeGroupDetail =
        cachedSerializable(
            CACHE_EPISODE_GROUP,
            tmdbId = 0,
            language = "",
            seasonNumber = null,
            key = episodeGroupKey(id),
            fetch = { buildTmdbClient().getEpisodeGroup(id) },
            serializer = EpisodeGroupDetail.serializer(),
            // 有效 episode group 必含至少一个分组；空 groups 视为错误/空响应，不缓存。
            isValid = { it.groups.isNotEmpty() },
        )

    /**
     * 合集补全：直接透传 [TmdbClient.enrichWithCollection]，不缓存。
     *
     * 该方法输入为已fetch的 [MediaMetadata]、输出基于合集端点 + movie 详情重新映射，缓存维度与
     * [getMovie] 重叠且结果含动态 collectionIndex，未列入 Task 2.3.4 详情缓存清单，故不缓存。
     */
    suspend fun enrichWithCollection(movie: MediaMetadata, language: String): MediaMetadata =
        buildTmdbClient().enrichWithCollection(movie, language)

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
        val fresh = coalesce("SEARCH_MOVIE:$cacheKey") {
            buildTmdbClient().searchMovie(query, year, language)
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
        val fresh = coalesce("SEARCH_TV:$cacheKey") {
            buildTmdbClient().searchTv(query, year, language)
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
     * （结果随 mediaType/language 维度变化，且命中后立刻走 [getMovie]/[getTv] 详情缓存）。
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
        val fresh = coalesce(cacheKey) {
            buildTmdbClient().findByImdbId(imdbId, mediaType, language)
        }
        synchronized(sessionCache) {
            sessionCache[cacheKey] = fresh?.let { listOf(it) } ?: emptyList()
        }
        return fresh
    }

    /**
     * P3.0：按 TMDB ID 直接拉详情（绕过搜索）。
     *
     * 文件名解析出 TMDB ID 时直接走详情端点，跳过 search + 相似度打分。
     * 由于 [TmdbClient.findByTmdbId] 内部就是 [getMovie]/[getTv]，本方法直接复用
     * 现有详情缓存（[getMovie]/[getTv] 已自带 Room 持久缓存与 TTL），不另开 sessionCache。
     *
     * [mediaType] 必须确定（非空）：调用方在 MatchType.AUTO 时应按 parsed.season/episodes 推断。
     * 返回完整 [MediaMetadata]（带 credits/aliases/localize 等详情字段）。
     */
    suspend fun findByTmdbId(
        tmdbId: Int,
        mediaType: MediaType,
        language: String = "zh-CN",
    ): MediaMetadata = when (mediaType) {
        // 复用现有详情缓存（movieKey/tvKey 键 + Room TTL）
        MediaType.MOVIE -> getMovie(tmdbId, language)
        MediaType.EPISODE -> getTv(tmdbId, language)
    }

    /**
     * P3.0：按 TVDB ID 精确查找（`/find/{external_id}?external_source=tvdb_id`）。
     *
     * 与 [findByImdbId] 一样走 [sessionCache]（同一 TVDB ID 不会重复联网），不持久化到 Room
     * （命中后立刻走 [getMovie]/[getTv] 详情缓存）。
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
        val fresh = coalesce(cacheKey) {
            buildTmdbClient().findByTvdbId(tvdbId, mediaType, language)
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

    /**
     * 清空全部 TMDB 缓存（设置页"清除 TMDB 缓存"调用）。
     *
     * 同时清空持久化数据库缓存（[dao.clearAll]）与会话级内存缓存（[clearSessionCache]），
     * 确保用户主动清空后下一次匹配请求会重新走网络。
     */
    suspend fun clearCache() {
        dao.clearAll()
        clearSessionCache()
    }

    /** 清理已过期缓存（可由设置页或定期 Worker 调用；读时已按 TTL 判定，非必须）。 */
    suspend fun evictExpired() = dao.deleteOlderThan(System.currentTimeMillis() - ttlMillis)

    // ---- 内部：缓存读写 ----

    /**
     * [MediaMetadata] 类缓存通用流程（movie/tv）：查缓存→未过期则反序列化 [CachedMediaMetadata]→
     * 否则网络获取→序列化回写→返回。反序列化失败（缓存损坏/字段演进不兼容）静默回退到网络。
     *
     * 响应有效性：仅当 [isValidMetadata] 为真（TMDB id 非空且非 0）时才回写持久缓存。
     * 反代错误页 / TMDB status 错误对象（如 404 的 `{"status_code":34,...}`）经 Retrofit 反序列化
     * 会落到全默认字段（id=0），若写入缓存会导致后续请求持续命中错误数据。
     * 参考 tmm `InMemoryCachedUrl.java` L79-81 仅缓存 2xx 的做法。
     */
    private suspend fun cached(
        mediaType: String,
        tmdbId: Int,
        language: String,
        seasonNumber: Int?,
        key: String,
        fetch: suspend () -> MediaMetadata,
    ): MediaMetadata {
        val now = System.currentTimeMillis()
        dao.getByKey(key)?.let { existing ->
            if (now - existing.cachedAt < ttlMillis) {
                runCatching {
                    json.decodeFromString(CachedMediaMetadata.serializer(), existing.responseJson)
                }.getOrNull()?.let { return it.toMediaMetadata() }
            }
        }
        val fresh = coalesce(key) { fetch() }
        if (isValidMetadata(fresh)) {
            store(key, mediaType, tmdbId, language, seasonNumber, fresh)
        }
        return fresh
    }

    /**
     * 可直接序列化 DTO（[SeasonDetail]/[EpisodeGroupDetail]）的缓存通用流程。
     *
     * 响应有效性：仅当 [isValid] 为真时才回写持久缓存，避免错误/空响应被缓存。
     */
    private suspend fun <T : Any> cachedSerializable(
        mediaType: String,
        tmdbId: Int,
        language: String,
        seasonNumber: Int?,
        key: String,
        fetch: suspend () -> T,
        serializer: KSerializer<T>,
        isValid: (T) -> Boolean,
    ): T {
        val now = System.currentTimeMillis()
        dao.getByKey(key)?.let { existing ->
            if (now - existing.cachedAt < ttlMillis) {
                runCatching { json.decodeFromString(serializer, existing.responseJson) }
                    .getOrNull()?.let { return it }
            }
        }
        val fresh = coalesce(key) { fetch() }
        if (isValid(fresh)) {
            storeJson(key, mediaType, tmdbId, language, seasonNumber, json.encodeToString(serializer, fresh))
        }
        return fresh
    }

    /**
     * MediaMetadata 有效性判定：TMDB id 非空且非 0 才视为有效详情。
     * 真实 movie/tv 详情必有非 0 id；反代错误页 / TMDB status 错误对象反序列化后 id 为 null/0。
     */
    private fun isValidMetadata(meta: MediaMetadata): Boolean {
        val id = meta.tmdbId ?: meta.id
        return id != null && id != 0
    }

    /** 序列化 [MediaMetadata] 为 [CachedMediaMetadata] JSON 并入库。 */
    private suspend fun store(
        key: String,
        mediaType: String,
        tmdbId: Int,
        language: String,
        seasonNumber: Int?,
        value: MediaMetadata,
    ) {
        val body = json.encodeToString(CachedMediaMetadata.serializer(), value.toCached())
        storeJson(key, mediaType, tmdbId, language, seasonNumber, body)
    }

    private suspend fun storeJson(
        key: String,
        mediaType: String,
        tmdbId: Int,
        language: String,
        seasonNumber: Int?,
        body: String,
    ) {
        dao.insert(
            TmdbCacheEntity(
                cacheKey = key,
                mediaType = mediaType,
                tmdbId = tmdbId,
                language = language,
                seasonNumber = seasonNumber,
                responseJson = body,
                cachedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ---- 内部：TmdbClient 构造（与 MatchViewModel 原逻辑一致） ----

    /**
     * 读 apiKey + 反代地址构造 [TmdbClient]；apiKey 为空抛 [IllegalStateException]。
     *
     * 缓存策略：client 实例按 `apiKey@baseUrl` 维度缓存，避免每次请求都新建 Retrofit 与拦截器链，
     * 保证限流 / 重试拦截器的状态在进程内跨请求共享（修复限流器失效 bug）。配置变更时自动重建。
     */
    private suspend fun buildTmdbClient(): TmdbClient {
        val apiKey = settings.apiKey.first()
        if (apiKey.isBlank()) throw IllegalStateException("请先在设置中填入 TMDB API Key")
        // 用户填了反代地址时，拼成 `proxyUrl + 官方API地址`（Cloudflare Workers Proxy 用法）；
        // 否则直连官方默认 baseUrl。
        val proxy = settings.tmdbProxyUrl.first().takeIf { it.isNotBlank() }
        val baseUrl = if (proxy != null) {
            proxy.trimEnd('/') + "/" + TmdbClient.DEFAULT_BASE_URL
        } else {
            TmdbClient.DEFAULT_BASE_URL
        }
        val key = "$apiKey@$baseUrl"

        // 命中缓存直接返回，复用共享的限流 / 重试状态。
        val hit = synchronized(clientLock) {
            val c = cachedClient
            if (c != null && cachedClientKey == key) c else null
        }
        if (hit != null) return hit

        // 未命中：新建（新 Retrofit + 新拦截器链），再原子写入缓存（双检锁防并发重复建）。
        val client = TmdbClient.create(sharedClient, apiKey, baseUrl)
        synchronized(clientLock) {
            val c = cachedClient
            if (c != null && cachedClientKey == key) {
                return c // 并发下已有同 key 的 client 建好，复用之
            }
            cachedClient = client
            cachedClientKey = key
        }
        return client
    }

    // ---- 内部：缓存键 ----

    private fun movieKey(tmdbId: Int, language: String): String = "$CACHE_MOVIE:$tmdbId:$language"
    private fun tvKey(tmdbId: Int, language: String): String = "$CACHE_TV:$tmdbId:$language"
    private fun seasonKey(tvId: Int, season: Int, language: String): String =
        "$CACHE_SEASON:$tvId:$season:$language"
    private fun episodeGroupKey(id: String): String = "$CACHE_EPISODE_GROUP:$id"

    private companion object {
        const val CACHE_MOVIE = "MOVIE"
        const val CACHE_TV = "TV"
        const val CACHE_SEASON = "SEASON"
        const val CACHE_EPISODE_GROUP = "EPISODE_GROUP"
        const val CACHE_FIND_IMDB = "FIND_IMDB"
        const val CACHE_FIND_TVDB = "FIND_TVDB"
    }
}

// ---- MediaMetadata 缓存快照 ----

/**
 * [MediaMetadata] 的可序列化缓存快照（Task 2.3.4）。
 *
 * [MediaMetadata] 本身在 :core 为普通 data class（非 @Serializable，不可改），故在 app 层镜像其全部
 * 字段以支持 Room JSON 缓存。[MediaType] 已 @Serializable，其余字段均为基本类型/集合，可安全序列化。
 * 字段与 [MediaMetadata] 一一对应，通过 [toCached]/[toMediaMetadata] 无损往返。
 */
@Serializable
private data class CachedMediaMetadata(
    val type: MediaType = MediaType.MOVIE,
    val id: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val name: String? = null,
    val originalName: String? = null,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val collectionName: String? = null,
    val collectionId: Int? = null,
    val collectionIndex: Int? = null,
    val collectionYears: List<Int> = emptyList(),
    val genres: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val originCountries: List<String> = emptyList(),
    val productionCountries: List<String> = emptyList(),
    val runtime: Int? = null,
    val certification: String? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val director: String? = null,
    val actors: List<String> = emptyList(),
    val numberOfSeasons: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumbers: List<Int> = emptyList(),
    val episodeTitles: List<String> = emptyList(),
    val episodeAirDates: List<String> = emptyList(),
    val seasonName: String? = null,
    val seasonYears: List<Int> = emptyList(),
    val seasonAbsoluteStarts: List<Int> = emptyList(),
    val special: Int? = null,
    val info: Map<String, String?> = emptyMap(),
    val localize: Map<String, Map<String, String>> = emptyMap(),
    val order: Map<String, Map<String, Int>> = emptyMap(),
)

/** [MediaMetadata] → 缓存快照。 */
private fun MediaMetadata.toCached(): CachedMediaMetadata = CachedMediaMetadata(
    type = type,
    id = id,
    tmdbId = tmdbId,
    imdbId = imdbId,
    tvdbId = tvdbId,
    name = name,
    originalName = originalName,
    aliases = aliases,
    year = year,
    releaseDate = releaseDate,
    firstAirDate = firstAirDate,
    collectionName = collectionName,
    collectionId = collectionId,
    collectionIndex = collectionIndex,
    collectionYears = collectionYears,
    genres = genres,
    originalLanguage = originalLanguage,
    spokenLanguages = spokenLanguages,
    originCountries = originCountries,
    productionCountries = productionCountries,
    runtime = runtime,
    certification = certification,
    rating = rating,
    votes = votes,
    director = director,
    actors = actors,
    numberOfSeasons = numberOfSeasons,
    seasonNumber = seasonNumber,
    episodeNumbers = episodeNumbers,
    episodeTitles = episodeTitles,
    episodeAirDates = episodeAirDates,
    seasonName = seasonName,
    seasonYears = seasonYears,
    seasonAbsoluteStarts = seasonAbsoluteStarts,
    special = special,
    info = info,
    localize = localize,
    order = order,
)

/** 缓存快照 → [MediaMetadata]。 */
private fun CachedMediaMetadata.toMediaMetadata(): MediaMetadata = MediaMetadata(
    type = type,
    id = id,
    tmdbId = tmdbId,
    imdbId = imdbId,
    tvdbId = tvdbId,
    name = name,
    originalName = originalName,
    aliases = aliases,
    year = year,
    releaseDate = releaseDate,
    firstAirDate = firstAirDate,
    collectionName = collectionName,
    collectionId = collectionId,
    collectionIndex = collectionIndex,
    collectionYears = collectionYears,
    genres = genres,
    originalLanguage = originalLanguage,
    spokenLanguages = spokenLanguages,
    originCountries = originCountries,
    productionCountries = productionCountries,
    runtime = runtime,
    certification = certification,
    rating = rating,
    votes = votes,
    director = director,
    actors = actors,
    numberOfSeasons = numberOfSeasons,
    seasonNumber = seasonNumber,
    episodeNumbers = episodeNumbers,
    episodeTitles = episodeTitles,
    episodeAirDates = episodeAirDates,
    seasonName = seasonName,
    seasonYears = seasonYears,
    seasonAbsoluteStarts = seasonAbsoluteStarts,
    special = special,
    info = info,
    localize = localize,
    order = order,
)
