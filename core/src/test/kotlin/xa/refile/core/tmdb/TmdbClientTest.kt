package xa.refile.core.tmdb

import com.google.common.truth.Truth.assertThat
import xa.refile.core.naming.MediaMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class TmdbClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TmdbClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        // 用 createForTest 注入 fake sleeper，让 429 退避测试不真睡 1s
        client = TmdbClient.createForTest(
            okHttpClient = OkHttpClient.Builder().build(),
            apiKey = "test-api-key",
            baseUrl = server.url("/").toString(),
            rateLimit = TmdbRateLimitInterceptor(
                maxRequests = 1000,
                windowMillis = 60_000L,
                sleeper = Sleeper { /* no-op */ },
            ),
            retry = TmdbRetryInterceptor(
                maxRetries = 3,
                sleeper = Sleeper { /* no-op */ },
            ),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `searchMovie sends query api_key and Accept header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "page": 1,
                      "total_pages": 1,
                      "total_results": 1,
                      "results": [
                        {
                          "id": 603,
                          "title": "The Matrix",
                          "original_title": "The Matrix",
                          "release_date": "1999-03-30",
                          "overview": "Set in the 22nd century...",
                          "poster_path": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
                          "vote_average": 8.2,
                          "popularity": 50.0
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val results = client.searchMovie("matrix", year = 1999, language = "zh-CN")

        val recorded = server.takeRequest()
        assertThat(recorded.path).startsWith("/search/movie")
        assertThat(recorded.path).contains("query=matrix")
        assertThat(recorded.path).contains("year=1999")
        assertThat(recorded.path).contains("language=zh-CN")
        assertThat(recorded.path).contains("api_key=test-api-key")
        assertThat(recorded.getHeader("Accept")).isEqualTo("application/json")

        assertThat(results).hasSize(1)
        val m = results.first()
        assertThat(m.id).isEqualTo(603)
        assertThat(m.name).isEqualTo("The Matrix")
        assertThat(m.year).isEqualTo(1999)
        assertThat(m.rating).isEqualTo(8.2)
        assertThat(m.info["overview"]).contains("22nd century")
        assertThat(m.info["posterPath"]).isEqualTo("/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg")
    }

    @Test fun `searchTv sends query and parses tv results`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "results": [
                        {
                          "id": 100088,
                          "name": "The Last of Us",
                          "original_name": "The Last of Us",
                          "first_air_date": "2023-01-15",
                          "overview": "Twenty years after modern civilization has been destroyed...",
                          "poster_path": "/uYrF9PMZLrQy6JUXV3V7ZXcdnB0.jpg",
                          "vote_average": 8.4,
                          "origin_country": ["US"]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val results = client.searchTv("last of us")
        val recorded = server.takeRequest()
        assertThat(recorded.path).startsWith("/search/tv")
        assertThat(recorded.path).contains("query=last%20of%20us")
        assertThat(recorded.path).contains("api_key=test-api-key")

        assertThat(results).hasSize(1)
        val m = results.first()
        assertThat(m.id).isEqualTo(100088)
        assertThat(m.name).isEqualTo("The Last of Us")
        assertThat(m.year).isEqualTo(2023)
        assertThat(m.originCountries).containsExactly("US").inOrder()
    }

    @Test fun `getMovie uses append_to_response and maps full detail`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 603,
                      "title": "The Matrix",
                      "original_title": "The Matrix",
                      "release_date": "1999-03-30",
                      "overview": "Set in the 22nd century, The Matrix tells the story of...",
                      "tagline": "Welcome to the Real World.",
                      "runtime": 136,
                      "imdb_id": "tt0133093",
                      "vote_average": 8.2,
                      "vote_count": 26000,
                      "original_language": "en",
                      "genres": [{"id": 878, "name": "Science Fiction"}, {"id": 28, "name": "Action"}],
                      "belongs_to_collection": {"id": 2344, "name": "The Matrix Collection"},
                      "spoken_languages": [{"iso_639_1": "en", "name": "English"}],
                      "production_countries": [{"iso_3166_1": "US", "name": "United States of America"}],
                      "origin_country": ["US"],
                      "credits": {
                        "cast": [
                          {"id": 6384, "name": "Keanu Reeves", "character": "Neo", "order": 0},
                          {"id": 5294, "name": "Laurence Fishburne", "character": "Morpheus", "order": 1},
                          {"id": 530, "name": "Carrie-Anne Moss", "character": "Trinity", "order": 2},
                          {"id": 1, "name": "Actor4", "order": 3},
                          {"id": 2, "name": "Actor5", "order": 4},
                          {"id": 3, "name": "Actor6", "order": 5}
                        ],
                        "crew": [
                          {"id": 93314, "name": "Lana Wachowski", "job": "Director"},
                          {"id": 93315, "name": "Lilly Wachowski", "job": "Director"}
                        ]
                      },
                      "external_ids": {"imdb_id": "tt0133093", "tvdb_id": null, "facebook_id": null},
                      "alternative_titles": {"results": [{"iso_3166_1": "US", "title": "Matrix"}]},
                      "translations": {"translations": [
                        {"iso_639_1": "zh", "iso_3166_1": "CN", "data": {"title": "黑客帝国", "overview": "概述"}}
                      ]},
                      "release_dates": {"results": [
                        {"iso_3166_1": "US", "release_dates": [{"certification": "R", "type": 3}]},
                        {"iso_3166_1": "JP", "release_dates": [{"certification": "PG-12"}]}
                      ]}
                    }
                    """.trimIndent(),
                ),
        )

        val m = client.getMovie(603, language = "zh-CN")

        // 验证请求路径与 append_to_response
        val recorded = server.takeRequest()
        assertThat(recorded.path).startsWith("/movie/603")
        assertThat(recorded.path).contains("append_to_response=")
        val appendParam = URLDecoder.decode(
            recorded.path!!.substringAfter("append_to_response=").substringBefore("&"),
            "UTF-8",
        )
        assertThat(appendParam.split(","))
            .containsAtLeast("credits", "external_ids", "alternative_titles", "translations", "release_dates")
        assertThat(recorded.path).contains("language=zh-CN")
        assertThat(recorded.getHeader("Accept")).isEqualTo("application/json")

        // 验证映射字段
        assertThat(m.id).isEqualTo(603)
        assertThat(m.tmdbId).isEqualTo(603)
        assertThat(m.name).isEqualTo("The Matrix")
        assertThat(m.originalName).isEqualTo("The Matrix")
        assertThat(m.year).isEqualTo(1999)
        assertThat(m.releaseDate).isEqualTo("1999-03-30")
        assertThat(m.imdbId).isEqualTo("tt0133093")
        assertThat(m.genres).containsExactly("Science Fiction", "Action").inOrder()
        assertThat(m.director).isEqualTo("Lana Wachowski")
        assertThat(m.actors).hasSize(5)
        assertThat(m.actors).containsExactly(
            "Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss", "Actor4", "Actor5",
        ).inOrder()
        assertThat(m.certification).isEqualTo("R")
        assertThat(m.runtime).isEqualTo(136)
        assertThat(m.rating).isEqualTo(8.2)
        assertThat(m.votes).isEqualTo(26000)
        assertThat(m.collectionName).isEqualTo("The Matrix Collection")
        assertThat(m.collectionId).isEqualTo(2344)
        assertThat(m.originalLanguage).isEqualTo("en")
        assertThat(m.spokenLanguages).containsExactly("en")
        assertThat(m.productionCountries).containsExactly("US")
        assertThat(m.originCountries).containsExactly("US")
        assertThat(m.aliases).containsExactly("Matrix")
        assertThat(m.info["tagline"]).isEqualTo("Welcome to the Real World.")
        assertThat(m.localize["zh"]?.get("n")).isEqualTo("黑客帝国")
    }

    @Test fun `getTv maps as EPISODE type with season count`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 100088,
                      "name": "The Last of Us",
                      "original_name": "The Last of Us",
                      "first_air_date": "2023-01-15",
                      "overview": "Twenty years after modern civilization has been destroyed...",
                      "number_of_seasons": 2,
                      "number_of_episodes": 16,
                      "episode_run_time": [60],
                      "genres": [{"id": 18, "name": "Drama"}],
                      "created_by": [{"id": 1, "name": "Craig Mazin"}],
                      "origin_country": ["US"],
                      "original_language": "en",
                      "production_countries": [{"iso_3166_1": "US", "name": "United States"}],
                      "spoken_languages": [{"iso_639_1": "en", "name": "English"}],
                      "vote_average": 8.4,
                      "vote_count": 5000,
                      "credits": {"cast": [], "crew": []},
                      "external_ids": {"imdb_id": "tt3581920", "tvdb_id": "390494"},
                      "alternative_titles": {"results": []},
                      "translations": {"translations": []},
                      "content_ratings": {"results": [
                        {"iso_3166_1": "US", "rating": "TV-MA"}
                      ]},
                      "episode_groups": {"results": []}
                    }
                    """.trimIndent(),
                ),
        )

        val m = client.getTv(100088)

        val recorded = server.takeRequest()
        assertThat(recorded.path).startsWith("/tv/100088")
        val appendParam = URLDecoder.decode(
            recorded.path!!.substringAfter("append_to_response=").substringBefore("&"),
            "UTF-8",
        )
        assertThat(appendParam.split(","))
            .containsAtLeast("credits", "external_ids", "alternative_titles", "translations", "content_ratings", "episode_groups")

        assertThat(m.id).isEqualTo(100088)
        assertThat(m.name).isEqualTo("The Last of Us")
        assertThat(m.year).isEqualTo(2023)
        assertThat(m.firstAirDate).isEqualTo("2023-01-15")
        assertThat(m.numberOfSeasons).isEqualTo(2)
        assertThat(m.director).isEqualTo("Craig Mazin")
        assertThat(m.certification).isEqualTo("TV-MA")
        assertThat(m.tvdbId).isEqualTo("390494")
        assertThat(m.imdbId).isEqualTo("tt3581920")
        assertThat(m.genres).containsExactly("Drama")
        assertThat(m.runtime).isEqualTo(60)
    }

    @Test fun `getSeason parses episodes list`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 12345,
                      "season_number": 1,
                      "name": "Season 1",
                      "air_date": "2023-01-15",
                      "overview": "First season.",
                      "episodes": [
                        {"id": 1, "episode_number": 1, "name": "When You're Lost in the Darkness", "air_date": "2023-01-15", "runtime": 81, "vote_average": 8.0},
                        {"id": 2, "episode_number": 2, "name": "Infected", "air_date": "2023-01-22", "runtime": 55, "vote_average": 7.8},
                        {"id": 3, "episode_number": 3, "name": "Long Long Time", "air_date": "2023-01-29", "runtime": 76, "vote_average": 9.0}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val season = client.getSeason(tvId = 100088, seasonNumber = 1)

        val recorded = server.takeRequest()
        assertThat(recorded.path).startsWith("/tv/100088/season/1")

        assertThat(season.id).isEqualTo(12345)
        assertThat(season.seasonNumber).isEqualTo(1)
        assertThat(season.name).isEqualTo("Season 1")
        assertThat(season.airDate).isEqualTo("2023-01-15")
        assertThat(season.episodes).hasSize(3)
        assertThat(season.episodes.map { it.episodeNumber }).containsExactly(1, 2, 3).inOrder()
        assertThat(season.episodes[0].name).isEqualTo("When You're Lost in the Darkness")
        assertThat(season.episodes[0].runtime).isEqualTo(81)
        assertThat(season.episodes[2].voteAverage).isEqualTo(9.0)
    }

    @Test fun `getMovie retries on 429 with Retry-After then succeeds`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("rate limited"),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 603,
                      "title": "The Matrix",
                      "release_date": "1999-03-30"
                    }
                    """.trimIndent(),
                ),
        )

        val m = client.getMovie(603)

        // 验证服务器收到 2 个请求（第一次 429，第二次 200）
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(m.id).isEqualTo(603)
        assertThat(m.name).isEqualTo("The Matrix")
        assertThat(m.year).isEqualTo(1999)
    }

    @Test fun `enrichWithCollection fetches collection and fills collectionIndex`() = runBlocking {
        // enrichWithCollection 会先拉 movie 再拉 collection
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 604,
                      "title": "The Matrix Reloaded",
                      "release_date": "2003-05-15",
                      "belongs_to_collection": {"id": 2344, "name": "The Matrix Collection"},
                      "credits": {"cast": [], "crew": []},
                      "external_ids": {},
                      "alternative_titles": {"results": []},
                      "translations": {"translations": []},
                      "release_dates": {"results": []}
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 2344,
                      "name": "The Matrix Collection",
                      "parts": [
                        {"id": 603, "title": "The Matrix", "release_date": "1999-03-30"},
                        {"id": 604, "title": "The Matrix Reloaded", "release_date": "2003-05-15"},
                        {"id": 605, "title": "The Matrix Revolutions", "release_date": "2003-11-05"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val base = MediaMetadata(
            id = 604,
            collectionId = 2344,
            collectionName = "The Matrix Collection",
        )
        val enriched = client.enrichWithCollection(base)

        // 验证两个请求都发出
        assertThat(server.requestCount).isEqualTo(2)
        val firstReq = server.takeRequest()
        val secondReq = server.takeRequest()
        assertThat(firstReq.path).startsWith("/movie/604")
        assertThat(secondReq.path).startsWith("/collection/2344")

        assertThat(enriched.collectionName).isEqualTo("The Matrix Collection")
        assertThat(enriched.collectionId).isEqualTo(2344)
        // 排序后：1999(603), 2003-05(604), 2003-11(605) → 604 是第 2 位
        assertThat(enriched.collectionIndex).isEqualTo(2)
        assertThat(enriched.collectionYears).containsExactly(1999, 2003).inOrder()
        assertThat(enriched.name).isEqualTo("The Matrix Reloaded")
        assertThat(enriched.year).isEqualTo(2003)
    }

    @Test fun `enrichWithCollection returns input unchanged when collectionId is null`() = runBlocking {
        val base = MediaMetadata(id = 604, collectionId = null)
        val result = client.enrichWithCollection(base)
        assertThat(result).isEqualTo(base)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
