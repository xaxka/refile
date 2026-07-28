package xa.refile.core.tmdb

import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata

/**
 * TMDB DTO → [MediaMetadata] 映射（计划 §5.4 / Task 2.2.2）。
 *
 * 仅做无副作用的纯函数映射；不发起任何网络请求，由 [TmdbClient] 在调用端点后调用。
 */
object TmdbMapper {

    /** 默认首选地区（电影 release_dates / 剧集 content_ratings）。 */
    const val PREFERRED_COUNTRY = "US"

    /** 从 ISO 日期（yyyy-MM-dd 或类似）取前 4 位年份。 */
    fun parseYear(date: String?): Int? = date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

    /** 电影详情 → [MediaMetadata]（MOVIE）。 */
    fun toMediaMetadata(movie: MovieDetail, language: String): MediaMetadata {
        val year = parseYear(movie.releaseDate)
        val genres = movie.genreNames()
        val director = movie.credits?.crew
            ?.firstOrNull { it.job.equals("Director", ignoreCase = true) }?.name
        val actors = movie.credits?.cast
            ?.sortedBy { it.order ?: Int.MAX_VALUE }
            ?.take(5)
            ?.mapNotNull { it.name }
            .orEmpty()
        val certification = pickMovieCertification(movie.releaseDates)
        val aliases = movie.alternativeTitles?.results
            ?.mapNotNull { it.title }
            .orEmpty()
        val info = buildMap {
            movie.overview?.let { put("overview", it) }
            movie.tagline?.let { put("tagline", it) }
            movie.popularity?.let { put("popularity", it.toString()) }
            movie.runtime?.let { put("runtime", it.toString()) }
            movie.posterPath?.let { put("posterPath", it) }
            movie.backdropPath?.let { put("backdropPath", it) }
        }
        val localize = buildLocalize(movie.translations, isMovie = true)

        return MediaMetadata(
            type = MediaType.MOVIE,
            id = movie.id,
            tmdbId = movie.id,
            imdbId = movie.externalIds?.imdbId ?: movie.imdbId,
            name = movie.title,
            originalName = movie.originalTitle,
            aliases = aliases,
            year = year,
            releaseDate = movie.releaseDate,
            collectionName = movie.belongsToCollection?.name,
            collectionId = movie.belongsToCollection?.id,
            genres = genres,
            originalLanguage = movie.originalLanguage,
            spokenLanguages = movie.spokenLanguages.mapNotNull { it.iso6391 },
            originCountries = movie.originCountry,
            productionCountries = movie.productionCountries.mapNotNull { it.iso31661 },
            runtime = movie.runtime,
            certification = certification,
            rating = movie.voteAverage,
            votes = movie.voteCount,
            director = director,
            actors = actors,
            info = info,
            localize = localize,
        )
    }

    /** 剧集详情 → [MediaMetadata]（EPISODE，无季/集上下文）。 */
    fun toMediaMetadata(tv: TvDetail, language: String): MediaMetadata {
        val year = parseYear(tv.firstAirDate)
        val director = tv.createdBy.firstOrNull()?.name
        val certification = pickTvCertification(tv.contentRatings)
        val aliases = tv.alternativeTitles?.results
            ?.mapNotNull { it.title }
            .orEmpty()
        val info = buildMap {
            tv.overview?.let { put("overview", it) }
            tv.tagline?.let { put("tagline", it) }
            tv.numberOfEpisodes?.let { put("episodeCount", it.toString()) }
            tv.posterPath?.let { put("posterPath", it) }
            tv.backdropPath?.let { put("backdropPath", it) }
            // 暴露 DVD episode group id，供 MatchViewModel 按需拉 group detail 换算 DVD 顺序。
            dvdEpisodeGroupId(tv)?.let { put("dvdEpisodeGroupId", it) }
        }
        val localize = buildLocalize(tv.translations, isMovie = false)

        return MediaMetadata(
            type = MediaType.EPISODE,
            id = tv.id,
            tmdbId = tv.id,
            tvdbId = tv.externalIds?.tvdbId,
            imdbId = tv.externalIds?.imdbId,
            name = tv.name,
            originalName = tv.originalName,
            aliases = aliases,
            year = year,
            firstAirDate = tv.firstAirDate,
            genres = tv.genreNames(),
            originalLanguage = tv.originalLanguage,
            spokenLanguages = tv.spokenLanguages.mapNotNull { it.iso6391 },
            originCountries = tv.originCountry,
            productionCountries = tv.productionCountries.mapNotNull { it.iso31661 },
            runtime = tv.episodeRunTime.firstOrNull(),
            certification = certification,
            rating = tv.voteAverage,
            votes = tv.voteCount,
            director = director,
            numberOfSeasons = tv.numberOfSeasons,
            info = info,
            localize = localize,
        )
    }

    /**
     * 剧集 + 季 + 集号 → [MediaMetadata]（EPISODE，含季/集上下文）。
     *
     * 集标题按 [episodeNumbers] 顺序从 [SeasonDetail.episodes] 按 episode_number 取 name，
     * 多集合并为 `A & B & C`。
     */
    fun toMediaMetadata(
        tv: TvDetail,
        season: SeasonDetail,
        episodeNumbers: List<Int>,
        language: String,
    ): MediaMetadata {
        val base = toMediaMetadata(tv, language)
        val seasonNumber = season.seasonNumber
        val episodesByNum = season.episodes
            .filter { it.episodeNumber != null }
            .associateBy { it.episodeNumber!! }

        val titles = episodeNumbers.mapNotNull { episodesByNum[it]?.name }
        val airDates = episodeNumbers.mapNotNull { episodesByNum[it]?.airDate }
        val seasonYears = seasonAirYears(season)

        return base.copy(
            seasonNumber = seasonNumber,
            episodeNumbers = episodeNumbers,
            episodeTitles = if (titles.size > 1) listOf(titles.joinToString(" & ")) else titles,
            episodeAirDates = airDates,
            seasonName = season.name,
            seasonYears = seasonYears,
            special = if (seasonNumber == 0) episodeNumbers.firstOrNull() else null,
        )
    }

    /**
     * 电影 + 合集详情 → [MediaMetadata]（在 [toMediaMetadata] 基础上补充 [MediaMetadata.collectionIndex]
     * 与 [MediaMetadata.collectionYears]）。
     *
     * collectionIndex：按 [CollectionPart.releaseDate] 升序定位当前 [MovieDetail.id] 在合集中的位置（1-based）。
     * collectionYears：合集中所有作品年份（去 null）。
     */
    fun toMediaMetadata(
        movie: MovieDetail,
        collection: CollectionDetail,
        language: String,
    ): MediaMetadata {
        val base = toMediaMetadata(movie, language)
        val sortedParts = collection.parts
            .sortedBy { it.releaseDate ?: "" }
        val years = collection.parts
            .mapNotNull { parseYear(it.releaseDate) }
            .distinct()
            .sorted()
        val index = sortedParts.indexOfFirst { it.id == movie.id }
            .let { if (it < 0) null else it + 1 }
        return base.copy(
            collectionName = collection.name ?: base.collectionName,
            collectionId = collection.id.takeIf { it != 0 } ?: base.collectionId,
            collectionIndex = index,
            collectionYears = years,
        )
    }

    /** 搜索结果轻量映射：电影条目。 */
    fun toLightMediaMetadata(result: MovieResult): MediaMetadata = MediaMetadata(
        type = MediaType.MOVIE,
        id = result.id,
        tmdbId = result.id,
        name = result.title,
        originalName = result.originalTitle,
        year = parseYear(result.releaseDate),
        releaseDate = result.releaseDate,
        rating = result.voteAverage,
        info = buildMap {
            result.overview?.let { put("overview", it) }
            result.posterPath?.let { put("posterPath", it) }
        },
    )

    /** 搜索结果轻量映射：剧集条目。 */
    fun toLightMediaMetadata(result: TvResult): MediaMetadata = MediaMetadata(
        type = MediaType.EPISODE,
        id = result.id,
        tmdbId = result.id,
        name = result.name,
        originalName = result.originalName,
        year = parseYear(result.firstAirDate),
        firstAirDate = result.firstAirDate,
        originCountries = result.originCountry,
        rating = result.voteAverage,
        info = buildMap {
            result.overview?.let { put("overview", it) }
            result.posterPath?.let { put("posterPath", it) }
        },
    )

    // ---- 内部工具 ----

    private fun MovieDetail.genreNames(): List<String> = genres.mapNotNull { it.name }
    private fun TvDetail.genreNames(): List<String> = genres.mapNotNull { it.name }

    /**
     * TMDB episode group type 常量。
     * 1=Original Air Date / 2=Absolute / 3=DVD / 4=Digital / 5=Story Arc / 6=Production / 7=TV。
     */
    const val EPISODE_GROUP_TYPE_DVD = 3

    /**
     * 取剧集的 DVD 顺序 episode group id（type==3）。
     *
     * [TvDetail.episodeGroups] 由 `getTv` 的 `append_to_response=episode_groups` 一并返回，
     * 仅含分组摘要（id/name/type/计数），无 episode 级映射。命中 id 后由调用方再调
     * [xa.refile.core.tmdb.TmdbClient.getEpisodeGroup] 拉详情做绝对集号→DVD 顺序换算。
     * 无 DVD 分组时返回 null。
     */
    fun dvdEpisodeGroupId(tv: TvDetail): String? =
        tv.episodeGroups?.results
            ?.firstOrNull { it.type == EPISODE_GROUP_TYPE_DVD && !it.id.isNullOrBlank() }
            ?.id

    /**
     * 构建 "原集号(String) → DVD 顺序集号(Int)" 映射，供模板变量 `{order.dvd.e}` 使用。
     *
     * DVD episode group 详情的每个 [EpisodeGroupChunk.episodes] 携带 TMDB episode `id` 与
     * DVD 顺序下的 `episode_number`（即 group 内重排后的集号）。本函数把当前季 episodes 按 `id`
     * 与 DVD group 内 episode 对齐，返回 originalEpisodeNumber → dvdEpisodeNumber。
     * 任一侧缺失 id / episodeNumber 或未匹配则跳过该集。
     */
    fun dvdOrderMap(
        seasonEpisodes: List<Episode>,
        groupDetail: EpisodeGroupDetail,
    ): Map<String, Int> {
        // DVD group 内 episode id → DVD 顺序集号
        // [Episode.id] 为非空 Int（默认 0），用 0 表示缺失（TMDB status 错误对象反序列化后 id=0）。
        val dvdById: Map<Int, Int> = groupDetail.groups
            .flatMap { it.episodes }
            .mapNotNull { ep ->
                val id = ep.id.takeIf { it != 0 } ?: return@mapNotNull null
                val dvdNum = ep.episodeNumber ?: return@mapNotNull null
                id to dvdNum
            }
            .toMap()
        return seasonEpisodes.mapNotNull { ep ->
            val tmdbId = ep.id.takeIf { it != 0 } ?: return@mapNotNull null
            val origNum = ep.episodeNumber ?: return@mapNotNull null
            val dvdNum = dvdById[tmdbId] ?: return@mapNotNull null
            origNum.toString() to dvdNum
        }.toMap()
    }

    /** 电影 release_dates 首选 US 的第一项 certification，否则取首个国家。 */
    private fun pickMovieCertification(releaseDates: ReleaseDatesResponse?): String? {
        val byCountry = releaseDates?.results ?: return null
        return byCountry.firstOrNull { it.iso31661.equals(PREFERRED_COUNTRY, ignoreCase = true) }
            ?.releaseDates
            ?.firstOrNull()
            ?.certification
            ?.takeIf { it.isNotBlank() }
            ?: byCountry.firstOrNull()
                ?.releaseDates
                ?.firstOrNull()
                ?.certification
                ?.takeIf { it.isNotBlank() }
    }

    /** 剧集 content_ratings 首选 US 的 rating，否则取首个国家。 */
    private fun pickTvCertification(contentRatings: ContentRatingsResponse?): String? {
        val results = contentRatings?.results ?: return null
        return results.firstOrNull { it.iso31661.equals(PREFERRED_COUNTRY, ignoreCase = true) }
            ?.rating
            ?.takeIf { it.isNotBlank() }
            ?: results.firstOrNull()
                ?.rating
                ?.takeIf { it.isNotBlank() }
    }

    /** 季内所有 episode.airDate 的年份（去 null，排序）。 */
    private fun seasonAirYears(season: SeasonDetail): List<Int> = season.episodes
        .mapNotNull { parseYear(it.airDate) }
        .distinct()
        .sorted()

    /**
     * 把 translations 响应转为 `lang -> { var -> value }`。
     * - 电影：title 来自 [TranslationData.title]
     * - 剧集：title 来自 [TranslationData.name]
     * - 同时写入 overview/tagline。
     */
    private fun buildLocalize(
        translations: TranslationsResponse?,
        isMovie: Boolean,
    ): Map<String, Map<String, String>> {
        val list = translations?.translations ?: return emptyMap()
        return list.mapNotNull { t ->
            val lang = t.iso6391 ?: return@mapNotNull null
            val data = t.data ?: return@mapNotNull null
            val title = if (isMovie) data.title else data.name
            val inner = buildMap<String, String> {
                title?.let { put("n", it) }
                data.overview?.let { put("o", it) }
                data.tagline?.let { put("t", it) }
            }
            if (inner.isEmpty()) null else lang to inner
        }.toMap()
    }
}
