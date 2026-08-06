package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.FilenameParser
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

/**
 * 匹配系统端到端代码覆盖测试（扩展集）。
 *
 * 与 [MatchEngineE2ETest] 互补：本文件聚焦联网采集的"未覆盖"命名场景，
 * 包含日期型剧集 / Anime CRC 方括号 / 韩剧多平台来源 / 3D 与 HDR 全格式变体 /
 * 多语言季关键词（Staffel/Saison）/ 季区间 / 集号字母后缀 / OVA 特别篇 /
 * CD 分片 / Repack 版本标记 / TVMaze-ID / IMDb URL / TMDB URL / WxH 分辨率 /
 * 印度电影 / 2023-2024 新片 / originalName 跨语言匹配 等共 30 条样本。
 *
 * 数据来源：
 * - Scene release 命名规范（[Title.Year.Resolution.Source.Codec-Audio-Group]）
 * - 字幕组/压制组动画命名惯例（[Group][Title][Ep][Tech][CRC]）
 * - 韩剧 WEB-DL 命名（Viki/TVING/Disney+/Netflix 平台标记 + EP01 集号格式）
 * - TMDB 真实条目（tmdbId 已知者直接断言，不确定者用 null + 合成负 ID）
 *
 * 端到端链路：[FilenameParser.parse] → [MatchEngine.match]。
 * 断言聚焦：解析阶段关键字段（title/year/season/episodes/技术标签/Provider ID）
 * 与匹配阶段决策类型（Auto/NeedsConfirm/NoMatch）及 best 候选 tmdbId。
 */
class MatchEngineE2EExtendedTest {

    private val parser = FilenameParser()
    private val engine = MatchEngine()

    // ---- §A 日期型剧集（daily show）----

    @Test fun `daily show US date format triggers isDailyShow`() {
        // 文件名含 2024.01.15 形式的日期 → parser 标记 isDailyShow=true，年份被剥离
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Daily.Show.2024.01.15.1080p.WEB-DL.x264-NTb.mkv",
            candidates = listOf(episode(23252, "The Daily Show", 1996)),
        )
        assertThat(parsed.title).isEqualTo("The Daily Show")
        assertThat(parsed.isDailyShow).isTrue()
        assertThat(parsed.year).isNull() // 日期串中的 2024 被剥离，不再当作发行年
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(23252)
    }

    // ---- §B Anime CRC 方括号命名 ----

    @Test fun `anime CRC SBSUB CONAN multi bracket parse`() {
        // 6 个连续方括号 + 第 3 个为纯数字集号 → tryAnimePattern 触发
        // (00B82A9E) 圆括号 CRC 在 anime 路径下被忽略
        val (parsed, decision) = runEndToEnd(
            fileName = "[SBSUB][CONAN][988][1080P][AVC_AAC][CHS_JP](00B82A9E).mp4",
            candidates = listOf(episode(null, "Detective Conan", 1996)),
        )
        assertThat(parsed.title).isEqualTo("CONAN")
        assertThat(parsed.episodes).containsExactly(988)
        assertThat(parsed.isAbsoluteEpisode).isTrue()
        assertThat(parsed.resolution).isEqualTo("1080p")
        assertThat(parsed.group).isEqualTo("SBSUB")
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto（标题不完全等，走单候选快捷方式）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `anime bracket not enough brackets falls back to normal parse`() {
        // 仅 2 个方括号 → tryAnimePattern 不触发（需 ≥3）；走常规解析路径
        // (2004) 圆括号年份保留；BD-1080p_H264_FLAC_2.0 方括号内技术 token 被 parseTech 识别
        val (parsed, decision) = runEndToEnd(
            fileName = "[Coalgirls]_Monster_(2004)_[BD-1080p_H264_FLAC_2.0].mkv",
            candidates = listOf(movie(null, "Monster", 2004)),
        )
        assertThat(parsed.title).isEqualTo("Monster")
        assertThat(parsed.year).isEqualTo(2004)
        assertThat(parsed.resolution).isEqualTo("1080p")
        assertThat(parsed.videoCodec).isEqualTo("h264")
        assertThat(parsed.audioCodec).isEqualTo("flac")
        assertThat(parsed.group).isEqualTo("Coalgirls")
        // 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    // ---- §C 韩剧 / 多平台来源 ----

    @Test fun `korean drama Squid Game S02 2024 NF auto`() {
        // 韩剧常见命名：Title.SxxExx.Year.Source.WEB-DL...
        val (parsed, decision) = runEndToEnd(
            fileName = "Squid.Game.S02E01.2024.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(null, "Squid Game", 2021)),
        )
        assertThat(parsed.title).isEqualTo("Squid Game")
        assertThat(parsed.season).isEqualTo(2)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.streamingSource).isEqualTo("Netflix")
        // 单候选；标题等但年份差 3（≤5）→ 保留；非硬信号 → 走单候选快捷方式 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `korean drama Light Shop DSNP 2024 hard signal auto`() {
        // Disney+ 来源标记 DSNP；HDR10 + H265 多技术 token
        val (parsed, decision) = runEndToEnd(
            fileName = "Light.Shop.S01E01.2024.2160p.DSNP.WEB-DL.DDP5.1.Atmos.HDR10.H265.mkv",
            candidates = listOf(episode(null, "Light Shop", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Light Shop")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.streamingSource).isEqualTo("Disney+")
        assertThat(parsed.hdr).isEqualTo("HDR10")
        // 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `korean drama Under the Gun 2024 EP01 standalone ep auto`() {
        // 韩剧偶发命名：EP01 独立集号格式（无 SxxExx）
        val (parsed, decision) = runEndToEnd(
            fileName = "Under.the.Gun.2024.EP01.1080p.WEB-DL.x264-NTb.mkv",
            candidates = listOf(episode(null, "Under the Gun", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Under the Gun")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.season).isNull()
        // 标题完全等 + 年份完全等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `the boys S04 2024 AMZN streamingSource auto`() {
        // AMZN → Amazon；S04 季标记
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Boys.S04E01.2024.1080p.AMZN.WEB-DL.DDP5.1.x264-NTb.mkv",
            candidates = listOf(episode(76479, "The Boys", 2006)),
        )
        assertThat(parsed.title).isEqualTo("The Boys")
        assertThat(parsed.season).isEqualTo(4)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.streamingSource).isEqualTo("Amazon")
        // 单候选；年份差 18>5 但标题完全等 → Feature #28 保留；非硬信号 → 单候选 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(76479)
    }

    // ---- §D 3D / HDR 全格式变体 ----

    @Test fun `3D SBS format detected Avatar 2009 auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Avatar.2009.1080p.BluRay.x264.DTS.HD.MA.5.1.3D.SBS-FGT.mkv",
            candidates = listOf(movie(19995, "Avatar", 2009)),
        )
        assertThat(parsed.title).isEqualTo("Avatar")
        assertThat(parsed.year).isEqualTo(2009)
        assertThat(parsed.threeD).isEqualTo("3D SBS")
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `3D TAB format detected`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2018.1080p.BluRay.3D.TAB.x264.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2018)),
        )
        assertThat(parsed.threeD).isEqualTo("3D TAB")
        assertThat(parsed.title).isEqualTo("Demo Movie")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `3D MVC format detected`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2017.1080p.BluRay.3D.MVC.x264.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2017)),
        )
        assertThat(parsed.threeD).isEqualTo("3D MVC")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `HDR10+ format detected`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2024.2160p.WEB-DL.HDR10+.x265.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2024)),
        )
        assertThat(parsed.hdr).isEqualTo("HDR10+")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `Dolby Vision only format detected`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2024.2160p.WEB-DL.DV.x265.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2024)),
        )
        assertThat(parsed.hdr).isEqualTo("Dolby Vision")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `HLG format detected`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2023.2160p.WEB-DL.HLG.x265.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2023)),
        )
        assertThat(parsed.hdr).isEqualTo("HLG")
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `Dune Part Two DV HDR 2024 hard signal auto`() {
        // DV + HDR 同时存在：parseHdr 优先级 DV → "Dolby Vision"（先匹配）
        val (parsed, decision) = runEndToEnd(
            fileName = "Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.DDP5.1.x265-NTb.mkv",
            candidates = listOf(movie(693134, "Dune: Part Two", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Dune Part Two")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.hdr).isNotNull()
        // 标题归一化等 + 年份等 → 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(693134)
    }

    // ---- §E 2023-2024 新片 ----

    @Test fun `Oppenheimer 2023 UHD HDR auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Oppenheimer.2023.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(null, "Oppenheimer", 2023)),
        )
        assertThat(parsed.title).isEqualTo("Oppenheimer")
        assertThat(parsed.year).isEqualTo(2023)
        assertThat(parsed.resolution).isEqualTo("2160p")
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `Inside Out 2 2024 hard signal auto`() {
        // 标题末尾数字 2 不应被当作集数（有年份时不进 tryAbsoluteEpisode）
        val (parsed, decision) = runEndToEnd(
            fileName = "Inside.Out.2.2024.2160p.WEB-DL.DV.HDR.x265-NTb.mkv",
            candidates = listOf(movie(1022789, "Inside Out 2", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Inside Out 2")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.season).isNull()
        assertThat(parsed.episodes).isEmpty()
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1022789)
    }

    // ---- §F 季区间 / 集号字母后缀 / OVA ----

    @Test fun `multi season range S01-S12 COMPLETE auto`() {
        // S01-S12 → SEASON_RANGE 匹配，记起始季 season=1
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Big.Bang.Theory.S01-S12.COMPLETE.1080p.BluRay.x264-ROVERS.mkv",
            candidates = listOf(episode(null, "The Big Bang Theory", 2007)),
        )
        assertThat(parsed.title).isEqualTo("The Big Bang Theory")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).isEmpty()
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `episode letter suffix S01E02a parsed`() {
        // S01E02a → episodeSubPart="a"（同集分上下部）
        val (parsed, decision) = runEndToEnd(
            fileName = "Series.Name.S01E02a.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(null, "Series Name", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Series Name")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(2)
        assertThat(parsed.episodeSubPart).isEqualTo("a")
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `anime special token season 0 detected`() {
        // 文件名含独立词 "Special" → ANIME_SPECIAL_TOKEN 触发 → season=0（特别篇）
        val (parsed, decision) = runEndToEnd(
            fileName = "Attack.on.Titan.Special.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(1429, "Attack on Titan", 2013)),
        )
        assertThat(parsed.title).isEqualTo("Attack on Titan")
        assertThat(parsed.season).isEqualTo(0)
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1429)
    }

    // ---- §G 分片 / 版本标记 ----

    @Test fun `stacking CD1 partIndex detected Godfather Part II auto`() {
        // Part II（罗马数字）+ CD1（数字分片）同时存在：parsePart 优先匹配 CD1 → partIndex=1
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Godfather.Part.II.1974.CD1.1080p.BluRay.x264-AMIABLE.mkv",
            candidates = listOf(movie(240, "The Godfather Part II", 1974)),
        )
        assertThat(parsed.title).isEqualTo("The Godfather Part II")
        assertThat(parsed.year).isEqualTo(1974)
        assertThat(parsed.partIndex).isEqualTo(1)
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(240)
    }

    @Test fun `repack version tag detected Breaking Bad auto`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Breaking.Bad.S01E01.REPACK.1080p.BluRay.x264-ROVERS.mkv",
            candidates = listOf(episode(1396, "Breaking Bad", 2008)),
        )
        assertThat(parsed.title).isEqualTo("Breaking Bad")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episodes).containsExactly(1)
        assertThat(parsed.version).isEqualTo("REPACK")
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1396)
    }

    // ---- §H Provider ID（URL 形态 / TVMaze）----

    @Test fun `tvmazeid bracket parsed`() {
        val (parsed, decision) = runEndToEnd(
            fileName = "Some.Show.S01E01.2024.[tvmazeid-123].mkv",
            candidates = listOf(episode(null, "Some Show", 2024)),
        )
        assertThat(parsed.tvmazeId).isEqualTo(123)
        assertThat(parsed.title).isEqualTo("Some Show")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.season).isEqualTo(1)
        // 标题+年份硬信号 → Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    @Test fun `imdb url form parsed`() {
        // imdb.com.title.tt1375666 URL 形态（点分隔）→ imdbId 提取
        val (parsed, decision) = runEndToEnd(
            fileName = "Inception.2010.imdb.com.title.tt1375666.mkv",
            candidates = listOf(movie(27205, "Inception", 2010)),
        )
        assertThat(parsed.imdbId).isEqualTo("tt1375666")
        // URL 形态会污染标题（imdb/com/title 非 stopword）；
        // 但单候选 + 年份 2010 完全匹配 → 单候选快捷方式 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(27205)
    }

    @Test fun `tmdb url form parsed`() {
        // themoviedb.org.movie.603 URL 形态 → tmdbId 提取
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.themoviedb.org.movie.603.mkv",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.tmdbId).isEqualTo(603)
        // URL 形态污染标题，但单候选 + 年份匹配 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
    }

    // ---- §I 多语言季关键词 ----

    @Test fun `german Staffel 2 Episode 5 multi-language auto`() {
        // Staffel（德语季关键词）+ Episode → SEASON_WORD_EP 匹配
        val (parsed, decision) = runEndToEnd(
            fileName = "Dark.Staffel.2.Episode.5.1080p.BluRay.x264.mkv",
            candidates = listOf(episode(70523, "Dark", 2017)),
        )
        assertThat(parsed.season).isEqualTo(2)
        assertThat(parsed.episodes).containsExactly(5)
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(70523)
    }

    @Test fun `french Saison 3 multi-language season only auto`() {
        // Saison（法语季关键词）无 Episode 后缀 → SEASON_WORD_ONLY 匹配 → season=3
        val (parsed, decision) = runEndToEnd(
            fileName = "Dark.Saison.3.1080p.WEB-DL.x264.mkv",
            candidates = listOf(episode(70523, "Dark", 2017)),
        )
        assertThat(parsed.season).isEqualTo(3)
        assertThat(parsed.episodes).isEmpty()
        assertThat(parsed.mediaType).isEqualTo(MediaType.EPISODE)
        // 单候选 → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- §J 字幕 / 分辨率 / 印度电影 ----

    @Test fun `chinese subtitle zh srt parseable`() {
        // .zh.srt 字幕伴随文件：subtitleInfo 解析语言标签
        val (parsed, decision) = runEndToEnd(
            fileName = "The.Matrix.1999.1080p.BluRay.x264-SPARKS.zh.srt",
            candidates = listOf(movie(603, "The Matrix", 1999)),
        )
        assertThat(parsed.title).isEqualTo("The Matrix")
        assertThat(parsed.year).isEqualTo(1999)
        assertThat(parsed.subtitleInfo).isNotNull()
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(603)
    }

    @Test fun `WxH resolution 1920x1080 detected`() {
        // WxH 分辨率形态：1920x1080 → resolution="1080p"（高度+p 等价）
        val (parsed, decision) = runEndToEnd(
            fileName = "Demo.Movie.2024.1920x1080.WEB-DL.x264.mkv",
            candidates = listOf(movie(null, "Demo Movie", 2024)),
        )
        assertThat(parsed.title).isEqualTo("Demo Movie")
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.resolution).isEqualTo("1080p")
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `RRR 2022 indian movie hard signal auto`() {
        // 印度电影 RRR；标题即 3 字母缩写
        val (parsed, decision) = runEndToEnd(
            fileName = "RRR.2022.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            candidates = listOf(movie(831486, "RRR", 2022)),
        )
        assertThat(parsed.title).isEqualTo("RRR")
        assertThat(parsed.year).isEqualTo(2022)
        assertThat(parsed.resolution).isEqualTo("2160p")
        // 硬信号 Auto(1.0)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.score).isEqualTo(1.0)
    }

    // ---- §K originalName 跨语言匹配 ----

    @Test fun `korean drama originalName alias ranks first in multi candidate`() {
        // 文件名用韩文原名 조명가게；候选 1 带 originalName="조명가게"，候选 2 不带
        // → 候选 1 在 originalName 维度命中 substringSim=1.0，分数远高于候选 2
        val (parsed, decision) = runEndToEnd(
            fileName = "조명가게.S01E01.2024.2160p.DSNP.WEB-DL.DDP5.1.Atmos.HDR10.H265.mkv",
            candidates = listOf(
                episode(1, "Light Shop", 2024, originalName = "조명가게"),
                episode(2, "Another Show", 2024, originalName = "다른 쇼"),
            ),
        )
        assertThat(parsed.year).isEqualTo(2024)
        assertThat(parsed.season).isEqualTo(1)
        // 候选 1 因 originalName 命中拿高分；候选 2 分数低 → 分差 ≥ margin → Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat(bestTmdbId(decision)).isEqualTo(1)
    }

    // ---- 辅助函数（与 MatchEngineE2ETest 同结构，保持本文件独立可运行）----

    private fun runEndToEnd(
        fileName: String,
        candidates: List<MatchCandidate>,
    ): Pair<ParsedFilename, MatchDecision> {
        val parsed = parser.parse(fileName)
        val decision = engine.match(parsed, candidates)
        return parsed to decision
    }

    private fun movie(tmdbId: Int?, name: String, year: Int): MatchCandidate =
        MatchCandidate(tmdbId = tmdbId ?: nextSyntheticId(), name = name, year = year, mediaType = MediaType.MOVIE)

    private fun episode(tmdbId: Int?, name: String, year: Int, originalName: String? = null): MatchCandidate =
        MatchCandidate(
            tmdbId = tmdbId ?: nextSyntheticId(),
            name = name,
            year = year,
            mediaType = MediaType.EPISODE,
            originalName = originalName,
        )

    private var syntheticIdCounter = 0
    private fun nextSyntheticId(): Int = --syntheticIdCounter

    private fun bestTmdbId(decision: MatchDecision): Int? = when (decision) {
        is MatchDecision.Auto -> decision.best.candidate.tmdbId
        is MatchDecision.NeedsConfirm -> decision.candidates.firstOrNull()?.candidate?.tmdbId
        MatchDecision.NoMatch -> null
    }
}
