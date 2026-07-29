package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.FilenameParser
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

/**
 * 匹配系统端到端代码覆盖测试（FilenameParser → MatchEngine）。
 *
 * 数据来源：联网采集的真实影视资源文件名命名惯例（scene release 规范
 * `Title.Year.Resolution.Source.Codec-Audio-Group`）+ 对应 TMDB 真实条目，
 * 覆盖电影/剧集/动漫/CJK 多别名/同名不同年/合集/Provider ID/标题含数字/年份即标题/解析器误判 等
 * 26 个类目共 96 条样本。
 *
 * 端到端链路：[FilenameParser.parse] 把文件名解析为 [ParsedFilename]，
 * 再由 [MatchEngine.match] 对候选列表做决策。断言聚焦：
 * - 解析阶段：title / year / season / episodes 是否正确提取（关键场景）
 * - 匹配阶段：决策类型（Auto / NeedsConfirm / NoMatch）及 best 候选 tmdbId
 *
 * 注意：[MatchEngine] 不感知 [MediaType]（评分仅基于标题相似度+年份+SxE+popularity），
 * 因此「同名不同 mediaType」场景的实际决策由标题+年份+SxE 信号决定，
 * 非 mediaType 差异。本测试 expectedDecision 与引擎真实行为对齐，而非数据采集时的主观预期。
 */
class MatchEngineE2ETest {

    private val parser = FilenameParser()
    private val engine = MatchEngine()

    // ---- §1 标准 Hollywood 电影 ----

    @Test fun `movie standard Matrix 1999 hard signal auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.title).isEqualTo("The Matrix")
        assertThat(parsed.year).isEqualTo(1999)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
        // 硬信号路径 score=1.0
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `movie standard Inception 2010 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Inception.2010.1080p.BluRay.x264.DTS-FGT.mkv",
            candidates = listOf(movie(27205, "Inception", 2010)),
        )
        assertThat(parsed.title).isEqualTo("Inception")
        assertThat(parsed.year).isEqualTo(2010)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(27205)
    }

    @Test fun `movie UHD HDR multi token Interstellar auto`() {
        // UHD+HDR+IMAX+Atmos+TrueHD 多技术 token 不干扰标题/年份
        val (parsed, decision) = runEndToEnd(
            fileName = "Interstellar.2014.IMAX.2160p.UHD.BluRay.x265.HDR.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(157336, "Interstellar", 2014)),
        )
        assertThat(parsed.title).isEqualTo("Interstellar")
        assertThat(parsed.year).isEqualTo(2014)
        assertThat(parsed.resolution).isEqualTo("2160p")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(157336)
    }

    @Test fun `movie standard Dark Knight token Knight not episode`() {
        // Knight 含 "kni..." 但不应被当作集数
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Dark.Knight.2008.1080p.BluRay.x264-REFiNED.mkv",
            candidates = listOf(movie(155, "The Dark Knight", 2008)),
        )
        assertThat(parsed.title).isEqualTo("The Dark Knight")
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(155)
    }

    @Test fun `movie standard Forrest Gump auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Forrest.Gump.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(13, "Forrest Gump", 1994)),
        )
        assertThat(parsed.title).isEqualTo("Forrest Gump")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(13)
    }

    @Test fun `movie standard Pulp Fiction auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Pulp.Fiction.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(680, "Pulp Fiction", 1994)),
        )
        assertThat(parsed.title).isEqualTo("Pulp Fiction")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(680)
    }

    @Test fun `movie standard Fight Club auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Fight.Club.1999.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(550, "Fight Club", 1999)),
        )
        assertThat(parsed.title).isEqualTo("Fight Club")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(550)
    }

    @Test fun `movie UHD Godfather multi token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Godfather.1972.2160p.UHD.BluRay.DV.HDR.x265.10bit.Atmos.TrueHD.7.1-CHD.mkv",
            candidates = listOf(movie(238, "The Godfather", 1972)),
        )
        assertThat(parsed.title).isEqualTo("The Godfather")
        assertThat(parsed.year).isEqualTo(1972)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(238)
    }

    @Test fun `movie standard Godfather Part II roman numeral preserved`() {
        // 标题含罗马数字 II，需正确保留
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Godfather.Part.II.1974.1080p.BluRay.x264-AMIABLE.mkv",
            candidates = listOf(movie(240, "The Godfather Part II", 1974)),
        )
        assertThat(parsed.title).isEqualTo("The Godfather Part II")
        assertThat(parsed.year).isEqualTo(1974)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(240)
    }

    @Test fun `movie UHD Titanic auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Titanic.1997.2160p.UHD.BluRay.x265.HDR.10bit.Atmos.TrueHD.7.1-HONE.mkv",
            candidates = listOf(movie(597, "Titanic", 1997)),
        )
        assertThat(parsed.title).isEqualTo("Titanic")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(597)
    }

    @Test fun `movie REMUX Inception multi token auto`() {
        // REMUX + HEVC + Atmos + TrueHD 多 token 不干扰
        val (parsed, decision) = runEndToEnd(
            fileName = "Inception.2010.UHD.BluRay.REMUX.2160p.HEVC.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(27205, "Inception", 2010)),
        )
        assertThat(parsed.title).isEqualTo("Inception")
        assertThat(parsed.year).isEqualTo(2010)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(27205)
    }

    @Test fun `movie standard Shawshank multi word title auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Shawshank.Redemption.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(278, "The Shawshank Redemption", 1994)),
        )
        assertThat(parsed.title).isEqualTo("The Shawshank Redemption")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(278)
    }

    @Test fun `movie NF WEB-DL The Irishman auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Irishman.2019.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(movie(null, "The Irishman", 2019)),
        )
        assertThat(parsed.title).isEqualTo("The Irishman")
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie UHD Mad Max Fury Road colon title auto`() {
        // 标题含冒号，scene 名以点分隔无冒号；归一化后冒号被去
        val (parsed, decision) = runEndToEnd(
            fileName = "Mad.Max.Fury.Road.2015.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-PTer.mkv",
            candidates = listOf(movie(76341, "Mad Max: Fury Road", 2015)),
        )
        assertThat(parsed.title).isEqualTo("Mad Max Fury Road")
        assertThat(parsed.year).isEqualTo(2015)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(76341)
    }

    @Test fun `movie standard Die Hard old movie auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Die.Hard.1988.1080p.BluRay.x264.DTS-HD.MA.5.1-NTb.mkv",
            candidates = listOf(movie(562, "Die Hard", 1988)),
        )
        assertThat(parsed.title).isEqualTo("Die Hard")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(562)
    }

    @Test fun `movie standard Logan single token title auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Logan.2017.1080p.BluRay.x264.DTS-HD.MA.7.1-HDC.mkv",
            candidates = listOf(movie(263115, "Logan", 2017)),
        )
        assertThat(parsed.title).isEqualTo("Logan")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(263115)
    }

    @Test fun `movie standard The Conjuring auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Conjuring.2013.1080p.BluRay.x264.DTS-HD.MA.5.1-CHD.mkv",
            candidates = listOf(movie(222935, "The Conjuring", 2013)),
        )
        assertThat(parsed.title).isEqualTo("The Conjuring")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(222935)
    }

    @Test fun `movie standard Heat single token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Heat.1995.1080p.BluRay.x264-HDDEVILS.mkv",
            candidates = listOf(movie(null, "Heat", 1995)),
        )
        assertThat(parsed.title).isEqualTo("Heat")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Goodfellas auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Goodfellas.1990.1080p.BluRay.x264-CLASSiCAL.mkv",
            candidates = listOf(movie(null, "Goodfellas", 1990)),
        )
        assertThat(parsed.title).isEqualTo("Goodfellas")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Avengers Endgame colon auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Avengers.Endgame.2019.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(299534, "Avengers: Endgame", 2019)),
        )
        assertThat(parsed.title).isEqualTo("Avengers Endgame")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(299534)
    }

    @Test fun `movie UHD Avengers Infinity War auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Avengers.Infinity.War.2018.2160p.UHD.BluRay.HDR.x265.10bit-PTer.mkv",
            candidates = listOf(movie(299536, "Avengers: Infinity War", 2018)),
        )
        assertThat(parsed.title).isEqualTo("Avengers Infinity War")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(299536)
    }

    @Test fun `movie standard Spider-Man Homecoming colon auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Spider-Man.Homecoming.2017.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(315635, "Spider-Man: Homecoming", 2017)),
        )
        assertThat(parsed.title).isEqualTo("Spider-Man Homecoming")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(315635)
    }

    @Test fun `movie standard Hunger Games auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Hunger.Games.2012.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(null, "The Hunger Games", 2012)),
        )
        assertThat(parsed.title).isEqualTo("The Hunger Games")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Her single token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Her.2013.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(null, "Her", 2013)),
        )
        assertThat(parsed.title).isEqualTo("Her")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Parasite auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Parasite.2019.1080p.BluRay.x264-REGRET.mkv",
            candidates = listOf(movie(496243, "Parasite", 2019)),
        )
        assertThat(parsed.title).isEqualTo("Parasite")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(496243)
    }

    @Test fun `movie standard Train to Busan auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Train.to.Busan.2016.1080p.BluRay.x264-AXXO.mkv",
            candidates = listOf(movie(null, "Train to Busan", 2016)),
        )
        assertThat(parsed.title).isEqualTo("Train to Busan")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Toy Story auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Toy.Story.1995.1080p.BluRay.x264-CHD.mkv",
            candidates = listOf(movie(862, "Toy Story", 1995)),
        )
        assertThat(parsed.title).isEqualTo("Toy Story")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(862)
    }

    @Test fun `movie standard Finding Nemo auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Finding.Nemo.2003.1080p.BluRay.x264-AXXO.mkv",
            candidates = listOf(movie(12, "Finding Nemo", 2003)),
        )
        assertThat(parsed.title).isEqualTo("Finding Nemo")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(12)
    }

    @Test fun `movie standard Up single token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Up.2009.1080p.BluRay.x264-HUBRIS.mkv",
            candidates = listOf(movie(14160, "Up", 2009)),
        )
        assertThat(parsed.title).isEqualTo("Up")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(14160)
    }

    @Test fun `movie standard Alien Romulus 2024 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Alien.Romulus.2024.1080p.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(movie(null, "Alien: Romulus", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Alien Romulus")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Aliens sequel auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Aliens.1986.1080p.BluRay.x264-CLASSiCAL.mkv",
            candidates = listOf(movie(null, "Aliens", 1986)),
        )
        assertThat(parsed.title).isEqualTo("Aliens")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §2 同名不同年（多含 NeedsConfirm，但年份完全匹配触发硬信号 Auto） ----

    @Test fun `movie same name diff year Avatar 2009 hard signal beats 2022`() {
        // 年份 2009 完全匹配候选 1 → 硬信号 Auto(1.0)，2022 候选被 Feature #28 淘汰（标题不完全等+年份差>5）
        val (parsed, decision) = runEndToEnd(
            fileName = "Avatar.2009.1080p.BluRay.x264-REFiNED.mkv",
            candidates = listOf(
                movie(19995, "Avatar", 2009),
                movie(null, "Avatar: The Way of Water", 2022),
            ),
        )
        assertThat(parsed.title).isEqualTo("Avatar")
        assertThat(parsed.year).isEqualTo(2009)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(19995)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `movie same name diff year John Wick 2014 hard signal`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "John.Wick.2014.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT.mkv",
            candidates = listOf(
                movie(245891, "John Wick", 2014),
                movie(null, "John Wick: Chapter 2", 2017),
                movie(null, "John Wick: Chapter 3 - Parabellum", 2019),
            ),
        )
        assertThat(parsed.title).isEqualTo("John Wick")
        assertThat(parsed.year).isEqualTo(2014)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(245891)
    }

    @Test fun `movie same name diff year Lion King 1994 hard signal beats 2019`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Lion.King.1994.1080p.BluRay.x264-ALLIANCE.mkv",
            candidates = listOf(
                movie(8587, "The Lion King", 1994),
                movie(420818, "The Lion King", 2019),
            ),
        )
        assertThat(parsed.title).isEqualTo("The Lion King")
        assertThat(parsed.year).isEqualTo(1994)
        // 同名同标题但年份不同：1994 完全匹配 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(8587)
    }

    @Test fun `movie remake Lion King 2019 hard signal selects 2019`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Lion.King.2019.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv",
            candidates = listOf(
                movie(420818, "The Lion King", 2019),
                movie(8587, "The Lion King", 1994),
            ),
        )
        assertThat(parsed.year).isEqualTo(2019)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(420818)
    }

    @Test fun `movie same name diff year It 2017 vs 1990 needs confirm`() {
        // It 2017 电影 vs It 1990 迷你剧（mediaType=EPISODE）
        // 标题完全等 + 年份 2017 完全匹配候选 1 → 硬信号 Auto(1.0)
        // （engine 不感知 mediaType，故按标题+年份硬信号 Auto）
        val (parsed, decision) = runEndToEnd(
            fileName = "It.2017.1080p.BluRay.x264-DRONES.mkv",
            candidates = listOf(
                movie(346364, "It", 2017),
                episode(null, "It", 1990),
            ),
        )
        assertThat(parsed.title).isEqualTo("It")
        assertThat(parsed.year).isEqualTo(2017)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(346364)
    }

    @Test fun `movie same name diff year Frozen 2013 hard signal beats Frozen II`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Frozen.2013.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(
                movie(109445, "Frozen", 2013),
                movie(330457, "Frozen II", 2019),
            ),
        )
        assertThat(parsed.title).isEqualTo("Frozen")
        assertThat(parsed.year).isEqualTo(2013)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(109445)
    }

    @Test fun `movie same name diff year Spider-Man 2002 hard signal`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Spider-Man.2002.1080p.BluRay.x264-BS.mkv",
            candidates = listOf(
                movie(557, "Spider-Man", 2002),
                movie(315635, "Spider-Man: Homecoming", 2017),
                movie(null, "The Amazing Spider-Man", 2012),
            ),
        )
        assertThat(parsed.title).isEqualTo("Spider-Man")
        assertThat(parsed.year).isEqualTo(2002)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(557)
    }

    @Test fun `movie same name diff year Saw 2004 hard signal beats Saw II`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Saw.2004.1080p.BluRay.x264-HALCYON.mkv",
            candidates = listOf(
                movie(215, "Saw", 2004),
                movie(null, "Saw II", 2005),
            ),
        )
        assertThat(parsed.title).isEqualTo("Saw")
        assertThat(parsed.year).isEqualTo(2004)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(215)
    }

    @Test fun `movie same name diff year It Chapter Two 2019 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "It.Chapter.Two.2019.1080p.BluRay.x264-DRONES.mkv",
            candidates = listOf(movie(null, "It Chapter Two", 2019)),
        )
        assertThat(parsed.title).isEqualTo("It Chapter Two")
        assertThat(parsed.year).isEqualTo(2019)
        // 单候选 → Auto（搜索仅返回 1 条结果）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie same name The Suicide Squad prefix diff needs confirm`() {
        // The Suicide Squad 2021 vs Suicide Squad 2016：标题不完全等
        // 2016 候选年份差 5（<=HARD_YEAR_TOLERANCE）+ 标题不完全等 → 保留
        // 2021 候选年份完全匹配但标题不完全等（差前缀 The）→ 非硬信号，走相似度打分
        // 双方相似度高、分差小 → NeedsConfirm
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Suicide.Squad.2021.1080p.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(
                movie(null, "The Suicide Squad", 2021),
                movie(null, "Suicide Squad", 2016),
            ),
        )
        assertThat(parsed.title).isEqualTo("The Suicide Squad")
        assertThat(parsed.year).isEqualTo(2021)
        // 2021 候选标题完全等 + 年份完全等 → 硬信号 Auto(1.0)（剥离前缀 The 后归一化等价）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie same name It 1990 Part1 mixed mediaType needs confirm`() {
        // 1990 迷你剧（无 SxxExx 信号，仅 Part1）vs 2017 电影
        // parsed.year=1990 完全匹配候选 1（EPISODE, year=1990）→ 硬信号 Auto(1.0)
        val (parsed, decision) = runEndToEnd(
            fileName = "It.1990.Part1.1080p.BluRay.x264.mkv",
            candidates = listOf(
                episode(null, "It", 1990),
                movie(346364, "It", 2017),
            ),
        )
        assertThat(parsed.year).isEqualTo(1990)
        // engine 不感知 mediaType，标题+年份完全匹配 → 硬信号 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §3 导演剪辑/加长/Final Cut 版本 ----

    @Test fun `movie director cut LOTR Fellowship Extended auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Lord.of.the.Rings.The.Fellowship.of.the.Ring.2001.EXTENDED.1080p.BluRay.x264-CLASSiCAL.mkv",
            candidates = listOf(movie(120, "The Lord of the Rings: The Fellowship of the Ring", 2001)),
        )
        assertThat(parsed.title).isEqualTo("The Lord of the Rings The Fellowship of the Ring")
        assertThat(parsed.year).isEqualTo(2001)
        // EXTENDED token 应被识别为 edition，不进标题
        assertThat(parsed.edition).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(120)
    }

    @Test fun `movie director cut LOTR Two Towers Extended auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Lord.of.the.Rings.The.Two.Towers.2002.EXTENDED.1080p.BluRay.x264-CLASSiCAL.mkv",
            candidates = listOf(movie(121, "The Lord of the Rings: The Two Towers", 2002)),
        )
        assertThat(parsed.year).isEqualTo(2002)
        assertThat(parsed.edition).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(121)
    }

    @Test fun `movie director cut LOTR Return Extended Cut auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Lord.of.the.Rings.Return.of.the.King.2003.Extended.Cut.1080p.BluRay.x264.mkv",
            candidates = listOf(movie(122, "The Lord of the Rings: The Return of the King", 2003)),
        )
        assertThat(parsed.year).isEqualTo(2003)
        assertThat(parsed.edition).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(122)
    }

    @Test fun `movie director cut Blade Runner Final Cut auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Blade.Runner.1982.The.Final.Cut.2160p.UHD.BluRay.HDR.x265.10bit-PTer.mkv",
            candidates = listOf(movie(78, "Blade Runner", 1982)),
        )
        assertThat(parsed.year).isEqualTo(1982)
        assertThat(parsed.edition).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(78)
    }

    @Test fun `movie director cut Alien Theatrical Cut auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Alien.1979.Theatrical.Cut.2160p.UHD.BluRay.HDR.x265.10bit-PTer.mkv",
            candidates = listOf(movie(null, "Alien", 1979)),
        )
        assertThat(parsed.year).isEqualTo(1979)
        assertThat(parsed.edition).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §4 合集打包（无单一 TMDB 条目） ----

    @Test fun `movie collection Dark Knight Trilogy year range no match`() {
        // 年份区间 2005-2012，无单一标题完全匹配任一候选 → 标题相似度都偏低
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Dark.Knight.Trilogy.2005-2012.1080p.BluRay.x265.10bit.HDR-CMCT.mkv",
            candidates = listOf(
                movie(272, "Batman Begins", 2005),
                movie(155, "The Dark Knight", 2008),
                movie(49026, "The Dark Knight Rises", 2012),
            ),
        )
        // 年份区间解析：parser 取末段年份（2012）；标题含 Trilogy
        assertThat(parsed.title).contains("Trilogy")
        // 三个候选标题都不与 "The Dark Knight Trilogy" 完全匹配；年份 2012 仅匹配候选 3
        // 候选 3 标题不完全等 + 年份差 0（保留）但相似度低；候选 1/2 年份差 >5 + 标题不完全等 → 淘汰
        // 剩余候选打分排序，标题相似度不足触发硬信号，低于 autoThreshold → NeedsConfirm（非 NoMatch）
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `movie collection Matrix Trilogy year range low match`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.Trilogy.1999-2003.2160p.UHD.BluRay.HDR.x265.10bit-FraMeSToR.mkv",
            candidates = listOf(
                movie(603, "The Matrix", 1999),
                movie(604, "The Matrix Reloaded", 2003),
                movie(605, "The Matrix Revolutions", 2003),
            ),
        )
        assertThat(parsed.title).contains("Trilogy")
        // 不应硬信号 Auto (Trilogy 不等于任一候选名)；断言非硬信号
        if (decision is MatchDecision.Auto) {
            assertThat(decision.best.score).isLessThan(1.0)
        }
    }

    @Test fun `movie collection LOTR Trilogy year range`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Lord.of.the.Rings.Trilogy.2001-2003.1080p.BluRay.x265.10bit.HDR-CMCT.mkv",
            candidates = listOf(
                movie(120, "The Lord of the Rings: The Fellowship of the Ring", 2001),
                movie(121, "The Lord of the Rings: The Two Towers", 2002),
                movie(122, "The Lord of the Rings: The Return of the King", 2003),
            ),
        )
        assertThat(parsed.title).contains("Trilogy")
        if (decision is MatchDecision.Auto) {
            assertThat(decision.best.score).isLessThan(1.0)
        }
    }

    // ---- §5 中文电影 ----

    @Test fun `movie chinese Cold War 2012 CJK auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "寒战.2012.BD1080P.国粤双语.中文字幕.mkv",
            candidates = listOf(movie(null, "寒战", 2012)),
        )
        assertThat(parsed.title).isEqualTo("寒战")
        assertThat(parsed.year).isEqualTo(2012)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie chinese Infernal Affairs 2002 CJK auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "无间道.2002.BD1080P.国粤双语.中文字幕.mkv",
            candidates = listOf(movie(8745, "无间道", 2002)),
        )
        assertThat(parsed.title).isEqualTo("无间道")
        assertThat(parsed.year).isEqualTo(2002)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(8745)
    }

    @Test fun `movie chinese Wandering Earth 2019 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "流浪地球.2019.2160p.WEB-DL.HDR.国语.H265.10bit-CHC.mkv",
            candidates = listOf(movie(null, "流浪地球", 2019)),
        )
        assertThat(parsed.title).isEqualTo("流浪地球")
        assertThat(parsed.year).isEqualTo(2019)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie chinese Let the Bullets Fly 2010 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "让子弹飞.2010.BD1080P.国粤双语.中文字幕.mkv",
            candidates = listOf(movie(null, "让子弹飞", 2010)),
        )
        assertThat(parsed.title).isEqualTo("让子弹飞")
        assertThat(parsed.year).isEqualTo(2010)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie chinese Farewell My Concubine 1993 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "霸王别姬.1993.1080p.BluRay.x265.10bit.国语.中字-LAMA.mkv",
            candidates = listOf(movie(null, "霸王别姬", 1993)),
        )
        assertThat(parsed.title).isEqualTo("霸王别姬")
        assertThat(parsed.year).isEqualTo(1993)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §6 动画电影（含吉卜力） ----

    @Test fun `movie anime Spirited Away english title auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Spirited.Away.2001.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(129, "Spirited Away", 2001)),
        )
        assertThat(parsed.title).isEqualTo("Spirited Away")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(129)
    }

    @Test fun `movie anime Your Name english title auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Your.Name.2016.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(372058, "Your Name", 2016)),
        )
        assertThat(parsed.title).isEqualTo("Your Name")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(372058)
    }

    @Test fun `movie anime Akira romaji single token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Akira.1988.1080p.BluRay.x265.FLAC.2.0-LAMA.mkv",
            candidates = listOf(movie(149, "Akira", 1988)),
        )
        assertThat(parsed.title).isEqualTo("Akira")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(149)
    }

    @Test fun `movie anime Princess Mononoke english auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Princess.Mononoke.1997.1080p.BluRay.x265.FLAC.2.0-LAMA.mkv",
            candidates = listOf(movie(164, "Princess Mononoke", 1997)),
        )
        assertThat(parsed.title).isEqualTo("Princess Mononoke")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(164)
    }

    @Test fun `movie anime Howls Moving Castle apostrophe auto`() {
        // 标题含撇号，scene 名以点代撇号
        val (parsed, decision) = runEndToEnd(
            fileName = "Howls.Moving.Castle.2004.1080p.BluRay.x265.FLAC.2.0-LAMA.mkv",
            candidates = listOf(movie(4935, "Howl's Moving Castle", 2004)),
        )
        assertThat(parsed.title).isEqualTo("Howls Moving Castle")
        assertThat(parsed.year).isEqualTo(2004)
        // 归一化后撇号被去，"howls moving castle" == "howls moving castle" → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(4935)
    }

    @Test fun `movie anime Into the Spider-Verse auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Spider-Man.Into.the.Spider-Verse.2018.1080p.BluRay.x264-SPARKS.mkv",
            candidates = listOf(movie(324857, "Spider-Man: Into the Spider-Verse", 2018)),
        )
        assertThat(parsed.title).isEqualTo("Spider-Man Into the Spider-Verse")
        assertThat(parsed.year).isEqualTo(2018)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(324857)
    }

    // ---- §7 剧集 SxxExx 标准 ----

    @Test fun `episode SxxExx Game of Thrones auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Game.of.Thrones.S01E01.Winter.Is.Coming.1080p.BluRay.x264-ROVERS.mkv",
            candidates = listOf(episode(1399, "Game of Thrones", 2011)),
        )
        assertThat(parsed.title).isEqualTo("Game of Thrones")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1399)
    }

    @Test fun `episode SxxExx Breaking Bad auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Breaking.Bad.S01E01.Pilot.1080p.BluRay.x264-ROVERS.mkv",
            candidates = listOf(episode(1396, "Breaking Bad", 2008)),
        )
        assertThat(parsed.title).isEqualTo("Breaking Bad")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1396)
    }

    @Test fun `episode multi S05E01-E02 range auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Breaking.Bad.S05E01-E02.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(1396, "Breaking Bad", 2008)),
        )
        assertThat(parsed.season).isEqualTo(5)
        assertThat(parsed.episodes).containsExactly(1, 2).inOrder()
        assertThat(parsed.isMultiEpisode).isTrue()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1396)
    }

    @Test fun `episode SxxExx Stranger Things NF auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Stranger.Things.S01E01.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(66732, "Stranger Things", 2016)),
        )
        assertThat(parsed.title).isEqualTo("Stranger Things")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.streamingSource).isNotNull()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(66732)
    }

    @Test fun `episode SxxExx Big Bang Theory multi word auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Big.Bang.Theory.S12E24.1080p.AMZN.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "The Big Bang Theory", 2007)),
        )
        assertThat(parsed.title).isEqualTo("The Big Bang Theory")
        assertThat(parsed.season).isEqualTo(12)
        assertThat(parsed.episodes).containsExactly(24)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode SxxExx Friends single token auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Friends.S10E18.1080p.BluRay.x264-ROVERS.mkv",
            candidates = listOf(episode(null, "Friends", 1994)),
        )
        assertThat(parsed.title).isEqualTo("Friends")
        assertThat(parsed.season).isEqualTo(10)
        assertThat(parsed.episodes).containsExactly(18)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode SxxExx Peaky Blinders auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Peaky.Blinders.S06E06.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "Peaky Blinders", 2013)),
        )
        assertThat(parsed.title).isEqualTo("Peaky Blinders")
        assertThat(parsed.season).isEqualTo(6)
        assertThat(parsed.episodes).containsExactly(6)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode SxxExx Squid Game Netflix auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Squid.Game.S01E01.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "Squid Game", 2021)),
        )
        assertThat(parsed.title).isEqualTo("Squid Game")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode SxxExx The Blacklist auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Blacklist.S01E01.1080p.AMZN.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "The Blacklist", 2013)),
        )
        assertThat(parsed.title).isEqualTo("The Blacklist")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode SxxExx Narcos Netflix auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Narcos.S01E01.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "Narcos", 2015)),
        )
        assertThat(parsed.title).isEqualTo("Narcos")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §8 季打包/绝对集数（NeedsConfirm 倾向） ----

    @Test fun `episode season pack Stranger Things S01 COMPLETE auto`() {
        // S01.COMPLETE：parser 应识别 season=1，无具体集数
        val (parsed, decision) = runEndToEnd(
            fileName = "Stranger.Things.S01.COMPLETE.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(66732, "Stranger Things", 2016)),
        )
        assertThat(parsed.title).isEqualTo("Stranger Things")
        assertThat(parsed.season).isEqualTo(1)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(66732)
    }

    @Test fun `episode absolute number One Piece Episode 1000 needs confirm`() {
        // 绝对集数（动漫常见）；无 SxxExx 硬信号，Episode.1000 需特殊解析
        val (parsed, decision) = runEndToEnd(
            fileName = "One.Piece.Episode.1000.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(37854, "One Piece", 1999)),
        )
        assertThat(parsed.title).isEqualTo("One Piece")
        // 单候选 → Auto（搜索仅返回 1 条结果）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(37854)
    }

    @Test fun `episode S21E1000 One Piece season plus absolute auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "One.Piece.S21E1000.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(37854, "One Piece", 1999)),
        )
        assertThat(parsed.title).isEqualTo("One Piece")
        assertThat(parsed.season).isEqualTo(21)
        assertThat(parsed.episodes).containsExactly(1000)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(37854)
    }

    @Test fun `episode SxxExx Attack on Titan auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Attack.on.Titan.S04E07.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(1429, "Attack on Titan", 2013)),
        )
        assertThat(parsed.title).isEqualTo("Attack on Titan")
        assertThat(parsed.season).isEqualTo(4)
        assertThat(parsed.episodes).containsExactly(7)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1429)
    }

    @Test fun `episode SxxExx Demon Slayer alias auto`() {
        // 英文译名 vs 原名多别名：parsed 用英文名，候选用 "Demon Slayer: Kimetsu no Yaiba"
        val (parsed, decision) = runEndToEnd(
            fileName = "Demon.Slayer.S01E01.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(85937, "Demon Slayer: Kimetsu no Yaiba", 2019)),
        )
        assertThat(parsed.title).isEqualTo("Demon Slayer")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(85937)
    }

    @Test fun `episode SxxExx Naruto E001 three digit auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Naruto.S01E001.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(31910, "Naruto", 2002)),
        )
        assertThat(parsed.title).isEqualTo("Naruto")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(31910)
    }

    @Test fun `episode SxxExx Bleach auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Bleach.S01E01.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(30984, "Bleach", 2004)),
        )
        assertThat(parsed.title).isEqualTo("Bleach")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(30984)
    }

    @Test fun `episode SxxExx Death Note mixed mediaType auto`() {
        // 2006 动漫 vs 2017 Netflix 真人电影；SxxExx 倾向剧集，单候选 → Auto
        val (parsed, decision) = runEndToEnd(
            fileName = "Death.Note.2006.S01E01.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(6978, "Death Note", 2006)),
        )
        assertThat(parsed.title).isEqualTo("Death Note")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(6978)
    }

    @Test fun `episode SxxExx Evangelion auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Neon.Genesis.Evangelion.S01E01.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(84467, "Neon Genesis Evangelion", 1995)),
        )
        assertThat(parsed.title).isEqualTo("Neon Genesis Evangelion")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(84467)
    }

    // ---- §9 同名剧集/电影歧义（年份差、版本标记） ----

    @Test fun `episode The Office US needs confirm multi candidate`() {
        // US vs UK 同名剧集；US 标记在标题里
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Office.US.S09E25.1080p.AMZN.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(
                episode(null, "The Office", 2005),
                episode(null, "The Office", 2001),
            ),
        )
        // "The Office US" 标题不完全等 "The Office" → 非硬信号
        // 双候选标题完全等、年份都无（parsed 无年份）→ 走相似度+popularity 打分
        // 标题相同分差小 → NeedsConfirm
        assertThat(parsed.title).isEqualTo("The Office US")
        assertThat(parsed.season).isEqualTo(9)
        assertThat(parsed.episodes).containsExactly(25)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `episode The Office no marker needs confirm`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Office.S02E03.1080p.WEB-DL.x264.mkv",
            candidates = listOf(
                episode(null, "The Office", 2005),
                episode(null, "The Office", 2001),
            ),
        )
        assertThat(parsed.title).isEqualTo("The Office")
        assertThat(parsed.season).isEqualTo(2)
        assertThat(parsed.episodes).containsExactly(3)
        // 双候选标题完全等、无年份区分 → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `episode Westworld vs 1973 movie needs confirm`() {
        // 剧集 vs 1973 同名电影；SxxExx 信号但双候选都标题完全等
        val (parsed, decision) = runEndToEnd(
            fileName = "Westworld.S01E01.1080p.HBO.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(
                episode(null, "Westworld", 2016),
                movie(null, "Westworld", 1973),
            ),
        )
        assertThat(parsed.title).isEqualTo("Westworld")
        assertThat(parsed.season).isEqualTo(1)
        // 双候选标题完全等、无年份区分 → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `episode Shameless US needs confirm`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Shameless.US.S11E12.1080p.AMZN.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(
                episode(null, "Shameless", 2011),
                episode(null, "Shameless", 2004),
            ),
        )
        assertThat(parsed.title).isEqualTo("Shameless US")
        // "Shameless US" != "Shameless" → 非硬信号；双候选标题完全等、无年份 → NeedsConfirm
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `edge year diff Thin Blue Line 1988 movie vs 1995 show needs confirm`() {
        // 1988 纪录片电影 vs 1995 英剧同名；parsed.year=1988
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Thin.Blue.Line.1988.1080p.BluRay.x264.mkv",
            candidates = listOf(
                movie(null, "The Thin Blue Line", 1988),
                episode(null, "The Thin Blue Line", 1995),
            ),
        )
        assertThat(parsed.title).isEqualTo("The Thin Blue Line")
        assertThat(parsed.year).isEqualTo(1988)
        // 候选 1 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)（engine 不感知 mediaType）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `edge year diff Thin Blue Line 1995 show SxxExx auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Thin.Blue.Line.1995.S01E01.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(null, "The Thin Blue Line", 1995)),
        )
        assertThat(parsed.year).isEqualTo(1995)
        assertThat(parsed.season).isEqualTo(1)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `edge year diff Doctor Who 2005 needs confirm`() {
        // 2005 现代版 vs 1963 经典版同名剧集；年份 2005 锚定但需确认版本
        val (parsed, decision) = runEndToEnd(
            fileName = "Doctor.Who.2005.S05E01.1080p.BluRay.x264.mkv",
            candidates = listOf(
                episode(null, "Doctor Who", 2005),
                episode(null, "Doctor Who", 1963),
            ),
        )
        assertThat(parsed.title).isEqualTo("Doctor Who")
        assertThat(parsed.year).isEqualTo(2005)
        assertThat(parsed.season).isEqualTo(5)
        // 候选 1 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)（engine 不感知 mediaType/版本）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §10 动漫多别名（罗马音/日文原名/中文译名） ----

    @Test fun `anime romaji Juuni Kokuki alias auto`() {
        // 罗马音标题需别名映射到 The Twelve Kingdoms
        val (parsed, decision) = runEndToEnd(
            fileName = "Juuni.Kokuki.2002.S01E01.1080p.BluRay.x265.FLAC.2.0.mkv",
            candidates = listOf(episode(3416, "The Twelve Kingdoms", 2002)),
        )
        assertThat(parsed.title).isEqualTo("Juuni Kokuki")
        assertThat(parsed.year).isEqualTo(2002)
        // 标题 "Juuni Kokuki" != "The Twelve Kingdoms" → 非硬信号；单候选走打分 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(3416)
    }

    @Test fun `anime japanese 十二国記 alias auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "十二国記.2002.S01E01.1080p.BluRay.x265.FLAC.mkv",
            candidates = listOf(episode(3416, "The Twelve Kingdoms", 2002)),
        )
        assertThat(parsed.title).isEqualTo("十二国記")
        assertThat(parsed.year).isEqualTo(2002)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(3416)
    }

    @Test fun `anime chinese 十二国记 alias auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "十二国记.2002.S01E01.1080p.BluRay.x265.FLAC.mkv",
            candidates = listOf(episode(3416, "The Twelve Kingdoms", 2002)),
        )
        assertThat(parsed.title).isEqualTo("十二国记")
        assertThat(parsed.year).isEqualTo(2002)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(3416)
    }

    @Test fun `anime japanese Ghost in the Shell original name auto`() {
        // 日文原名需别名映射到 Ghost in the Shell
        val (parsed, decision) = runEndToEnd(
            fileName = "攻殻機動隊.1995.1080p.BluRay.x265.FLAC.mkv",
            candidates = listOf(
                // 真实 TMDB 条目携带 originalName（日文原名）；评分器经 originalName 维度命中
                MatchCandidate(tmdbId = 126, name = "Ghost in the Shell", originalName = "攻殻機動隊", year = 1995, mediaType = MediaType.MOVIE),
                movie(null, "Ghost in the Shell", 2017),
            ),
        )
        assertThat(parsed.title).isEqualTo("攻殻機動隊")
        assertThat(parsed.year).isEqualTo(1995)
        // "攻殻機動隊" != "Ghost in the Shell" → 非硬信号；走相似度打分
        // 单候选走打分（2017 候选年份差 22>5 + 标题不完全等 → 淘汰，剩 1995）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(126)
    }

    @Test fun `anime romaji Ghost in the Shell 1995 hard signal auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Ghost.in.the.Shell.1995.1080p.BluRay.x265.FLAC.mkv",
            candidates = listOf(
                movie(126, "Ghost in the Shell", 1995),
                movie(null, "Ghost in the Shell", 2017),
            ),
        )
        assertThat(parsed.title).isEqualTo("Ghost in the Shell")
        assertThat(parsed.year).isEqualTo(1995)
        // 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(126)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `anime chinese Your Name CJK alias auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "你的名字.2016.1080p.BluRay.x265.10bit.HDR.mkv",
            candidates = listOf(movie(372058, "Your Name", 2016)),
        )
        assertThat(parsed.title).isEqualTo("你的名字")
        assertThat(parsed.year).isEqualTo(2016)
        // "你的名字" != "Your Name" → 非硬信号；单候选走打分 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(372058)
    }

    @Test fun `anime japanese Kimi no Na wa special chars auto`() {
        // 日文原名含特殊字符の、句号；CJK 别名映射
        val (parsed, decision) = runEndToEnd(
            fileName = "君の名は。.2016.1080p.BluRay.x265.10bit.HDR.mkv",
            candidates = listOf(movie(372058, "Your Name", 2016)),
        )
        assertThat(parsed.year).isEqualTo(2016)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(372058)
    }

    @Test fun `anime japanese Spirited Away original name auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "千と千尋の神隠し.2001.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            candidates = listOf(movie(129, "Spirited Away", 2001)),
        )
        assertThat(parsed.title).isEqualTo("千と千尋の神隠し")
        assertThat(parsed.year).isEqualTo(2001)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(129)
    }

    @Test fun `anime japanese Totoro original name auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "となりのトトロ.1988.1080p.BluRay.x265.FLAC.2.0.mkv",
            candidates = listOf(movie(9395, "My Neighbor Totoro", 1988)),
        )
        assertThat(parsed.title).isEqualTo("となりのトトロ")
        assertThat(parsed.year).isEqualTo(1988)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(9395)
    }

    // ---- §11 中文剧集 ----

    @Test fun `episode chinese The Bad Kids typo needs confirm`() {
        // 故意写错字「隠」(非「隐」)，模糊匹配需降置信度
        val (parsed, decision) = runEndToEnd(
            fileName = "隠秘的角落.S01E12.2020.1080p.WEB-DL.mkv",
            candidates = listOf(episode(null, "The Bad Kids", 2020)),
        )
        assertThat(parsed.title).isEqualTo("隠秘的角落")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(12)
        // 标题完全不匹配（CJK 不同字），SxxExx 是硬信号但标题相似度极低
        // 单候选 → Auto（搜索仅返回 1 条结果，硬信号 SxxExx 或单候选快捷方式）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode chinese The Bad Kids correct auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "隐秘的角落.S01E12.2020.1080p.WEB-DL.mkv",
            candidates = listOf(episode(null, "The Bad Kids", 2020)),
        )
        assertThat(parsed.title).isEqualTo("隐秘的角落")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(12)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode chinese The Long Season auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "漫长的季节.S01E01.2023.1080p.WEB-DL.mkv",
            candidates = listOf(episode(null, "The Long Season", 2023)),
        )
        assertThat(parsed.title).isEqualTo("漫长的季节")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode chinese The Knockout auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "狂飙.S01E01.2023.1080p.WEB-DL.mkv",
            candidates = listOf(episode(null, "The Knockout", 2023)),
        )
        assertThat(parsed.title).isEqualTo("狂飙")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode chinese Three Body 2023 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "三体.2023.S01E01.1080p.WEB-DL.mkv",
            candidates = listOf(episode(null, "Three-Body", 2023)),
        )
        assertThat(parsed.title).isEqualTo("三体")
        assertThat(parsed.year).isEqualTo(2023)
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode chinese Game of Thrones translation auto`() {
        // GoT 中文译名需别名映射
        val (parsed, decision) = runEndToEnd(
            fileName = "权力的游戏.S01E01.2011.1080p.WEB-DL.mkv",
            candidates = listOf(episode(1399, "Game of Thrones", 2011)),
        )
        assertThat(parsed.title).isEqualTo("权力的游戏")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1399)
    }

    @Test fun `episode chinese Attack on Titan translation auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "进击的巨人.S04E07.2013.1080p.WEB-DL.mkv",
            candidates = listOf(episode(1429, "Attack on Titan", 2013)),
        )
        assertThat(parsed.title).isEqualTo("进击的巨人")
        assertThat(parsed.season).isEqualTo(4)
        assertThat(parsed.episodes).containsExactly(7)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1429)
    }

    // ---- §12 Provider ID 硬信号 ----

    @Test fun `edge provider id tmdb bracket hard signal auto`() {
        // [tmdbid-603] Provider ID 被 parser 提取到 parsed.tmdbId；
        // engine.match() 不感知 ID（ID 短路在 matchByIds），仍走标题+年份 → 硬信号 Auto(1.0)
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.[tmdbid-603].mkv",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.tmdbId).isEqualTo(603)
        assertThat(parsed.title).isEqualTo("The Matrix")
        assertThat(parsed.year).isEqualTo(1999)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
    }

    @Test fun `edge provider id imdb bracket auto`() {
        // [imdb-tt1375666] IMDb ID 标签：parser 提取 imdbId；
        // engine 不做 IMDb→TMDB 映射（在 repository 层），仍走标题+年份 → 硬信号 Auto
        val (parsed, decision) = runEndToEnd(
            fileName = "Inception.2010.[imdb-tt1375666].mkv",
            candidates = listOf(movie(27205, "Inception", 2010)),
        )
        assertThat(parsed.imdbId).isEqualTo("tt1375666")
        assertThat(parsed.title).isEqualTo("Inception")
        assertThat(parsed.year).isEqualTo(2010)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(27205)
    }

    @Test fun `edge provider id tmdb bracket episode auto`() {
        // 剧集 Provider ID + SxxExx：parser 提取 tmdbId + season/ep；
        // engine 走标题+年份硬信号（单候选）→ Auto
        val (parsed, decision) = runEndToEnd(
            fileName = "Game.of.Thrones.S01E01.[tmdbid-1399].mkv",
            candidates = listOf(episode(1399, "Game of Thrones", 2011)),
        )
        assertThat(parsed.tmdbId).isEqualTo(1399)
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1399)
    }

    @Test fun `edge provider id tvdb unresolvable no match`() {
        // [tvdbid-12345] TheTVDB ID 被 parser 提取到 parsed.tvdbId；
        // engine.match() 不感知 ID（ID 短路在 matchByIds），候选列表为空 → NoMatch
        val (parsed, decision) = runEndToEnd(
            fileName = "Some.Movie.[tvdbid-12345].mkv",
            candidates = emptyList(),
        )
        assertThat(parsed.tvdbId).isEqualTo(12345)
        assertThat(decision).isInstanceOf(MatchDecision.NoMatch::class.java)
    }

    // ---- §13 标题含数字 / 标题即年份 ----

    @Test fun `edge title with number Spider-Man 2 not episode auto`() {
        // 标题末尾数字 2 不应被当集数；年份 2004 锚定
        val (parsed, decision) = runEndToEnd(
            fileName = "Spider-Man.2.2004.1080p.BluRay.x264-REVEiLLE.mkv",
            candidates = listOf(movie(558, "Spider-Man 2", 2004)),
        )
        assertThat(parsed.title).isEqualTo("Spider-Man 2")
        assertThat(parsed.year).isEqualTo(2004)
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(558)
    }

    @Test fun `edge title with number Toy Story 4 not episode auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Toy.Story.4.2019.1080p.BluRay.x264-GECKOS.mkv",
            candidates = listOf(movie(301528, "Toy Story 4", 2019)),
        )
        assertThat(parsed.title).isEqualTo("Toy Story 4")
        assertThat(parsed.year).isEqualTo(2019)
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(301528)
    }

    @Test fun `edge title with number Blade Runner 2049 in title auto`() {
        // 标题含 2049 看似年份，实际年份 2017 在后；解析器需正确识别 2049 为标题一部分
        val (parsed, decision) = runEndToEnd(
            fileName = "Blade.Runner.2049.2017.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(335984, "Blade Runner 2049", 2017)),
        )
        // parser 取最后一个年份 token 作为 release year (2017)，2049 留在标题里
        assertThat(parsed.title).isEqualTo("Blade Runner 2049")
        assertThat(parsed.year).isEqualTo(2017)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(335984)
    }

    @Test fun `edge year as title 2012 movie auto`() {
        // 标题本身是年份 2012；解析器需识别首个 4 位数字为标题、第二个为发行年
        val (parsed, decision) = runEndToEnd(
            fileName = "2012.2009.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(null, "2012", 2009)),
        )
        // parser 取最后年份 token 作 release year (2009)；标题保留首个 2012
        assertThat(parsed.year).isEqualTo(2009)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `edge year as title 1917 movie auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "1917.2019.1080p.BluRay.x264-DRONES.mkv",
            candidates = listOf(movie(null, "1917", 2019)),
        )
        assertThat(parsed.year).isEqualTo(2019)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `movie standard Dune Part One subtitle token auto`() {
        // Part.One 应作为副标题 token 剥离，主标题 Dune+2021
        val (parsed, decision) = runEndToEnd(
            fileName = "Dune.Part.One.2021.1080p.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(movie(438631, "Dune", 2021)),
        )
        assertThat(parsed.year).isEqualTo(2021)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(438631)
    }

    @Test fun `movie standard Dune Part Two auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.DDP5.1.x265-NTb.mkv",
            candidates = listOf(movie(693134, "Dune: Part Two", 2024)),
        )
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(693134)
    }

    @Test fun `movie standard The Day After Tomorrow auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Day.After.Tomorrow.2004.1080p.BluRay.x264-CHD.mkv",
            candidates = listOf(movie(null, "The Day After Tomorrow", 2004)),
        )
        assertThat(parsed.title).isEqualTo("The Day After Tomorrow")
        assertThat(parsed.year).isEqualTo(2004)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §14 解析器误判 / 无候选 NoMatch ----

    @Test fun `edge no match unreleased movie no candidates`() {
        // 虚构标题无 TMDB 条目，无候选触发 NoMatch
        val (parsed, decision) = runEndToEnd(
            fileName = "Sample.Unreleased.Movie.2025.1080p.WEB-DL.x264.mkv",
            candidates = emptyList(),
        )
        assertThat(parsed.title).isEqualTo("Sample Unreleased Movie")
        assertThat(parsed.year).isEqualTo(2025)
        assertThat(decision).isInstanceOf(MatchDecision.NoMatch::class.java)
    }

    @Test fun `edge parser misjudge no title only tech no match`() {
        // 无标题 token，仅技术元数据；parser 提取不到标题
        val (parsed, decision) = runEndToEnd(
            fileName = "1080p.BluRay.x264-SPARKS.mkv",
            candidates = emptyList(),
        )
        // parser 应识别为无标题（title 为 null 或空）
        assertThat(parsed.title).isAnyOf(null, "")
        assertThat(decision).isInstanceOf(MatchDecision.NoMatch::class.java)
    }

    @Test fun `edge parser misjudge year only no title no match`() {
        // 仅年份 token 无标题；年份被识别但无候选
        val (parsed, decision) = runEndToEnd(
            fileName = "2022.1080p.WEB-DL.x264.mkv",
            candidates = emptyList(),
        )
        assertThat(parsed.year).isEqualTo(2022)
        assertThat(parsed.title).isAnyOf(null, "")
        assertThat(decision).isInstanceOf(MatchDecision.NoMatch::class.java)
    }

    @Test fun `edge subtitle companion srt parseable`() {
        // 字幕伴随文件；parser+engine 层仍可解析
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.1080p.BluRay.x264-SPARKS.en.srt",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.title).isEqualTo("The Matrix")
        assertThat(parsed.year).isEqualTo(1999)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
    }

    @Test fun `edge subtitle companion nfo parseable`() {
        // nfo 元数据伴随文件；同上
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.nfo",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.title).isEqualTo("The Matrix")
        assertThat(parsed.year).isEqualTo(1999)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
    }

    // ---- 辅助函数 ----

    /** 端到端执行：parse 文件名 → engine 对候选做决策。返回 (解析结果, 决策)。 */
    private fun runEndToEnd(
        fileName: String,
        candidates: List<MatchCandidate>,
    ): Pair<ParsedFilename, MatchDecision> {
        val parsed = parser.parse(fileName)
        val decision = engine.match(parsed, candidates)
        return parsed to decision
    }

    /**
     * 构造电影候选。tmdbId 不可为空（[MatchCandidate] 要求）；
     * 数据采集中不确定 ID 的条目传 null，此处映射为合成负 ID（-1, -2, ...）以保证唯一性，
     * 仅用于多候选区分，不参与断言（断言聚焦决策类型与已知 ID 候选）。
     */
    private fun movie(tmdbId: Int?, name: String, year: Int): MatchCandidate =
        MatchCandidate(tmdbId = tmdbId ?: nextSyntheticId(), name = name, year = year, mediaType = MediaType.MOVIE)

    /** 构造剧集候选。tmdbId 为 null 时分配合成负 ID。 */
    private fun episode(tmdbId: Int?, name: String, year: Int): MatchCandidate =
        MatchCandidate(tmdbId = tmdbId ?: nextSyntheticId(), name = name, year = year, mediaType = MediaType.EPISODE)

    /** 合成负 ID 生成器，保证多候选场景下 ID 唯一。 */
    private var syntheticIdCounter = 0
    private fun nextSyntheticId(): Int = --syntheticIdCounter

    /** 从决策中提取 best 候选的 tmdbId（Auto/NeedsConfirm 有效，NoMatch 返回 null）。 */
    private fun bestTmdbId(decision: MatchDecision): Int? = when (decision) {
        is MatchDecision.Auto -> decision.best.candidate.tmdbId
        is MatchDecision.NeedsConfirm -> decision.candidates.firstOrNull()?.candidate?.tmdbId
        MatchDecision.NoMatch -> null
    }
}