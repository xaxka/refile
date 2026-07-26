package xa.refile.core.naming

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

class TemplateEngineTest {

    private fun engine(
        media: MediaMetadata = MediaMetadata(),
        file: FileContext = FileContext(),
        batch: BatchContext = BatchContext(),
        options: NamingOptions = NamingOptions(),
    ) = TemplateEngine(BindingResolver(media, file, batch, options), options)

    // ---- A 组：匹配对象与通用绑定 ----

    @Test fun `variable n movie name`() {
        val r = engine(media = MediaMetadata(name = "The Last of Us")).render("{n}")
        assertThat(r.path).isEqualTo("The Last of Us")
    }

    @Test fun `variable y year`() {
        val r = engine(media = MediaMetadata(year = 2023)).render("{y}")
        assertThat(r.path).isEqualTo("2023")
    }

    @Test fun `variable ny name year combo`() {
        val r = engine(media = MediaMetadata(name = "The Last of Us", year = 2023)).render("{ny}")
        assertThat(r.path).isEqualTo("The Last of Us (2023)")
    }

    @Test fun `variable id and tmdbid`() {
        val r = engine(media = MediaMetadata(tmdbId = 100088)).render("{id}")
        assertThat(r.path).isEqualTo("100088")
    }

    @Test fun `variable imdbid`() {
        val r = engine(media = MediaMetadata(imdbId = "tt3581920")).render("{imdbid}")
        assertThat(r.path).isEqualTo("tt3581920")
    }

    @Test fun `variable tvdbid only for episode`() {
        val ep = engine(media = MediaMetadata(type = MediaType.EPISODE, tvdbId = "392256")).render("{tvdbid}")
        assertThat(ep.path).isEqualTo("392256")
        val movie = engine(media = MediaMetadata(type = MediaType.MOVIE, tvdbId = "392256")).render("{tvdbid}")
        assertThat(movie.path).isEmpty()
    }

    @Test fun `variable primaryTitle`() {
        val r = engine(media = MediaMetadata(originalName = "Juuni Kokuki")).render("{primaryTitle}")
        assertThat(r.path).isEqualTo("Juuni Kokuki")
    }

    @Test fun `variable type`() {
        assertThat(engine(media = MediaMetadata(type = MediaType.MOVIE)).render("{type}").path).isEqualTo("Movie")
        assertThat(engine(media = MediaMetadata(type = MediaType.EPISODE)).render("{type}").path).isEqualTo("Episode")
    }

    // ---- B 组：剧集绑定 ----

    @Test fun `variable s season`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, seasonNumber = 3)).render("{s}")
        assertThat(r.path).isEqualTo("3")
    }

    @Test fun `variable e episode`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeNumbers = listOf(1))).render("{e}")
        assertThat(r.path).isEqualTo("1")
    }

    @Test fun `variable s00e00 padded`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, seasonNumber = 1, episodeNumbers = listOf(1)))
            .render("{s00e00}")
        assertThat(r.path).isEqualTo("S01E01")
    }

    @Test fun `variable sxe`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, seasonNumber = 1, episodeNumbers = listOf(1)))
            .render("{sxe}")
        assertThat(r.path).isEqualTo("1x01")
    }

    @Test fun `variable t episode title merged`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeTitles = listOf("Labyrinth", "Echo")))
            .render("{t}")
        assertThat(r.path).isEqualTo("Labyrinth & Echo")
    }

    @Test fun `variable d airdate`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-29")))
            .render("{d}")
        assertThat(r.path).isEqualTo("2023-01-29")
    }

    @Test fun `variable airdate alias`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-29")))
            .render("{airdate}")
        assertThat(r.path).isEqualTo("2023-01-29")
    }

    @Test fun `variable absolute episode`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, seasonNumber = 2, episodeNumbers = listOf(1),
            seasonAbsoluteStarts = listOf(10, 10),
        )).render("{absolute}")
        assertThat(r.path).isEqualTo("11")
    }

    @Test fun `variable sc number of seasons`() {
        val r = engine(media = MediaMetadata(numberOfSeasons = 5)).render("{sc}")
        assertThat(r.path).isEqualTo("5")
    }

    @Test fun `variable anime flag`() {
        val anime = engine(media = MediaMetadata(
            type = MediaType.EPISODE, originCountries = listOf("JP"), genres = listOf("Animation"),
        )).render("{anime}")
        assertThat(anime.path).isEqualTo("true")
    }

    // ---- C 组：影视元数据 ----

    @Test fun `variable collection`() {
        val r = engine(media = MediaMetadata(collectionName = "Avatar Collection")).render("{collection}")
        assertThat(r.path).isEqualTo("Avatar Collection")
    }

    @Test fun `variable ci collection index`() {
        val r = engine(media = MediaMetadata(collectionIndex = 1)).render("{ci}")
        assertThat(r.path).isEqualTo("1")
    }

    @Test fun `variable decade`() {
        val r = engine(media = MediaMetadata(year = 1975)).render("{decade}")
        assertThat(r.path).isEqualTo("1970")
    }

    @Test fun `variable genre first`() {
        val r = engine(media = MediaMetadata(genres = listOf("Science Fiction", "Drama"))).render("{genre}")
        assertThat(r.path).isEqualTo("Science Fiction")
    }

    @Test fun `variable genres list`() {
        val r = engine(media = MediaMetadata(genres = listOf("Sci-Fi", "Drama"))).render("{genres}")
        assertThat(r.path).isEqualTo("Sci-Fi, Drama")
    }

    @Test fun `variable certification`() {
        val r = engine(media = MediaMetadata(certification = "PG-13")).render("{certification}")
        assertThat(r.path).isEqualTo("PG-13")
    }

    @Test fun `variable rating`() {
        val r = engine(media = MediaMetadata(rating = 7.4)).render("{rating}")
        assertThat(r.path).isEqualTo("7.4")
    }

    @Test fun `variable director`() {
        val r = engine(media = MediaMetadata(director = "James Cameron")).render("{director}")
        assertThat(r.path).isEqualTo("James Cameron")
    }

    @Test fun `variable actors list`() {
        val r = engine(media = MediaMetadata(actors = listOf("Zoe Saldana", "Sam Worthington"))).render("{actors}")
        assertThat(r.path).isEqualTo("Zoe Saldana, Sam Worthington")
    }

    // ---- D 组：批次与序号 ----

    @Test fun `variable pi part index`() {
        val r = engine(batch = BatchContext(partIndex = 1)).render("{pi}")
        assertThat(r.path).isEqualTo("1")
    }

    @Test fun `variable az sort letter`() {
        val r = engine(media = MediaMetadata(name = "The Matrix")).render("{az}")
        assertThat(r.path).isEqualTo("M")
    }

    // ---- E 组：文件与路径 ----

    @Test fun `variable fn display name`() {
        val r = engine(file = FileContext(displayName = "Serenity")).render("{fn}")
        assertThat(r.path).isEqualTo("Serenity")
    }

    @Test fun `variable f full path`() {
        val r = engine(file = FileContext(fullPath = "/library/a.mkv")).render("{f}")
        assertThat(r.path).isEqualTo("/library/a.mkv")
    }

    @Test fun `variable bytes human readable`() {
        val r = engine(file = FileContext(contentLength = 373_293_056L)).render("{bytes}")
        assertThat(r.path).isEqualTo("356 MB")
    }

    @Test fun `variable today`() {
        val r = engine(batch = BatchContext(today = "2026-07-23")).render("{today}")
        assertThat(r.path).isEqualTo("2026-07-23")
    }

    // ---- F 组：技术标签（来源文件名） ----

    @Test fun `variable vf from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(resolution = "1080p"))).render("{vf}")
        assertThat(r.path).isEqualTo("1080p")
    }

    @Test fun `variable vc from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(videoCodec = "x264"))).render("{vc}")
        assertThat(r.path).isEqualTo("x264")
    }

    @Test fun `variable group from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(group = "ALLiANCE"))).render("{group}")
        assertThat(r.path).isEqualTo("ALLiANCE")
    }

    // ---- G 组：高级上下文 ----

    @Test fun `variable info tagline`() {
        val r = engine(media = MediaMetadata(info = mapOf("tagline" to "Survive"))).render("{info.tagline}")
        assertThat(r.path).isEqualTo("Survive")
    }

    @Test fun `variable localize ja n`() {
        val r = engine(media = MediaMetadata(localize = mapOf("ja" to mapOf("n" to "十二国記")))).render("{localize.ja.n}")
        assertThat(r.path).isEqualTo("十二国記")
    }

    // ---- 管道修饰符 ----

    @Test fun `pipe upper`() {
        val r = engine(media = MediaMetadata(name = "Firefly")).render("{n|upper}")
        assertThat(r.path).isEqualTo("FIREFLY")
    }

    @Test fun `pipe chained lower space`() {
        val r = engine(media = MediaMetadata(name = "Deep Space")).render("{n|lower|space(_)}")
        assertThat(r.path).isEqualTo("deep_space")
    }

    @Test fun `pipe pad`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeNumbers = listOf(5))).render("{e|pad(3)}")
        assertThat(r.path).isEqualTo("005")
    }

    @Test fun `pipe joining list`() {
        val r = engine(media = MediaMetadata(genres = listOf("Sci-Fi", "Drama"))).render("{genres|joining(-)}")
        assertThat(r.path).isEqualTo("Sci-Fi-Drama")
    }

    @Test fun `pipe sortName`() {
        val r = engine(media = MediaMetadata(name = "The Walking Dead")).render("{n|sortName}")
        assertThat(r.path).isEqualTo("Walking Dead, The")
    }

    @Test fun `pipe roman`() {
        val r = engine(media = MediaMetadata(collectionIndex = 4)).render("{ci|roman}")
        assertThat(r.path).isEqualTo("IV")
    }

    @Test fun `pipe acronym`() {
        val r = engine(media = MediaMetadata(name = "Deep Space 9")).render("{n|acronym}")
        assertThat(r.path).isEqualTo("DS9")
    }

    // ---- 容错：缺失变量不输出 undefined ----

    @Test fun `missing variable renders empty and cleans separators`() {
        val r = engine(media = MediaMetadata(name = "Matrix")).render("{n}.{y}")
        assertThat(r.path).isEqualTo("Matrix")
        assertThat(r.path).doesNotContain("undefined")
    }

    @Test fun `missing variable in parens omitted`() {
        val r = engine(media = MediaMetadata(name = "Matrix")).render("{n} ({y})")
        assertThat(r.path).isEqualTo("Matrix")
    }

    @Test fun `excluded binding renders empty with warning`() {
        val engine = engine()
        val r = engine.render("{mediaTitle}")
        assertThat(r.path).isEmpty()
        assertThat(r.warnings).isNotEmpty()
    }

    @Test fun `unknown variable does not crash`() {
        val r = engine(media = MediaMetadata(name = "Matrix")).render("{n}{totallyUnknown}")
        assertThat(r.path).isEqualTo("Matrix")
    }

    // ---- 路径分隔 ----

    @Test fun `path separator creates directories`() {
        val r = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("Movies/{n} ({y})/{n} ({y})")
        assertThat(r.path).isEqualTo("Movies/Avatar (2009)/Avatar (2009)")
    }

    // ---- 内置预设 ----

    @Test fun `default preset episode template`() {
        val repo = PresetRepository()
        val template = repo.templateFor(Preset.DEFAULT, isEpisode = true)
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, name = "Firefly", year = 2002,
            seasonNumber = 1, episodeNumbers = listOf(1), episodeTitles = listOf("Serenity"),
        )).render(template)
        assertThat(r.path).isEqualTo("TV Shows/Firefly/Season 01/Firefly - S01E01 - Serenity")
    }

    @Test fun `default preset movie template`() {
        val repo = PresetRepository()
        val template = repo.templateFor(Preset.DEFAULT, isEpisode = false)
        val r = engine(media = MediaMetadata(name = "Avatar", year = 2009)).render(template)
        assertThat(r.path).isEqualTo("Movies/Avatar (2009)/Avatar (2009)")
    }

    // ---- 全局可视化选项 ----

    @Test fun `illegal char replaced by dash`() {
        val r = engine(
            media = MediaMetadata(name = "A:B"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_DASH),
        ).render("{n}")
        assertThat(r.path).isEqualTo("A-B")
    }

    @Test fun `word separator underscore`() {
        val r = engine(
            media = MediaMetadata(name = "Deep Space"),
            options = NamingOptions(wordSeparator = '_'),
        ).render("{n}")
        assertThat(r.path).isEqualTo("Deep_Space")
    }

    @Test fun `casing upper`() {
        val r = engine(
            media = MediaMetadata(name = "matrix"),
            options = NamingOptions(casing = NamingOptions.Casing.UPPER),
        ).render("{n}")
        assertThat(r.path).isEqualTo("MATRIX")
    }

    // ---- .method() 链式语法 ----

    @Test fun `dot chain clean removes illegal chars`() {
        val r = engine(media = MediaMetadata(name = "Show: Title")).render("{n.clean()}")
        assertThat(r.path).isEqualTo("Show Title")
    }

    @Test fun `dot chain upper`() {
        val r = engine(media = MediaMetadata(name = "Firefly")).render("{n.upper()}")
        assertThat(r.path).isEqualTo("FIREFLY")
    }

    @Test fun `dot chain lower`() {
        val r = engine(media = MediaMetadata(name = "MATRIX")).render("{n.lower()}")
        assertThat(r.path).isEqualTo("matrix")
    }

    @Test fun `dot chain clean then space empty arg deletes spaces`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, episodeTitles = listOf("Labyrinth & Echo")))
            .render("{t.clean().space('')}")
        assertThat(r.path).isEqualTo("Labyrinth&Echo")
    }

    @Test fun `dot chain space dot replaces spaces`() {
        val r = engine(media = MediaMetadata(name = "Deep Space")).render("{n.space('.')}")
        assertThat(r.path).isEqualTo("Deep.Space")
    }

    @Test fun `dot chain upperInitial then space dot`() {
        val r = engine(media = MediaMetadata(name = "the matrix"))
            .render("{n.upperInitial().space('.')}")
        assertThat(r.path).isEqualTo("The.matrix")
    }

    @Test fun `pipe clean still works for compatibility`() {
        val r = engine(media = MediaMetadata(name = "Show: Title")).render("{n|clean}")
        assertThat(r.path).isEqualTo("Show Title")
    }

    @Test fun `mixed dot chain and pipe both apply`() {
        val r = engine(media = MediaMetadata(name = "Firefly: Serenity"))
            .render("{n.clean()|upper}")
        assertThat(r.path).isEqualTo("FIREFLY SERENITY")
    }

    // ---- 端到端模板（用户给定的关键模板） ----

    @Test fun `movie template n clean with year`() {
        val r = engine(media = MediaMetadata(name = "Inception", year = 2010))
            .render("{n.clean()} ({y})")
        assertThat(r.path).isEqualTo("Inception (2010)")
    }

    @Test fun `movie template n clean strips colon`() {
        val r = engine(media = MediaMetadata(name = "Show: Title", year = 2010))
            .render("{n.clean()} ({y})")
        assertThat(r.path).isEqualTo("Show Title (2010)")
    }

    @Test fun `episode template with clean space empty arg`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE,
            name = "The Last of Us",
            year = 2023,
            seasonNumber = 1,
            episodeNumbers = listOf(2),
            episodeTitles = listOf("Labyrinth & Echo"),
        )).render("{n}({y}){s00e00}—{t.clean().space('')}")
        assertThat(r.path).isEqualTo("The Last of Us(2023)S01E02—Labyrinth&Echo")
    }

    // ---- 新增函数 ----

    @Test fun `replaceTrailingBrackets removes trailing parens`() {
        val r = engine(media = MediaMetadata(name = "Show (US)"))
            .render("{n.replaceTrailingBrackets()}")
        assertThat(r.path).isEqualTo("Show")
    }

    @Test fun `tail takes last n chars`() {
        val r = engine(media = MediaMetadata(name = "Inception")).render("{n.tail(3)}")
        assertThat(r.path).isEqualTo("ion")
    }

    // ---- 参数引号 ----

    @Test fun `replaceAll single quoted args parse correctly`() {
        val r = engine(media = MediaMetadata(name = "Deep Space"))
            .render("{n.replaceAll(' ', '.')}")
        assertThat(r.path).isEqualTo("Deep.Space")
    }

    @Test fun `colon single quoted arg parses correctly`() {
        val r = engine(media = MediaMetadata(name = "A:B")).render("{n.colon('-')}")
        assertThat(r.path).isEqualTo("A-B")
    }
}
