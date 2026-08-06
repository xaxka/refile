package xa.refile.core.tmdb

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import org.junit.Test

class TmdbMapperTest {

    @Test fun `parseYear extracts first 4 chars`() {
        assertThat(TmdbMapper.parseYear("2023-01-15")).isEqualTo(2023)
        assertThat(TmdbMapper.parseYear("1999")).isEqualTo(1999)
        assertThat(TmdbMapper.parseYear(null)).isNull()
        assertThat(TmdbMapper.parseYear("")).isNull()
        assertThat(TmdbMapper.parseYear("abc")).isNull()
    }

    @Test fun `movie maps year from release_date`() {
        val movie = MovieDetail(
            id = 1,
            title = "The Matrix",
            releaseDate = "1999-03-31",
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.type).isEqualTo(MediaType.MOVIE)
        assertThat(m.name).isEqualTo("The Matrix")
        assertThat(m.year).isEqualTo(1999)
        assertThat(m.releaseDate).isEqualTo("1999-03-31")
        assertThat(m.id).isEqualTo(1)
        assertThat(m.tmdbId).isEqualTo(1)
    }

    @Test fun `movie extracts director from crew`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            credits = Credits(
                crew = listOf(
                    CrewMember(id = 1, name = "Lana Wachowski", job = "Director"),
                    CrewMember(id = 2, name = "John Doe", job = "Producer"),
                    CrewMember(id = 3, name = "Lilly Wachowski", job = "Director"),
                ),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.director).isEqualTo("Lana Wachowski")
    }

    @Test fun `movie actors top 5 by order`() {
        val cast = (1..6).map { CastMember(id = it, name = "Actor$it", order = it) }
        val movie = MovieDetail(id = 1, title = "X", credits = Credits(cast = cast))
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.actors).hasSize(5)
        assertThat(m.actors).containsExactly("Actor1", "Actor2", "Actor3", "Actor4", "Actor5").inOrder()
    }

    @Test fun `movie actors sorted by order when out of order`() {
        val cast = listOf(
            CastMember(id = 1, name = "Lead", order = 1),
            CastMember(id = 2, name = "Third", order = 3),
            CastMember(id = 3, name = "Second", order = 2),
        )
        val movie = MovieDetail(id = 1, title = "X", credits = Credits(cast = cast))
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.actors).containsExactly("Lead", "Second", "Third").inOrder()
    }

    @Test fun `movie extracts genres names`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            genres = listOf(Genre(1, "Sci-Fi"), Genre(2, "Action")),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.genres).containsExactly("Sci-Fi", "Action")
    }

    @Test fun `movie certification prefers US release_date`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            releaseDates = ReleaseDatesResponse(
                results = listOf(
                    ReleaseDatesByCountry(
                        iso31661 = "JP",
                        releaseDates = listOf(ReleaseDate(certification = "PG-12")),
                    ),
                    ReleaseDatesByCountry(
                        iso31661 = "US",
                        releaseDates = listOf(
                            ReleaseDate(certification = "R"),
                            ReleaseDate(certification = "NC-17"),
                        ),
                    ),
                ),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.certification).isEqualTo("R")
    }

    @Test fun `movie certification falls back to first country when no US`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            releaseDates = ReleaseDatesResponse(
                results = listOf(
                    ReleaseDatesByCountry(
                        iso31661 = "JP",
                        releaseDates = listOf(ReleaseDate(certification = "PG-12")),
                    ),
                ),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.certification).isEqualTo("PG-12")
    }

    @Test fun `movie collection name and id from belongs_to_collection`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            belongsToCollection = CollectionRef(id = 10, name = "The Matrix Collection"),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.collectionName).isEqualTo("The Matrix Collection")
        assertThat(m.collectionId).isEqualTo(10)
        assertThat(m.collectionIndex).isNull()
        assertThat(m.collectionYears).isEmpty()
    }

    @Test fun `movie imdb id from external_ids preferred over root`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            imdbId = "tt0000001",
            externalIds = ExternalIds(imdbId = "tt0133093"),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.imdbId).isEqualTo("tt0133093")
    }

    @Test fun `movie info map contains overview and tagline`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            overview = "An overview.",
            tagline = "A tagline.",
            runtime = 136,
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.info["overview"]).isEqualTo("An overview.")
        assertThat(m.info["tagline"]).isEqualTo("A tagline.")
        assertThat(m.info["runtime"]).isEqualTo("136")
        assertThat(m.runtime).isEqualTo(136)
    }

    @Test fun `movie localize maps translations to lang title`() {
        val movie = MovieDetail(
            id = 1,
            title = "X",
            translations = TranslationsResponse(
                translations = listOf(
                    Translation(
                        iso6391 = "zh",
                        iso31661 = "CN",
                        data = TranslationData(title = "矩阵", overview = "中文概述"),
                    ),
                    Translation(
                        iso6391 = "en",
                        iso31661 = "US",
                        data = TranslationData(title = "The Matrix"),
                    ),
                ),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(movie, "zh-CN")
        assertThat(m.localize["zh"]?.get("n")).isEqualTo("矩阵")
        assertThat(m.localize["zh"]?.get("o")).isEqualTo("中文概述")
        assertThat(m.localize["en"]?.get("n")).isEqualTo("The Matrix")
    }

    @Test fun `tv maps as EPISODE with year from first_air_date`() {
        val tv = TvDetail(
            id = 1,
            name = "The Last of Us",
            firstAirDate = "2023-01-15",
            numberOfSeasons = 1,
        )
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.type).isEqualTo(MediaType.EPISODE)
        assertThat(m.name).isEqualTo("The Last of Us")
        assertThat(m.year).isEqualTo(2023)
        assertThat(m.firstAirDate).isEqualTo("2023-01-15")
        assertThat(m.numberOfSeasons).isEqualTo(1)
    }

    @Test fun `tv director from created_by first`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            createdBy = listOf(Creator(id = 1, name = "Craig Mazin")),
        )
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.director).isEqualTo("Craig Mazin")
    }

    @Test fun `tv certification from content_ratings US`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            contentRatings = ContentRatingsResponse(
                results = listOf(
                    ContentRating(iso31661 = "JP", rating = "PG-12"),
                    ContentRating(iso31661 = "US", rating = "TV-MA"),
                ),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.certification).isEqualTo("TV-MA")
    }

    @Test fun `tv external ids pass through`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            externalIds = ExternalIds(imdbId = "tt3581920", tvdbId = "12345"),
        )
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.imdbId).isEqualTo("tt3581920")
        assertThat(m.tvdbId).isEqualTo("12345")
    }

    @Test fun `season fills episode numbers and titles for single episode`() {
        val tv = TvDetail(id = 1, name = "X", firstAirDate = "2023-01-01")
        val season = SeasonDetail(
            id = 2,
            seasonNumber = 1,
            name = "Season 1",
            episodes = listOf(
                Episode(id = 10, episodeNumber = 1, name = "Pilot", airDate = "2023-01-15"),
                Episode(id = 11, episodeNumber = 2, name = "Infected", airDate = "2023-01-22"),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(tv, season, listOf(1), "zh-CN")
        assertThat(m.seasonNumber).isEqualTo(1)
        assertThat(m.episodeNumbers).containsExactly(1)
        assertThat(m.episodeTitles).containsExactly("Pilot")
        assertThat(m.episodeAirDates).containsExactly("2023-01-15")
        assertThat(m.seasonName).isEqualTo("Season 1")
        assertThat(m.seasonYears).containsExactly(2023)
        assertThat(m.special).isNull()
    }

    @Test fun `multi-episode titles merged with A and B`() {
        val tv = TvDetail(id = 1, name = "X", firstAirDate = "2023-01-01")
        val season = SeasonDetail(
            id = 2,
            seasonNumber = 1,
            name = "Season 1",
            episodes = listOf(
                Episode(id = 10, episodeNumber = 1, name = "Alpha", airDate = "2023-01-15"),
                Episode(id = 11, episodeNumber = 2, name = "Beta", airDate = "2023-01-22"),
                Episode(id = 12, episodeNumber = 3, name = "Gamma", airDate = "2023-01-29"),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(tv, season, listOf(1, 2, 3), "zh-CN")
        assertThat(m.episodeNumbers).containsExactly(1, 2, 3).inOrder()
        assertThat(m.episodeTitles).containsExactly("Alpha & Beta & Gamma")
        assertThat(m.episodeAirDates).containsExactly("2023-01-15", "2023-01-22", "2023-01-29").inOrder()
    }

    @Test fun `season 0 marks special episode number`() {
        val tv = TvDetail(id = 1, name = "X", firstAirDate = "2023-01-01")
        val season = SeasonDetail(
            id = 2,
            seasonNumber = 0,
            name = "Specials",
            episodes = listOf(Episode(id = 10, episodeNumber = 1, name = "Behind the Scenes")),
        )
        val m = TmdbMapper.toMediaMetadata(tv, season, listOf(1), "zh-CN")
        assertThat(m.seasonNumber).isEqualTo(0)
        assertThat(m.special).isEqualTo(1)
    }

    @Test fun `collection fills collectionIndex by release_date order and collectionYears`() {
        val movie = MovieDetail(id = 2, title = "The Matrix Reloaded", releaseDate = "2003-05-15")
        val collection = CollectionDetail(
            id = 2344,
            name = "The Matrix Collection",
            parts = listOf(
                CollectionPart(id = 3, title = "The Matrix Revolutions", releaseDate = "2003-11-05"),
                CollectionPart(id = 1, title = "The Matrix", releaseDate = "1999-03-31"),
                CollectionPart(id = 2, title = "The Matrix Reloaded", releaseDate = "2003-05-15"),
                CollectionPart(id = 4, title = "The Matrix Resurrections", releaseDate = "2021-12-22"),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(movie, collection, "zh-CN")
        // 排序后顺序：1999 -> 2003-05 -> 2003-11 -> 2021，第2部位置 = 2
        assertThat(m.collectionIndex).isEqualTo(2)
        assertThat(m.collectionYears).containsExactly(1999, 2003, 2021).inOrder()
        assertThat(m.collectionName).isEqualTo("The Matrix Collection")
        assertThat(m.collectionId).isEqualTo(2344)
    }

    @Test fun `collection index null when movie not in parts`() {
        val movie = MovieDetail(id = 999, title = "Unrelated", releaseDate = "2020-01-01")
        val collection = CollectionDetail(
            id = 1,
            name = "Some Collection",
            parts = listOf(CollectionPart(id = 1, title = "A", releaseDate = "2010-01-01")),
        )
        val m = TmdbMapper.toMediaMetadata(movie, collection, "zh-CN")
        assertThat(m.collectionIndex).isNull()
        assertThat(m.collectionYears).containsExactly(2010)
    }

    @Test fun `light movie search result maps minimal fields`() {
        val r = MovieResult(
            id = 42,
            title = "Hitchhiker's Guide",
            releaseDate = "2005-04-29",
            overview = "An adventure.",
            posterPath = "/abc.jpg",
            voteAverage = 7.0,
        )
        val m = TmdbMapper.toLightMediaMetadata(r)
        assertThat(m.type).isEqualTo(MediaType.MOVIE)
        assertThat(m.id).isEqualTo(42)
        assertThat(m.name).isEqualTo("Hitchhiker's Guide")
        assertThat(m.year).isEqualTo(2005)
        assertThat(m.info["overview"]).isEqualTo("An adventure.")
        assertThat(m.info["posterPath"]).isEqualTo("/abc.jpg")
    }

    @Test fun `light tv search result maps minimal fields`() {
        val r = TvResult(
            id = 100,
            name = "Firefly",
            firstAirDate = "2002-09-20",
            originCountry = listOf("US"),
            voteAverage = 8.0,
        )
        val m = TmdbMapper.toLightMediaMetadata(r)
        assertThat(m.type).isEqualTo(MediaType.EPISODE)
        assertThat(m.id).isEqualTo(100)
        assertThat(m.name).isEqualTo("Firefly")
        assertThat(m.year).isEqualTo(2002)
        assertThat(m.originCountries).containsExactly("US")
    }

    // ---- DVD 顺序 / episode_groups ----

    @Test fun `dvdEpisodeGroupId picks type 3 group id`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            episodeGroups = EpisodeGroupsResponse(
                results = listOf(
                    EpisodeGroup(id = "air-date-group", name = "Air Date", type = 1),
                    EpisodeGroup(id = "dvd-group-id", name = "DVD Order", type = 3),
                    EpisodeGroup(id = "digital-group", name = "Digital", type = 4),
                ),
            ),
        )
        assertThat(TmdbMapper.dvdEpisodeGroupId(tv)).isEqualTo("dvd-group-id")
    }

    @Test fun `dvdEpisodeGroupId returns null when no dvd group`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            episodeGroups = EpisodeGroupsResponse(
                results = listOf(
                    EpisodeGroup(id = "air-date-group", type = 1),
                    EpisodeGroup(id = "digital-group", type = 4),
                ),
            ),
        )
        assertThat(TmdbMapper.dvdEpisodeGroupId(tv)).isNull()
    }

    @Test fun `dvdEpisodeGroupId returns null when dvd group id blank`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            episodeGroups = EpisodeGroupsResponse(
                results = listOf(EpisodeGroup(id = "", type = 3)),
            ),
        )
        assertThat(TmdbMapper.dvdEpisodeGroupId(tv)).isNull()
    }

    @Test fun `tv info exposes dvdEpisodeGroupId when present`() {
        val tv = TvDetail(
            id = 1,
            name = "X",
            episodeGroups = EpisodeGroupsResponse(
                results = listOf(EpisodeGroup(id = "dvd-group-id", type = 3)),
            ),
        )
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.info["dvdEpisodeGroupId"]).isEqualTo("dvd-group-id")
    }

    @Test fun `tv info omits dvdEpisodeGroupId when absent`() {
        val tv = TvDetail(id = 1, name = "X")
        val m = TmdbMapper.toMediaMetadata(tv, "zh-CN")
        assertThat(m.info).doesNotContainKey("dvdEpisodeGroupId")
    }

    @Test fun `dvdOrderMap aligns original to dvd episode numbers by id`() {
        // 季内原顺序：ep1(id=10) ep2(id=11) ep3(id=12)
        val seasonEpisodes = listOf(
            Episode(id = 10, episodeNumber = 1, name = "Pilot"),
            Episode(id = 11, episodeNumber = 2, name = "Infected"),
            Episode(id = 12, episodeNumber = 3, name = "Long Long Time"),
        )
        // DVD 顺序把 ep3 排到第 2 位（典型 DVD 重排场景）
        val groupDetail = EpisodeGroupDetail(
            id = "dvd-group-id",
            groups = listOf(
                EpisodeGroupChunk(
                    id = "chunk1",
                    episodes = listOf(
                        Episode(id = 10, episodeNumber = 1), // ep1 仍第 1
                        Episode(id = 12, episodeNumber = 2), // 原 ep3 → DVD 第 2
                        Episode(id = 11, episodeNumber = 3), // 原 ep2 → DVD 第 3
                    ),
                ),
            ),
        )
        val map = TmdbMapper.dvdOrderMap(seasonEpisodes, groupDetail)
        assertThat(map["1"]).isEqualTo(1)
        assertThat(map["2"]).isEqualTo(3)
        assertThat(map["3"]).isEqualTo(2)
    }

    @Test fun `dvdOrderMap empty when no id overlap`() {
        val seasonEpisodes = listOf(Episode(id = 10, episodeNumber = 1))
        val groupDetail = EpisodeGroupDetail(
            groups = listOf(
                EpisodeGroupChunk(episodes = listOf(Episode(id = 999, episodeNumber = 1))),
            ),
        )
        assertThat(TmdbMapper.dvdOrderMap(seasonEpisodes, groupDetail)).isEmpty()
    }

    @Test fun `dvdOrderMap skips episodes with id 0 or missing episodeNumber`() {
        val seasonEpisodes = listOf(
            Episode(id = 0, episodeNumber = 1), // id=0 视为缺失，跳过
            Episode(id = 11, episodeNumber = 2),
            Episode(id = 12, episodeNumber = null), // episodeNumber 缺失，跳过
        )
        val groupDetail = EpisodeGroupDetail(
            groups = listOf(
                EpisodeGroupChunk(
                    episodes = listOf(
                        Episode(id = 0, episodeNumber = 5),
                        Episode(id = 11, episodeNumber = 9),
                        Episode(id = 12, episodeNumber = null),
                    ),
                ),
            ),
        )
        val map = TmdbMapper.dvdOrderMap(seasonEpisodes, groupDetail)
        // 仅 id=11 的 episode 两端都有有效 episodeNumber，能匹配
        assertThat(map).hasSize(1)
        assertThat(map["2"]).isEqualTo(9)
    }

    @Test fun `dvdOrderMap empty when group has no episodes`() {
        val seasonEpisodes = listOf(Episode(id = 10, episodeNumber = 1))
        val groupDetail = EpisodeGroupDetail(groups = emptyList())
        assertThat(TmdbMapper.dvdOrderMap(seasonEpisodes, groupDetail)).isEmpty()
    }
}
