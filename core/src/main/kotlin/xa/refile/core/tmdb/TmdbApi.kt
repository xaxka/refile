package xa.refile.core.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB v3 Retrofit service（计划 §5.4 / Task 2.2.2）。
 *
 * `api_key`、`Accept: application/json` 由 [TmdbApiKeyInterceptor] 统一注入。
 */
interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: String? = null,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
    ): MovieSearchResponse

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: String? = null,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
    ): TvSearchResponse

    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String,
        @Query("language") language: String? = null,
    ): MovieDetail

    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String,
        @Query("language") language: String? = null,
    ): TvDetail

    @GET("tv/{id}/season/{season_number}")
    suspend fun seasonDetail(
        @Path("id") id: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("append_to_response") append: String? = null,
        @Query("language") language: String? = null,
    ): SeasonDetail

    @GET("tv/episode_group/{id}")
    suspend fun episodeGroup(
        @Path("id") id: String,
    ): EpisodeGroupDetail

    @GET("collection/{id}")
    suspend fun collection(
        @Path("id") id: Int,
        @Query("language") language: String? = null,
    ): CollectionDetail
}
