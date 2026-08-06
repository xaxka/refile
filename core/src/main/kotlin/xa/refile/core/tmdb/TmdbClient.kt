package xa.refile.core.tmdb

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * TMDB 客户端门面（计划 §5.4 / Task 2.2.2）。
 *
 * 封装 [TmdbApi] 的端点调用 + DTO→[MediaMetadata] 映射 + append_to_response 合并。
 * 不发起除 TMDB 以外的任何元数据请求（红线）。
 *
 * 限流（[TmdbRateLimitInterceptor]）与 429 退避（[TmdbRetryInterceptor]）由调用方
 * 通过 [OkHttpClient] 注入；本类的 [create] 工厂会默认挂上。
 */
class TmdbClient internal constructor(
    private val api: TmdbApi,
) {

    /** 搜索电影，返回轻量 [MediaMetadata] 列表（id/name/year/overview/poster）。 */
    suspend fun searchMovie(
        query: String,
        year: Int? = null,
        language: String = "zh-CN",
    ): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val response = api.searchMovie(
            query = query,
            year = year?.toString(),
            language = language,
        )
        response.results.map { TmdbMapper.toLightMediaMetadata(it) }
    }

    /** 搜索剧集。 */
    suspend fun searchTv(
        query: String,
        year: Int? = null,
        language: String = "zh-CN",
    ): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val response = api.searchTv(
            query = query,
            year = year?.toString(),
            language = language,
        )
        response.results.map { TmdbMapper.toLightMediaMetadata(it) }
    }

    /**
     * P2.4：按 IMDb ID 精确查找（`/find/{external_id}?external_source=imdb_id`）。
     *
     * 文件名中解析到 `tt\d{7,8}` 时直接走该端点，绕过标题相似度打分。
     * [mediaType] 指定取哪个分桶：
     * - [MediaType.MOVIE]：取 `movie_results` 首个
     * - [MediaType.EPISODE]：取 `tv_results` 首个
     * - null：优先 `movie_results`，否则 `tv_results`（AUTO 模式）
     *
     * 返回轻量 [MediaMetadata]（与 search 结果同维度），命中后由调用方走 [getMovie]/[getTv] 拉详情。
     * 未命中返回 null。
     */
    suspend fun findByImdbId(
        imdbId: String,
        mediaType: MediaType?,
        language: String = "zh-CN",
    ): MediaMetadata? = withContext(Dispatchers.IO) {
        val response = api.findByExternalId(
            externalId = imdbId,
            externalSource = "imdb_id",
            language = language,
        )
        when (mediaType) {
            MediaType.MOVIE -> response.movieResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
            MediaType.EPISODE -> response.tvResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
            null -> response.movieResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
                ?: response.tvResults.firstOrNull()
                    ?.let { TmdbMapper.toLightMediaMetadata(it) }
        }
    }

    /**
     * P3.0：按 TMDB ID 直接拉详情（绕过搜索）。
     *
     * 文件名解析出 `[tmdbid-123]` 或 `themoviedb.org/movie/123` 时直接走详情端点，
     * 跳过 search + 相似度打分。与 [findByImdbId] 的关键区别：返回完整 [MediaMetadata]
     * （带 credits/aliases/localize 等详情字段），命中后调用方无需再走 [getMovie]/[getTv]
     * 拉详情，可直接使用返回值构造 [MatchDecision.Auto]。
     *
     * [mediaType] 必须确定（非空）：
     * - [MediaType.MOVIE]：调用 [getMovie]（即 `/movie/{id}?append_to_response=...`）
     * - [MediaType.EPISODE]：调用 [getTv]（即 `/tv/{id}?append_to_response=...`）
     *
     * 注：参数为非空类型，编译期保证 null 不会被传入；调用方在 MatchType.AUTO 时应根据
     * parsed.season/episodes 推断（有则 EPISODE 否则 MOVIE），不要把 null 透传到本方法。
     */
    suspend fun findByTmdbId(
        tmdbId: Int,
        mediaType: MediaType,
        language: String = "zh-CN",
    ): MediaMetadata = when (mediaType) {
        MediaType.MOVIE -> getMovie(tmdbId, language)
        MediaType.EPISODE -> getTv(tmdbId, language)
    }

    /**
     * P3.0：按 TVDB ID 精确查找（`/find/{external_id}?external_source=tvdb_id`）。
     *
     * 文件名解析出 `[tvdbid-123]` 或 `thetvdb.com/series/123` 时直接走该端点。
     * 与 [findByImdbId] 一致的分桶逻辑：
     * - [MediaType.MOVIE]：取 `movie_results` 首个
     * - [MediaType.EPISODE]：取 `tv_results` 首个
     * - null：优先 `movie_results`，否则 `tv_results`（AUTO 模式）
     *
     * 返回轻量 [MediaMetadata]（与 search 结果同维度），命中后由调用方走 [getMovie]/[getTv] 拉详情。
     * 未命中返回 null。
     */
    suspend fun findByTvdbId(
        tvdbId: Int,
        mediaType: MediaType?,
        language: String = "zh-CN",
    ): MediaMetadata? = withContext(Dispatchers.IO) {
        val response = api.findByExternalId(
            externalId = tvdbId.toString(),
            externalSource = "tvdb_id",
            language = language,
        )
        when (mediaType) {
            MediaType.MOVIE -> response.movieResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
            MediaType.EPISODE -> response.tvResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
            null -> response.movieResults.firstOrNull()
                ?.let { TmdbMapper.toLightMediaMetadata(it) }
                ?: response.tvResults.firstOrNull()
                    ?.let { TmdbMapper.toLightMediaMetadata(it) }
        }
    }

    /** 电影详情（append_to_response 合并 credits/external_ids/alternative_titles/translations/release_dates）。 */
    suspend fun getMovie(
        id: Int,
        language: String = "zh-CN",
    ): MediaMetadata = withContext(Dispatchers.IO) {
        val detail = api.movieDetail(
            id = id,
            append = "credits,external_ids,alternative_titles,translations,release_dates",
            language = language,
        )
        TmdbMapper.toMediaMetadata(detail, language)
    }

    /** 剧集详情（append_to_response 合并 credits/external_ids/alternative_titles/translations/content_ratings/episode_groups）。 */
    suspend fun getTv(
        id: Int,
        language: String = "zh-CN",
    ): MediaMetadata = withContext(Dispatchers.IO) {
        val detail = api.tvDetail(
            id = id,
            append = "credits,external_ids,alternative_titles,translations,content_ratings,episode_groups",
            language = language,
        )
        TmdbMapper.toMediaMetadata(detail, language)
    }

    /** 拉取某季详情（含 episodes 列表）。 */
    suspend fun getSeason(
        tvId: Int,
        seasonNumber: Int,
        language: String = "zh-CN",
    ): SeasonDetail = withContext(Dispatchers.IO) {
        api.seasonDetail(
            id = tvId,
            seasonNumber = seasonNumber,
            language = language,
        )
    }

    /** 拉取 episode group 详情（用于绝对集号 / 自定义分组）。 */
    suspend fun getEpisodeGroup(id: String): EpisodeGroupDetail = withContext(Dispatchers.IO) {
        api.episodeGroup(id)
    }

    /**
     * 仅当 [movie].[MediaMetadata.collectionId] 非空时调用 collection 端点补充
     * [MediaMetadata.collectionIndex] 与 [MediaMetadata.collectionYears]。
     *
     * 注意：当前 [MediaMetadata] 不携带原始 [MovieDetail]；本方法重新拉取 movie 详情以拿到 id
     * 用于在合集中定位。若调用方已有 MovieDetail，可直接调用 [TmdbMapper.toMediaMetadata]。
     */
    suspend fun enrichWithCollection(
        movie: MediaMetadata,
        language: String = "zh-CN",
    ): MediaMetadata = withContext(Dispatchers.IO) {
        val collectionId = movie.collectionId ?: return@withContext movie
        val detail = api.movieDetail(
            id = movie.id ?: return@withContext movie,
            append = "credits,external_ids,alternative_titles,translations,release_dates",
            language = language,
        )
        val collection = api.collection(collectionId, language)
        TmdbMapper.toMediaMetadata(detail, collection, language)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org/3/"

        /**
         * 构建 [TmdbClient]：组装 Retrofit，挂上 kotlinx-serialization converter
         * （`ignoreUnknownKeys=true; coerceInputValues=true`）与限流 + 重试 + API key 拦截器。
         *
         * 若调用方需要自定义 [OkHttpClient]（如加日志拦截器），可自行构建并传入；
         * 本方法会以 newBuilder() 在其基础上追加 TMDB 专属拦截器。
         */
        fun create(
            okHttpClient: OkHttpClient,
            apiKey: String,
            baseUrl: String = DEFAULT_BASE_URL,
        ): TmdbClient {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
            // 顺序：ApiKey（外）→ Retry → RateLimit（内）。
            // Retry 在 RateLimit 之外，retry 的 chain.proceed() 会再次进入 RateLimit，重试请求同样受限流。
            val client = okHttpClient.newBuilder()
                .addInterceptor(TmdbApiKeyInterceptor(apiKey))
                .addInterceptor(TmdbRetryInterceptor())
                .addInterceptor(TmdbRateLimitInterceptor())
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return TmdbClient(retrofit.create(TmdbApi::class.java))
        }

        /** 测试用工厂：允许注入自定义限流/重试参数与 sleeper。 */
        internal fun createForTest(
            okHttpClient: OkHttpClient,
            apiKey: String,
            baseUrl: String,
            rateLimit: TmdbRateLimitInterceptor,
            retry: TmdbRetryInterceptor,
        ): TmdbClient {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
            // 顺序：ApiKey（外）→ retry → rateLimit（内），重试请求同样经过限流。
            val client = okHttpClient.newBuilder()
                .addInterceptor(TmdbApiKeyInterceptor(apiKey))
                .addInterceptor(retry)
                .addInterceptor(rateLimit)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return TmdbClient(retrofit.create(TmdbApi::class.java))
        }
    }
}
