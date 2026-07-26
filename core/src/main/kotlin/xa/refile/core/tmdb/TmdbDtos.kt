package xa.refile.core.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TMDB API 响应 DTO（计划 §5.4 / Task 2.2.1）。
 *
 * 字段全部可空并附默认值，配合 `Json { ignoreUnknownKeys = true; coerceInputValues = true }`
 * 容忍 TMDB 缺字段或新增字段。snake_case 通过 [@SerialName] 映射。
 */
@Serializable
data class MovieSearchResponse(
    val page: Int = 0,
    val results: List<MovieResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class TvSearchResponse(
    val page: Int = 0,
    val results: List<TvResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class MovieResult(
    val id: Int = 0,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
)

@Serializable
data class TvResult(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
)

@Serializable
data class MovieDetail(
    val id: Int = 0,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val genres: List<Genre> = emptyList(),
    @SerialName("belongs_to_collection") val belongsToCollection: CollectionRef? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("spoken_languages") val spokenLanguages: List<Language> = emptyList(),
    @SerialName("production_countries") val productionCountries: List<Country> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    val credits: Credits? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
    @SerialName("alternative_titles") val alternativeTitles: AltTitlesResponse? = null,
    val translations: TranslationsResponse? = null,
    @SerialName("release_dates") val releaseDates: ReleaseDatesResponse? = null,
)

@Serializable
data class TvDetail(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<Genre> = emptyList(),
    @SerialName("created_by") val createdBy: List<Creator> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("production_countries") val productionCountries: List<Country> = emptyList(),
    @SerialName("spoken_languages") val spokenLanguages: List<Language> = emptyList(),
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    val credits: Credits? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
    @SerialName("alternative_titles") val alternativeTitles: AltTitlesResponse? = null,
    val translations: TranslationsResponse? = null,
    @SerialName("content_ratings") val contentRatings: ContentRatingsResponse? = null,
    @SerialName("episode_groups") val episodeGroups: EpisodeGroupsResponse? = null,
)

@Serializable
data class SeasonDetail(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int? = null,
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val id: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
)

@Serializable
data class EpisodeGroup(
    // TMDB episode group 的 id 是字符串（如 "6a581eeb140cc72ca6d6255c"），用 String 承载避免反序列化失败
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val type: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("group_count") val groupCount: Int? = null,
)

@Serializable
data class EpisodeGroupDetail(
    // /tv/episode_group/{id} 响应的 id 同样是字符串，保持与 EpisodeGroup 一致
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val groups: List<EpisodeGroupChunk> = emptyList(),
)

@Serializable
data class EpisodeGroupChunk(
    // episode group 内部分组的 id 同样是字符串
    val id: String? = null,
    val name: String? = null,
    val order: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class CollectionDetail(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val parts: List<CollectionPart> = emptyList(),
)

@Serializable
data class CollectionPart(
    val id: Int = 0,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class CollectionRef(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class Genre(
    val id: Int = 0,
    val name: String? = null,
)

@Serializable
data class Language(
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("english_name") val englishName: String? = null,
    val name: String? = null,
)

@Serializable
data class Country(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val name: String? = null,
)

@Serializable
data class Credits(
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
)

@Serializable
data class CastMember(
    val id: Int = 0,
    val name: String? = null,
    val character: String? = null,
    val order: Int? = null,
    @SerialName("cast_id") val castId: Int? = null,
)

@Serializable
data class CrewMember(
    val id: Int = 0,
    val name: String? = null,
    val job: String? = null,
    val department: String? = null,
)

@Serializable
data class Creator(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class ExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    @SerialName("facebook_id") val facebookId: String? = null,
    @SerialName("instagram_id") val instagramId: String? = null,
    @SerialName("twitter_id") val twitterId: String? = null,
)

@Serializable
data class AltTitlesResponse(
    val results: List<AltTitle> = emptyList(),
)

@Serializable
data class AltTitle(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val title: String? = null,
    val type: String? = null,
)

@Serializable
data class TranslationsResponse(
    val translations: List<Translation> = emptyList(),
)

@Serializable
data class Translation(
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val name: String? = null,
    @SerialName("english_name") val englishName: String? = null,
    val data: TranslationData? = null,
)

@Serializable
data class TranslationData(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val homepage: String? = null,
    val tagline: String? = null,
)

@Serializable
data class ReleaseDatesResponse(
    val results: List<ReleaseDatesByCountry> = emptyList(),
)

@Serializable
data class ReleaseDatesByCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<ReleaseDate> = emptyList(),
)

@Serializable
data class ReleaseDate(
    val certification: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val type: Int? = null,
    val note: String? = null,
)

@Serializable
data class ContentRatingsResponse(
    val results: List<ContentRating> = emptyList(),
)

@Serializable
data class ContentRating(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val rating: String? = null,
)

@Serializable
data class EpisodeGroupsResponse(
    val results: List<EpisodeGroup> = emptyList(),
)
