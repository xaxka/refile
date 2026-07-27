package xa.refile.data.repository

import xa.refile.core.backup.HostsDnsFactory
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
import okhttp3.OkHttpClient
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
 * DI：[TmdbClient] 无法作为稳定单例提供（其构造依赖 DataStore 中的 apiKey/hostsConfig，属动态
 * 设置），故本仓库注入 [SettingsRepository] 自行按需构造 [TmdbClient]（与 MatchViewModel 原逻辑
 * 一致：读 hostsConfig → [HostsDnsFactory] → [TmdbClient.create]）。apiKey 为空时抛
 * [IllegalStateException]，由调用方捕获提示用户。
 */
@Singleton
class TmdbCacheRepository @Inject constructor(
    private val dao: TmdbCacheDao,
    private val settings: SettingsRepository,
) {

    /**
     * 共享 OkHttpClient：所有 TMDB 请求复用同一连接池/线程池，避免每次请求新建 client 造成资源泄漏。
     * hostsConfig 仍按需通过 [HostsDnsFactory] 挂载（newBuilder 复用底层连接池）。
     */
    private val sharedClient by lazy { OkHttpClient() }

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
        val fresh = buildTmdbClient().searchMovie(query, year, language)
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
        val fresh = buildTmdbClient().searchTv(query, year, language)
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
        val fresh = buildTmdbClient().findByImdbId(imdbId, mediaType, language)
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
        val fresh = fetch()
        store(key, mediaType, tmdbId, language, seasonNumber, fresh)
        return fresh
    }

    /**
     * 可直接序列化 DTO（[SeasonDetail]/[EpisodeGroupDetail]）的缓存通用流程。
     */
    private suspend fun <T : Any> cachedSerializable(
        mediaType: String,
        tmdbId: Int,
        language: String,
        seasonNumber: Int?,
        key: String,
        fetch: suspend () -> T,
        serializer: KSerializer<T>,
    ): T {
        val now = System.currentTimeMillis()
        dao.getByKey(key)?.let { existing ->
            if (now - existing.cachedAt < ttlMillis) {
                runCatching { json.decodeFromString(serializer, existing.responseJson) }
                    .getOrNull()?.let { return it }
            }
        }
        val fresh = fetch()
        storeJson(key, mediaType, tmdbId, language, seasonNumber, json.encodeToString(serializer, fresh))
        return fresh
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

    /** 读 apiKey + effectiveHostsConfig 构造 [TmdbClient]；apiKey 为空抛 [IllegalStateException]。 */
    private suspend fun buildTmdbClient(): TmdbClient {
        val apiKey = settings.apiKey.first()
        if (apiKey.isBlank()) throw IllegalStateException("请先在设置中填入 TMDB API Key")
        // 读 effectiveHostsConfig（合并运行时临时禁用状态），而非原始 hostsConfig：
        // 程序启动自动检测到可直连 TMDB 时会临时禁用 hosts，此处需尊重该结果。
        val hostsConfig = settings.effectiveHostsConfig.first()
        val baseClient = HostsDnsFactory.createOkHttpClientWithHosts(hostsConfig, base = sharedClient)
        // 自定义反代地址优先：用户填了 tmdbBaseUrl 时用反代（绕过 DNS 污染），否则用官方默认。
        val baseUrl = settings.tmdbBaseUrl.first().takeIf { it.isNotBlank() }
            ?: TmdbClient.DEFAULT_BASE_URL
        return TmdbClient.create(baseClient, apiKey, baseUrl)
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
