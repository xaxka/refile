package xa.refile.data.repository

import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.tmdb.EpisodeGroupDetail
import xa.refile.core.tmdb.SeasonDetail
import xa.refile.core.tmdb.TmdbClient
import xa.refile.data.db.TmdbCacheDao
import xa.refile.data.db.TmdbCacheEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 详情缓存仓库（Task 20 拆分自原 `TmdbCacheRepository` 的详情缓存职责）。
 *
 * 包装 [TmdbClient] 的详情类请求：[getMovie]/[getTv]/[getSeason]/[getEpisodeGroup] 先查 Room
 * 缓存命中且未过期则直接反序列化返回，否则走网络并回写缓存。[findByTmdbId] 复用本仓库的详情缓存。
 * [enrichWithCollection] 直接透传不缓存（结果含动态 collectionIndex，缓存维度与 [getMovie] 重叠）。
 *
 * 持久缓存键：`"{mediaType}:{tmdbId}:{language}[:{season}]"`，TTL 7 天。命中后由本仓库按 `cachedAt`
 * 判定过期，过期视为未命中并覆盖回写。
 *
 * DI：`@Singleton` + `@Inject constructor`。共享的 OkHttpClient / TmdbClient 缓存 / 并发请求合并
 * 由 [TmdbClientProvider] 提供（两个仓库复用同一实例，保证限流 / 重试状态跨请求共享）。
 *
 * 搜索类请求（searchMovie/searchTv/findByImdbId/findByTvdbId）见 [TmdbSearchRepository]；会话级
 * 内存缓存的清空见 [TmdbSearchRepository.clearSessionCache]。
 */
@Singleton
class TmdbDetailRepository @Inject constructor(
    private val dao: TmdbCacheDao,
    private val clientProvider: TmdbClientProvider,
) {

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

    /** 电影详情：键 `MOVIE:$tmdbId:$language`。 */
    suspend fun getMovie(tmdbId: Int, language: String): MediaMetadata =
        cached(CACHE_MOVIE, tmdbId, language, seasonNumber = null, key = movieKey(tmdbId, language)) {
            clientProvider.buildTmdbClient().getMovie(tmdbId, language)
        }

    /** 剧集详情：键 `TV:$tmdbId:$language`。 */
    suspend fun getTv(tmdbId: Int, language: String): MediaMetadata =
        cached(CACHE_TV, tmdbId, language, seasonNumber = null, key = tvKey(tmdbId, language)) {
            clientProvider.buildTmdbClient().getTv(tmdbId, language)
        }

    /** 季详情：键 `SEASON:$tvId:$season:$language`。 */
    suspend fun getSeason(tvId: Int, seasonNumber: Int, language: String): SeasonDetail =
        cachedSerializable(
            CACHE_SEASON,
            tmdbId = tvId,
            language = language,
            seasonNumber = seasonNumber,
            key = seasonKey(tvId, seasonNumber, language),
            fetch = { clientProvider.buildTmdbClient().getSeason(tvId, seasonNumber, language) },
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
            fetch = { clientProvider.buildTmdbClient().getEpisodeGroup(id) },
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
        clientProvider.buildTmdbClient().enrichWithCollection(movie, language)

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
     * 清空持久化 TMDB 详情缓存（设置页"清除 TMDB 缓存"调用）。
     *
     * 仅清 Room 持久缓存；会话级搜索内存缓存归 [TmdbSearchRepository.clearSessionCache] 管，
     * 调用方（[xa.refile.ui.settings.SettingsViewModel]）需同时调用两者以彻底清空。
     */
    suspend fun clearCache() {
        dao.clearAll()
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
     * 仅缓存 2xx 响应的做法。
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
        val fresh = clientProvider.coalesce(key) { fetch() }
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
        val fresh = clientProvider.coalesce(key) { fetch() }
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
