package xa.refile.core.naming

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename
import xa.refile.core.parser.SubtitleInfo
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

    // ---- Task 8.3：反引号与控制字符非法字符集补齐用例 ----

    @Test fun `backtick replaced by dash when illegal char handling is dash`() {
        val r = engine(
            media = MediaMetadata(name = "A`B"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_DASH),
        ).render("{n}")
        assertThat(r.path).isEqualTo("A-B")
    }

    @Test fun `backtick replaced by underscore when illegal char handling is underscore`() {
        val r = engine(
            media = MediaMetadata(name = "A`B"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_UNDERSCORE),
        ).render("{n}")
        assertThat(r.path).isEqualTo("A_B")
    }

    @Test fun `backtick removed when illegal char handling is remove`() {
        val r = engine(
            media = MediaMetadata(name = "A`B"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REMOVE),
        ).render("{n}")
        assertThat(r.path).isEqualTo("AB")
    }

    @Test fun `control char newline replaced by dash`() {
        val r = engine(
            media = MediaMetadata(name = "A\nB"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_DASH),
        ).render("{n}")
        assertThat(r.path).isEqualTo("A-B")
    }

    @Test fun `control char tab replaced by dash`() {
        val r = engine(
            media = MediaMetadata(name = "A\tB"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_DASH),
        ).render("{n}")
        assertThat(r.path).isEqualTo("A-B")
    }

    @Test fun `existing illegal char set still works`() {
        // 验证 Task 8.2 之前就覆盖的 8 个字符仍然被替换：/ \ : * ? " < > |
        // 注意：路径分隔符 / 不在 applyGlobalOptions 的正则内（render 已按 / 切段），
        // 反斜杠 \ 在正则内会被替换。为避免路径分隔符歧义，此处仅断言
        // : * ? " < > | 这 7 个字符在最终路径中不存在。
        val r = engine(
            media = MediaMetadata(name = "A:B*C?D\"E<F>G|H"),
            options = NamingOptions(illegalCharHandling = NamingOptions.IllegalCharHandling.REPLACE_DASH),
        ).render("{n}")
        assertThat(r.path).doesNotContain(":")
        assertThat(r.path).doesNotContain("*")
        assertThat(r.path).doesNotContain("?")
        assertThat(r.path).doesNotContain("\"")
        assertThat(r.path).doesNotContain("<")
        assertThat(r.path).doesNotContain(">")
        assertThat(r.path).doesNotContain("|")
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
        // FileBot 语义：逐词首字母大写
        val r = engine(media = MediaMetadata(name = "the matrix"))
            .render("{n.upperInitial().space('.')}")
        assertThat(r.path).isEqualTo("The.Matrix")
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

    @Test fun `replacePart converts trailing part number`() {
        // FileBot："Today Is the Day (1)" → "Today Is the Day, Part 1"
        val r = engine(media = MediaMetadata(name = "Today Is the Day (1)"))
            .render("{n.replacePart()}")
        assertThat(r.path).isEqualTo("Today Is the Day, Part 1")
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

    // ---- 日期格式化修饰符（FileBot format）----

    @Test fun `format default pattern yyyy-MM-dd`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-15")))
            .render("{airdate|format}")
        assertThat(r.path).isEqualTo("2023-01-15")
    }

    @Test fun `format custom pattern dots`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-15")))
            .render("{airdate|format(yyyy.MM.dd)}")
        assertThat(r.path).isEqualTo("2023.01.15")
    }

    @Test fun `format custom pattern slashes`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-15")))
            .render("{airdate|format(dd/MM/yyyy)}")
        assertThat(r.path).isEqualTo("15/01/2023")
    }

    @Test fun `format handles ISO datetime input`() {
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("2023-01-15T10:30:00Z")))
            .render("{airdate|format(yyyy.MM.dd)}")
        assertThat(r.path).isEqualTo("2023.01.15")
    }

    @Test fun `format invalid date returns original`() {
        // 无法解析的日期 → 原样返回（容错，不崩溃）
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE, episodeAirDates = listOf("not-a-date")))
            .render("{airdate|format(yyyy.MM.dd)}")
        assertThat(r.path).isEqualTo("not-a-date")
    }

    @Test fun `format missing date renders empty`() {
        // 无日期数据 → 变量解析为 null → 渲染为空
        val r = engine(media = MediaMetadata(type = MediaType.EPISODE))
            .render("{airdate|format(yyyy.MM.dd)}")
        assertThat(r.path).isEmpty()
    }

    @Test fun `parseDate with custom pattern outputs ISO`() {
        val r = engine(media = MediaMetadata(name = "20230115"))
            .render("{n|parseDate('yyyyMMdd')}")
        assertThat(r.path).isEqualTo("2023-01-15")
    }

    // ---- 排序首字母修饰符（FileBot sortInitial）----

    @Test fun `sortInitial returns uppercase first letter`() {
        val r = engine(media = MediaMetadata(name = "Avatar")).render("{n|sortInitial}")
        assertThat(r.path).isEqualTo("A")
    }

    @Test fun `sortInitial strips leading article`() {
        // The Avatar → 排序名 Avatar, The → 首字母 A
        val r = engine(media = MediaMetadata(name = "The Avatar")).render("{n|sortInitial}")
        assertThat(r.path).isEqualTo("A")
    }

    @Test fun `sortInitial used as directory segment`() {
        // 经典用法：{n|sortInitial}/{n} ({y})/{n} ({y})
        val r = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{n|sortInitial}/{n} ({y})/{n} ({y})")
        assertThat(r.path).isEqualTo("A/Avatar (2009)/Avatar (2009)")
    }

    @Test fun `sortInitial handles number start`() {
        // 数字开头 → 原样返回首字符
        val r = engine(media = MediaMetadata(name = "12 Monkeys")).render("{n|sortInitial}")
        assertThat(r.path).isEqualTo("1")
    }

    // ---- Feature #9：条件块 ----

    @Test fun `conditional block renders when condition truthy`() {
        // y 存在 → 渲染 ({y})
        val r = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{?y}({y}){/?}")
        assertThat(r.path).isEqualTo("(2009)")
    }

    @Test fun `conditional block omits when condition falsy`() {
        // y 缺失 → 整段省略
        val r = engine(media = MediaMetadata(name = "Avatar"))
            .render("{?y}({y}){/?}")
        assertThat(r.path).isEmpty()
    }

    @Test fun `conditional block with else branch`() {
        // y 存在 → 渲染 yes 分支
        val r1 = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{?y}({y}){:}No Year{/?}")
        assertThat(r1.path).isEqualTo("(2009)")

        // y 缺失 → 渲染 no 分支
        val r2 = engine(media = MediaMetadata(name = "Avatar"))
            .render("{?y}({y}){:}No Year{/?}")
        assertThat(r2.path).isEqualTo("No Year")
    }

    @Test fun `conditional block negation`() {
        // {!y} → y 缺失时为真
        val r1 = engine(media = MediaMetadata(name = "Avatar"))
            .render("{?!y}No Year{/?}")
        assertThat(r1.path).isEqualTo("No Year")

        // y 存在 → {!y} 为假 → 省略
        val r2 = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{?!y}No Year{/?}")
        assertThat(r2.path).isEmpty()
    }

    @Test fun `conditional block nested`() {
        // 嵌套条件块：外层 y 为真，内层 s 为假
        val r = engine(media = MediaMetadata(name = "Lost", year = 2004))
            .render("{?y}{?s}S{s}E{e}{/?}{/?}")
        assertThat(r.path).isEmpty() // s 缺失 → 内层省略 → 外层渲染为空

        // 嵌套条件块：外层 y 为真，内层 s 为真
        val r2 = engine(media = MediaMetadata(
            type = MediaType.EPISODE, name = "Lost", year = 2004,
            seasonNumber = 1, episodeNumbers = listOf(1),
        )).render("{?y}{?s}S{s}E{e}{/?}{/?}")
        assertThat(r2.path).isEqualTo("S1E1")
    }

    @Test fun `conditional block in full template`() {
        // 实际模板：有年份加括号，无年份不加
        val withYear = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{n}{?y} ({y}){/?}")
        assertThat(withYear.path).isEqualTo("Avatar (2009)")

        val withoutYear = engine(media = MediaMetadata(name = "Avatar"))
            .render("{n}{?y} ({y}){/?}")
        assertThat(withoutYear.path).isEqualTo("Avatar")
    }

    @Test fun `conditional block old syntax with trailing question mark still works`() {
        // 向后兼容：{?y?} 旧语法（带尾 ?）
        val r = engine(media = MediaMetadata(name = "Avatar", year = 2009))
            .render("{?y?}({y}){/?}")
        assertThat(r.path).isEqualTo("(2009)")
    }

    // ---- Feature #21：编译缓存 ----

    @Test fun `compile cache produces same result on repeated render`() {
        // 同一模板多次渲染，结果一致（缓存不改变行为）
        // 注：分隔符用 - 而非 |（| 是非法文件名字符，会被默认 NamingOptions 替换）
        val e = engine(media = MediaMetadata(name = "Avatar", year = 2009))
        val template = "{n.clean()}-{y}-{n|upper}"
        val r1 = e.render(template)
        val r2 = e.render(template)
        assertThat(r1.path).isEqualTo(r2.path)
        assertThat(r1.path).isEqualTo("Avatar-2009-AVATAR")
    }

    @Test fun `compile cache handles different templates`() {
        val e = engine(media = MediaMetadata(name = "Avatar", year = 2009))
        val r1 = e.render("{n|upper}")
        val r2 = e.render("{n|lower}")
        assertThat(r1.path).isEqualTo("AVATAR")
        assertThat(r2.path).isEqualTo("avatar")
    }

    // ---- FileBot 对齐：文件名不含扩展名 ----

    @Test fun `fn strips extension`() {
        val r = engine(file = FileContext(displayName = "Serenity.mkv", ext = "mkv")).render("{fn}")
        assertThat(r.path).isEqualTo("Serenity")
    }

    @Test fun `original and mediaFileName strip extension`() {
        val f = FileContext(displayName = "Inception.2010.1080p.mkv", ext = "mkv")
        assertThat(engine(file = f).render("{original}").path).isEqualTo("Inception.2010.1080p")
        assertThat(engine(file = f).render("{mediaFileName}").path).isEqualTo("Inception.2010.1080p")
    }

    // ---- FileBot 对齐：d 回退到电影上映日期 ----

    @Test fun `d falls back to movie release date`() {
        val r = engine(media = MediaMetadata(type = MediaType.MOVIE, releaseDate = "2010-07-16")).render("{d}")
        assertThat(r.path).isEqualTo("2010-07-16")
    }

    // ---- FileBot 对齐：文件名解析类绑定 ----

    @Test fun `edition from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(edition = "Director's Cut"))).render("{edition}")
        assertThat(r.path).isEqualTo("Director's Cut")
    }

    @Test fun `tags from edition and version`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(edition = "Director's Cut", version = "PROPER")))
            .render("{tags}")
        assertThat(r.path).isEqualTo("Director's Cut, PROPER")
    }

    @Test fun `s3d from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(threeD = "3D SBS"))).render("{s3d}")
        assertThat(r.path).isEqualTo("3D SBS")
    }

    @Test fun `hdr from filename`() {
        val r = engine(file = FileContext(parsed = ParsedFilename(hdr = "HDR10"))).render("{hdr}")
        assertThat(r.path).isEqualTo("HDR10")
    }

    @Test fun `dovi splits from hdr`() {
        val f = FileContext(parsed = ParsedFilename(hdr = "Dolby Vision"))
        assertThat(engine(file = f).render("{dovi}").path).isEqualTo("Dolby Vision")
        assertThat(engine(file = f).render("{hdr}").path).isEmpty()
    }

    @Test fun `lang and subt from subtitle filename`() {
        val f = FileContext(
            displayName = "movie.srt",
            ext = "srt",
            parsed = ParsedFilename(subtitleInfo = SubtitleInfo("zh", forced = true, default = false, hearingImpaired = false)),
        )
        assertThat(engine(file = f).render("{lang}").path).isEqualTo("zh")
        assertThat(engine(file = f).render("{fn}{subt}").path).isEqualTo("movie.zh.forced")
    }

    // ---- FileBot 对齐：批次与路径别名 ----

    @Test fun `i model index`() {
        val r = engine(batch = BatchContext(index = 0)).render("{i}")
        assertThat(r.path).isEqualTo("0")
    }

    @Test fun `root aliases drive`() {
        val r = engine(file = FileContext(drive = "library")).render("{root}")
        assertThat(r.path).isEqualTo("library")
    }

    @Test fun `episodes aliases episodelist`() {
        val m = MediaMetadata(type = MediaType.EPISODE, seasonNumber = 1, episodeNumbers = listOf(1, 2))
        assertThat(engine(media = m).render("{episodes}").path).isEqualTo("[1x01, 1x02]")
    }

    // ---- FileBot 对齐：媒体服务器标准路径 ----

    @Test fun `plex movie path`() {
        val r = engine(media = MediaMetadata(type = MediaType.MOVIE, name = "Avatar", year = 2009)).render("{plex}")
        assertThat(r.path).isEqualTo("Movies/Avatar (2009)/Avatar (2009)")
    }

    @Test fun `plex episode path`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, name = "Alias", year = 2001,
            seasonNumber = 1, episodeNumbers = listOf(1), episodeTitles = listOf("Truth Be Told"),
        )).render("{plex}")
        assertThat(r.path).isEqualTo("TV Shows/Alias/Season 01/Alias - S01E01 - Truth Be Told")
    }

    @Test fun `kodi episode path uses year and sxe`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, name = "Alias", year = 2001,
            seasonNumber = 1, episodeNumbers = listOf(1), episodeTitles = listOf("Truth Be Told"),
        )).render("{kodi}")
        assertThat(r.path).isEqualTo("TV Shows/Alias (2001)/Season 1/Alias (2001) - 1x01 - Truth Be Told")
    }

    @Test fun `emby and jellyfin episode paths`() {
        val m = MediaMetadata(
            type = MediaType.EPISODE, name = "Alias", year = 2001,
            seasonNumber = 1, episodeNumbers = listOf(1), episodeTitles = listOf("Truth Be Told"),
        )
        val expected = "TV Shows/Alias (2001)/Season 01/Alias (2001) - S01E01 - Truth Be Told"
        assertThat(engine(media = m).render("{emby}").path).isEqualTo(expected)
        assertThat(engine(media = m).render("{jellyfin}").path).isEqualTo(expected)
    }

    @Test fun `plex season zero uses Specials folder`() {
        val r = engine(media = MediaMetadata(
            type = MediaType.EPISODE, name = "Alias", year = 2001,
            seasonNumber = 0, episodeNumbers = listOf(1), episodeTitles = listOf("The Legend of Rambaldi"),
        )).render("{plex}")
        assertThat(r.path).isEqualTo("TV Shows/Alias/Specials/Alias - S00E01 - The Legend of Rambaldi")
    }

    // ---- FileBot 对齐：修饰符语义修正 ----

    @Test fun `upperInitial capitalizes every word`() {
        val r = engine(media = MediaMetadata(name = "The Day a new Demon was born")).render("{n|upperInitial}")
        assertThat(r.path).isEqualTo("The Day A New Demon Was Born")
    }

    @Test fun `lowerTrail titlecases trailing uppercase word`() {
        val r = engine(media = MediaMetadata(name = "Gundam SEED")).render("{n|lowerTrail}")
        assertThat(r.path).isEqualTo("Gundam Seed")
    }

    @Test fun `initialName reduces first name only`() {
        val r = engine(media = MediaMetadata(director = "James Cameron")).render("{director|initialName}")
        assertThat(r.path).isEqualTo("J. Cameron")
    }

    @Test fun `roman replaces small numbers in string`() {
        val r = engine(media = MediaMetadata(name = "Star Wars Episode 4")).render("{n|roman}")
        assertThat(r.path).isEqualTo("Star Wars Episode IV")
    }

    @Test fun `upper with pattern only transforms match`() {
        val r = engine(media = MediaMetadata(name = "the matrix")).render("{n|upper('matrix')}")
        assertThat(r.path).isEqualTo("the MATRIX")
    }

    @Test fun `colon with space gets spaced dash`() {
        val r = engine(media = MediaMetadata(name = "Sissi: The Young Empress")).render("{n.colon('-')}")
        assertThat(r.path).isEqualTo("Sissi - The Young Empress")
    }

    @Test fun `pad pads each number in string`() {
        val r = engine(media = MediaMetadata(name = "1x1")).render("{n|pad(2,3)}")
        assertThat(r.path).isEqualTo("01x001")
    }

    @Test fun `round zero precision prints integer`() {
        val r = engine(media = MediaMetadata(rating = 3.14)).render("{rating|round(0)}")
        assertThat(r.path).isEqualTo("3")
    }

    @Test fun `match is case insensitive`() {
        val r = engine(media = MediaMetadata(name = "Firefly 2009")).render("{n|match('firefly')}")
        assertThat(r.path).isEqualTo("Firefly")
    }

    @Test fun `match with group index`() {
        val r = engine(media = MediaMetadata(name = "Firefly 2009")).render("{n|match('(\\d{4})', 1)}")
        assertThat(r.path).isEqualTo("2009")
    }

    @Test fun `matchBrackets returns bracket contents`() {
        val r = engine(media = MediaMetadata(name = "Show (US) [2020]")).render("{n|matchBrackets}")
        assertThat(r.path).isEqualTo("US, 2020")
    }

    @Test fun `removeBrackets strips bracket groups`() {
        val r = engine(media = MediaMetadata(name = "Show (US) [2020]")).render("{n|removeBrackets}")
        assertThat(r.path).isEqualTo("Show")
    }

    @Test fun `remove strips given characters`() {
        val r = engine(media = MediaMetadata(name = "A.B.C")).render("{n|remove('.')}")
        assertThat(r.path).isEqualTo("ABC")
    }

    @Test fun `removeIllegalCharacters strips windows illegal chars`() {
        val r = engine(media = MediaMetadata(name = "A?B")).render("{n|removeIllegalCharacters}")
        assertThat(r.path).isEqualTo("AB")
    }

    @Test fun `replaceIllegalCharacters uses unicode lookalikes`() {
        val r = engine(media = MediaMetadata(name = "A?B")).render("{n|replaceIllegalCharacters}")
        assertThat(r.path).isEqualTo("A？B")
    }

    @Test fun `ascii folds diacritics`() {
        val r = engine(media = MediaMetadata(name = "Café")).render("{n|ascii}")
        assertThat(r.path).isEqualTo("Cafe")
    }

    @Test fun `validateFileName strips leading dot`() {
        val r = engine(media = MediaMetadata(name = ".hack")).render("{n|validateFileName}")
        assertThat(r.path).isEqualTo("hack")
    }

    @Test fun `joiningDistinct dedupes`() {
        val r = engine(media = MediaMetadata(genres = listOf("Sci-Fi", "Drama", "Sci-Fi")))
            .render("{genres|joiningDistinct(',')}")
        assertThat(r.path).isEqualTo("Sci-Fi,Drama")
    }

    @Test fun `bounds takes first and last`() {
        val r = engine(media = MediaMetadata(seasonYears = listOf(2002, 2003, 2004)))
            .render("{sy|bounds|joining('-')}")
        assertThat(r.path).isEqualTo("2002-2004")
    }

    @Test fun `truncate hard cuts at limit`() {
        val r = engine(media = MediaMetadata(name = "The Quick Brown Fox")).render("{n|truncate(10)}")
        assertThat(r.path).isEqualTo("The Quick")
    }

    @Test fun `isLatin detects script`() {
        assertThat(engine(media = MediaMetadata(name = "Firefly")).render("{n|isLatin}").path).isEqualTo("true")
        assertThat(engine(media = MediaMetadata(name = "权力的游戏")).render("{n|isLatin}").path).isEqualTo("false")
    }

    @Test fun `toDate then format renders year`() {
        // 1672617600 = 2023-01-02T00:00:00Z（任意时区年份均为 2023）
        val r = engine(media = MediaMetadata(name = "1672617600")).render("{n|toDate|format(yyyy)}")
        assertThat(r.path).isEqualTo("2023")
    }
}
