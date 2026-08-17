package xa.refile.core.parser

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import org.junit.Test

class FilenameParserTest {

    private val parser = FilenameParser()

    // ---- §5.3 季集模式表 ----

    @Test fun `SxxExx basic`() {
        val r = parser.parse("The.Last.of.Us.S01E02.1080p.WEB-DL.x264-GROUP.mkv")
        assertThat(r.title).isEqualTo("The Last of Us")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
        assertThat(r.resolution).isEqualTo("1080p")
        assertThat(r.source).isEqualTo("WEB-DL")
        assertThat(r.videoCodec).isEqualTo("x264")
        assertThat(r.group).isEqualTo("GROUP")
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `S1E2 lowercase`() {
        val r = parser.parse("Show.s1e2.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `S01E01E02 multi-episode`() {
        val r = parser.parse("The.Last.of.Us.S01E01E02.1080p.WEB-DL.x264-GROUP.mkv")
        assertThat(r.title).isEqualTo("The Last of Us")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1, 2)
        assertThat(r.isMultiEpisode).isTrue()
    }

    @Test fun `S01E01-E03 range`() {
        val r = parser.parse("Firefly.S01E01-E03.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1, 2, 3).inOrder()
    }

    @Test fun `NxN pattern`() {
        val r = parser.parse("Firefly.1x02.Serenity.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `Chinese season and episode`() {
        val r = parser.parse("某剧.第1季第2集.mkv")
        assertThat(r.title).isEqualTo("某剧")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `Chinese episode only`() {
        val r = parser.parse("某剧.第02集.mkv")
        assertThat(r.season).isNull()
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `standalone E02`() {
        val r = parser.parse("SomeShow.E02.mkv")
        assertThat(r.episodes).containsExactly(2)
        // P3 修复（报告 #15）：与 第X集 / [02] / Episode 16 一致，标记为绝对集号
        assertThat(r.isAbsoluteEpisode).isTrue()
    }

    @Test fun `standalone EP02`() {
        val r = parser.parse("SomeShow.EP02.mkv")
        assertThat(r.episodes).containsExactly(2)
        assertThat(r.isAbsoluteEpisode).isTrue()
    }

    @Test fun `bracket episode 02`() {
        val r = parser.parse("SomeShow.[02].mkv")
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `daily show date pattern`() {
        val r = parser.parse("The.Daily.Show.2024.01.15.1080p.WEB.mkv")
        assertThat(r.isDailyShow).isTrue()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `daily show dashed date`() {
        val r = parser.parse("DailyShow.2024-01-15.mkv")
        assertThat(r.isDailyShow).isTrue()
    }

    // ---- 清洗与标题 ----

    @Test fun `underscores replaced with spaces`() {
        val r = parser.parse("Some_Movie_2023_1080p.mkv")
        assertThat(r.title).isEqualTo("Some Movie")
        assertThat(r.year).isEqualTo(2023)
    }

    @Test fun `dots replaced with spaces`() {
        val r = parser.parse("Some.Movie.2023.1080p.mkv")
        assertThat(r.title).isEqualTo("Some Movie")
        assertThat(r.year).isEqualTo(2023)
    }

    @Test fun `year in parens preserved for year extraction but stripped from title`() {
        val r = parser.parse("The Matrix (1999).mkv")
        assertThat(r.year).isEqualTo(1999)
        assertThat(r.title).isEqualTo("The Matrix")
    }

    @Test fun `brackets release info stripped`() {
        val r = parser.parse("[Group] Movie Title [1080p].mkv")
        assertThat(r.title).isEqualTo("Movie Title")
    }

    @Test fun `tech tail stripped`() {
        val r = parser.parse("Avatar 2009 720p BluRay x264 AAC-Group.mkv")
        assertThat(r.title).isEqualTo("Avatar")
        assertThat(r.year).isEqualTo(2009)
        assertThat(r.resolution).isEqualTo("720p")
        assertThat(r.source).isEqualTo("BluRay")
        assertThat(r.videoCodec).isEqualTo("x264")
        assertThat(r.audioCodec).isEqualTo("aac")
        assertThat(r.group).isEqualTo("Group")
    }

    @Test fun `no year movie`() {
        val r = parser.parse("SomeObscureMovie.mkv")
        assertThat(r.title).isEqualTo("SomeObscureMovie")
        assertThat(r.year).isNull()
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }

    @Test fun `multi-year filename takes last year as release year`() {
        // 1994 是原作/片中年份，2026 是发行版年份；应取 2026 用于 TMDB 搜索，
        // 否则用 1994 搜 2026 年的电影会搜不到。
        val r = parser.parse("Cold.War.1994.2026.2160p.WEB-DL.mkv")
        assertThat(r.year).isEqualTo(2026)
        assertThat(r.season).isNull()
        assertThat(r.episodes).isEmpty()
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
        assertThat(r.resolution).isEqualTo("2160p")
        assertThat(r.source).isEqualTo("WEB-DL")
    }

    @Test fun `Chinese title with tech`() {
        val r = parser.parse("流浪地球2.2023.2160p.WEB-DL.x265.mkv")
        assertThat(r.title).isEqualTo("流浪地球2")
        assertThat(r.year).isEqualTo(2023)
        assertThat(r.resolution).isEqualTo("2160p")
        assertThat(r.videoCodec).isEqualTo("x265")
    }

    // ---- 技术标签归一化 ----

    @Test fun `BluRay source variants`() {
        assertThat(parser.parse("Movie.BluRay.1080p.mkv").source).isEqualTo("BluRay")
        assertThat(parser.parse("Movie.Blu-ray.1080p.mkv").source).isEqualTo("BluRay")
        assertThat(parser.parse("Movie.BRRip.mkv").source).isEqualTo("BluRay")
        assertThat(parser.parse("Movie.BDRip.mkv").source).isEqualTo("BluRay")
    }

    @Test fun `WEB-DL source`() {
        assertThat(parser.parse("Movie.WEB-DL.1080p.mkv").source).isEqualTo("WEB-DL")
        assertThat(parser.parse("Movie.WEBDL.1080p.mkv").source).isEqualTo("WEB-DL")
    }

    @Test fun `WEBRip source`() {
        assertThat(parser.parse("Movie.WEBRip.1080p.mkv").source).isEqualTo("WEBRip")
    }

    @Test fun `HDTV source`() {
        assertThat(parser.parse("Movie.HDTV.1080p.mkv").source).isEqualTo("HDTV")
    }

    @Test fun `DVDRip source`() {
        assertThat(parser.parse("Movie.DVDRip.mkv").source).isEqualTo("DVDRip")
    }

    @Test fun `Remux source`() {
        assertThat(parser.parse("Movie.Remux.2160p.mkv").source).isEqualTo("Remux")
    }

    @Test fun `resolution 4K`() {
        assertThat(parser.parse("Movie.4K.mkv").resolution).isEqualTo("4k")
    }

    @Test fun `video codecs`() {
        assertThat(parser.parse("Movie.x264.mkv").videoCodec).isEqualTo("x264")
        assertThat(parser.parse("Movie.x265.mkv").videoCodec).isEqualTo("x265")
        assertThat(parser.parse("Movie.HEVC.mkv").videoCodec).isEqualTo("hevc")
        assertThat(parser.parse("Movie.AV1.mkv").videoCodec).isEqualTo("av1")
    }

    @Test fun `audio codecs`() {
        assertThat(parser.parse("Movie.AAC.mkv").audioCodec).isEqualTo("aac")
        assertThat(parser.parse("Movie.AC3.mkv").audioCodec).isEqualTo("ac3")
        assertThat(parser.parse("Movie.DTS.mkv").audioCodec).isEqualTo("dts")
        assertThat(parser.parse("Movie.Atmos.mkv").audioCodec).isEqualTo("atmos")
    }

    // ---- 扩展名与伴随文件 ----

    @Test fun `extension split`() {
        assertThat(parser.splitExtension("a.b.mkv")).isEqualTo("a.b" to "mkv")
        assertThat(parser.splitExtension("noext")).isEqualTo("noext" to "")
        assertThat(parser.splitExtension("hidden.dot")).isEqualTo("hidden" to "dot")
        // strmlnk 为 7 字符，处于 splitExtension 长度上限边界，必须能正常切分。
        assertThat(parser.splitExtension("Movie.strmlnk")).isEqualTo("Movie" to "strmlnk")
    }

    @Test fun `video extensions recognized`() {
        listOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb", "iso",
            "mk3d", "mks", "mka", "ogm", "strm", "strmlnk",
        ).forEach { assertThat(parser.videoExtensions).contains(it) }
    }

    @Test fun `subtitle extensions recognized`() {
        listOf("srt", "ass", "ssa", "sub", "idx", "sup", "vtt", "smi").forEach {
            assertThat(parser.subtitleExtensions).contains(it)
        }
    }

    // ---- 多分片 ----

    @Test fun `part cd1`() {
        val r = parser.parse("Movie.CD1.1080p.mkv")
        assertThat(r.partIndex).isEqualTo(1)
    }

    @Test fun `part disc2`() {
        val r = parser.parse("Movie.Disc2.mkv")
        assertThat(r.partIndex).isEqualTo(2)
    }

    // ---- 边界 ----

    @Test fun `apollo 13 not misclassified as episode`() {
        val r = parser.parse("Apollo 13 1995 1080p.mkv")
        assertThat(r.episodes).isEmpty()
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }

    @Test fun `empty filename`() {
        val r = parser.parse("")
        assertThat(r.title).isNull()
    }

    @Test fun `no extension`() {
        val r = parser.parse("SomeShow S01E01")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1)
    }

    @Test fun `preserves chinese title with season`() {
        val r = parser.parse("进击的巨人.第3季第12集.1080p.mkv")
        assertThat(r.title).isEqualTo("进击的巨人")
        assertThat(r.season).isEqualTo(3)
        assertThat(r.episodes).containsExactly(12)
    }

    @Test fun `combined group in dash and brackets`() {
        val r = parser.parse("Show.S01E01.1080p.WEB-DL.x264-RG.mkv")
        assertThat(r.group).isEqualTo("RG")
    }

    @Test fun `multi episode three eps`() {
        val r = parser.parse("Show.S01E01E02E03.mkv")
        assertThat(r.episodes).containsExactly(1, 2, 3).inOrder()
    }

    // ---- §5.3 增强：绝对集号 / 中文数字 / 版本标签 / DAILY-YEAR 修复 ----

    @Test fun `absolute episode trailing number`() {
        val r = parser.parse("Show 12.mkv")
        assertThat(r.episodes).containsExactly(12)
        assertThat(r.isAbsoluteEpisode).isTrue()
        assertThat(r.season).isNull()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `absolute episode in brackets`() {
        val r = parser.parse("[12] Show.mkv")
        assertThat(r.episodes).containsExactly(12)
        assertThat(r.isAbsoluteEpisode).isTrue()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `absolute episode dash separator`() {
        val r = parser.parse("Show-12.mkv")
        assertThat(r.episodes).containsExactly(12)
        assertThat(r.isAbsoluteEpisode).isTrue()
    }

    @Test fun `absolute episode underscore separator`() {
        val r = parser.parse("Show_12.mkv")
        assertThat(r.episodes).containsExactly(12)
        assertThat(r.isAbsoluteEpisode).isTrue()
    }

    @Test fun `absolute episode range without E prefix`() {
        val r = parser.parse("Show 01-03.mkv")
        assertThat(r.episodes).containsExactly(1, 2, 3).inOrder()
        assertThat(r.isAbsoluteEpisode).isTrue()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `absolute episode not applied when year present`() {
        // 含年份时不再尝试绝对集号，避免误伤 "Apollo 13 1995" 这类电影
        val r = parser.parse("Apollo 13 1995 1080p.mkv")
        assertThat(r.episodes).isEmpty()
        assertThat(r.isAbsoluteEpisode).isFalse()
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }

    @Test fun `absolute episode not applied when leading number`() {
        // 数字位于开头很可能是标题的一部分（如 "12 Monkeys"），不识别为集号
        val r = parser.parse("12 Monkeys.mkv")
        assertThat(r.episodes).isEmpty()
        assertThat(r.isAbsoluteEpisode).isFalse()
    }

    // P2 修复（报告 #11）：空格分隔 + 无补零 + 单个 1 位数 → 电影标题尾数，不是绝对集号。
    @Test fun `single unpadded digit after space is title not episode`() {
        val super8 = parser.parse("Super 8.mkv")
        assertThat(super8.episodes).isEmpty()
        assertThat(super8.isAbsoluteEpisode).isFalse()
        assertThat(super8.mediaType).isEqualTo(MediaType.MOVIE)

        val district9 = parser.parse("District 9.mkv")
        assertThat(district9.episodes).isEmpty()
        assertThat(district9.mediaType).isEqualTo(MediaType.MOVIE)
    }

    // 对照：补零（"08"）与 -/_ 分隔的单个 1 位数仍是绝对集号（动画命名惯例）。
    @Test fun `padded or separator attached single digit is absolute episode`() {
        val padded = parser.parse("Show 08.mkv")
        assertThat(padded.episodes).containsExactly(8)
        assertThat(padded.isAbsoluteEpisode).isTrue()

        val dashed = parser.parse("Show-8.mkv")
        assertThat(dashed.episodes).containsExactly(8)
        assertThat(dashed.isAbsoluteEpisode).isTrue()
    }

    @Test fun `chinese number season and episode`() {
        val r = parser.parse("某剧.第一季第二集.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
    }

    @Test fun `chinese number episode only with alias`() {
        val r = parser.parse("某剧.第拾贰话.mkv")
        assertThat(r.season).isNull()
        assertThat(r.episodes).containsExactly(12)
    }

    @Test fun `chinese number season only`() {
        val r = parser.parse("某剧.第二季.mkv")
        assertThat(r.season).isEqualTo(2)
        assertThat(r.episodes).isEmpty()
    }

    @Test fun `chinese episode alias hua zhang hui pian`() {
        assertThat(parser.parse("剧.第3话.mkv").episodes).containsExactly(3)
        assertThat(parser.parse("剧.第4章.mkv").episodes).containsExactly(4)
        assertThat(parser.parse("剧.第5回.mkv").episodes).containsExactly(5)
        assertThat(parser.parse("剧.第6篇.mkv").episodes).containsExactly(6)
    }

    @Test fun `chinese number combinations`() {
        // 二十三=23, 三十=30, 一百零一=101
        assertThat(parser.parse("剧.第二十三话.mkv").episodes).containsExactly(23)
        assertThat(parser.parse("剧.第三十话.mkv").episodes).containsExactly(30)
        assertThat(parser.parse("剧.第一百零一话.mkv").episodes).containsExactly(101)
    }

    @Test fun `season range S01-S03`() {
        val r = parser.parse("Show.S01-S03.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `version tag v2`() {
        val r = parser.parse("Show v2.mkv")
        assertThat(r.version).isEqualTo("v2")
    }

    @Test fun `version tag repack proper`() {
        assertThat(parser.parse("Show.S01E02.Repack.mkv").version).isEqualTo("Repack")
        assertThat(parser.parse("Show.S01E02.Proper.mkv").version).isEqualTo("Proper")
    }

    @Test fun `version tag not matched at title start`() {
        // "Final" 位于开头应视为标题，不识别为版本标签
        val r = parser.parse("Final Destination 2009.mkv")
        assertThat(r.version).isNull()
    }

    @Test fun `daily show year not misextracted`() {
        val r = parser.parse("2024.01.15 Show.mkv")
        assertThat(r.isDailyShow).isTrue()
        assertThat(r.year).isNull()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `daily show dashed year not misextracted`() {
        val r = parser.parse("DailyShow.2024-01-15.mkv")
        assertThat(r.isDailyShow).isTrue()
        assertThat(r.year).isNull()
    }

    // ---- 集名/副标题分隔（` - `）----

    @Test fun `episode title after dash separator stripped`() {
        // `雀骨(2026)S01E01 - 无衣嘉鱼的混乱初遇`：` - ` 后是集名/副标题，
        // 应只保留主标识 `雀骨 2026 S01E01`，标题为 `雀骨`。
        val r = parser.parse("雀骨(2026)S01E01 - 无衣嘉鱼的混乱初遇.mkv")
        assertThat(r.title).isEqualTo("雀骨")
        assertThat(r.year).isEqualTo(2026)
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1)
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    @Test fun `episode title dash separator without extension`() {
        val r = parser.parse("雀骨(2026)S01E01 - 无衣嘉鱼的混乱初遇")
        assertThat(r.title).isEqualTo("雀骨")
        assertThat(r.year).isEqualTo(2026)
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1)
    }

    @Test fun `hyphen without spaces not treated as separator`() {
        // `X-Men` 这类连字符无空格，不应被当作集名分隔符拆分（标题完整保留 X 和 Men，不会只剩 X）。
        // 连字符在词表清洗阶段保留（不拆分），故最终标题为 `X-Men`（与 Spider-Man 一致）。
        val r = parser.parse("X-Men.2000.1080p.mkv")
        assertThat(r.title).isEqualTo("X-Men")
        assertThat(r.year).isEqualTo(2000)
    }

    // ---- P0.1 词表清洗 ----

    @Test fun `stopword cleaning keeps leading 4K in title`() {
        // 4K 出现在标题中段而非尾部，不应被尾部停用词扫描截断
        val r = parser.parse("The.4K.Restoration.Movie.2009.mkv")
        assertThat(r.title).isEqualTo("The 4K Restoration Movie")
        assertThat(r.year).isEqualTo(2009)
    }

    @Test fun `multi year movie with h265 dts5 dot 1 tech tail cleans to title and last year`() {
        val r = parser.parse("Cold.War.1994.2026.2160p.YK.WEB-DL.H.265.DV.HQ.DTS5.1-PandaQT.mkv")
        assertThat(r.title).isEqualTo("Cold War")
        assertThat(r.year).isEqualTo(2026)
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
        assertThat(r.resolution).isEqualTo("2160p")
        assertThat(r.source).isEqualTo("WEB-DL")
    }

    @Test fun `chinese english mixed title with h265 dts tech tail cleans correctly`() {
        // `寒战1994` 中 1994 紧跟汉字，视为标题一部分不剥离；`Cold.War.1994` 中点分隔的 1994
        // 仍识别为年份并被剥离；year 取最后一个有效年份 2026。
        // 中英混合标题按 CJK/Latin 边界拆分：title="寒战1994"，aliases=["Cold War"]，
        // 匹配器分别搜两段再合并候选。
        val r = parser.parse("寒战1994.Cold.War.1994.2026.2160p.HQ.WEB-DL.H265.HDR.DTS-QuickIO.mkv")
        assertThat(r.title).isEqualTo("寒战1994")
        assertThat(r.titleAliases).containsExactly("Cold War")
        assertThat(r.year).isEqualTo(2026)
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }

    // ---- P0.2 多集区间上限保护 ----

    @Test fun `episode range over limit keeps only start`() {
        // 区间跨度 > 5（MAX_EPISODE_RANGE），只保留起始集号
        val r = parser.parse("Show.S01E01-E99.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1)
    }

    @Test fun `episode range at boundary still expands`() {
        // 边界：跨度 = 5（=MAX_EPISODE_RANGE），仍展开
        val r = parser.parse("Show.S01E01-E05.mkv")
        assertThat(r.episodes).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    // ---- P0.3 多集尾号字符保护 ----

    @Test fun `episode range with resolution suffix not expanded`() {
        // s09e14-1080p：1080p 分辨率不应被误判为 E14-E108 区间
        val r = parser.parse("Show.s09e14-1080p.mkv")
        assertThat(r.season).isEqualTo(9)
        assertThat(r.episodes).containsExactly(14)
    }

    @Test fun `episode range followed by 720p not corrupted`() {
        // S01E01-E03.720p：720p 不应被吃进区间
        val r = parser.parse("Show.S01E01-E03.720p.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1, 2, 3).inOrder()
    }

    // ---- P0.4 季集号 sanity 上限 ----

    @Test fun `season over limit discarded`() {
        // S99：季号 99 > MAX_SEASON(50) → 季号丢弃；集号 1 保留
        val r = parser.parse("Show.S99E01.mkv")
        assertThat(r.season).isNull()
        assertThat(r.episodes).containsExactly(1)
    }

    @Test fun `episode over limit no longer discarded`() {
        // 集号不再设上限：1000 集（One Piece 等长篇动画）保留，标记 isAbsolute
        val r = parser.parse("Show.S01E1000.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(1000)
        assertThat(r.isAbsoluteEpisode).isTrue()
    }

    @Test fun `episode 51 preserved for long season`() {
        // E51：国产剧常见 50+ 集/季，集号不再设上限 → 保留
        val r = parser.parse("Show.S01E51.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(51)
    }

    @Test fun `standalone episode 51 preserved`() {
        // E051 无季号：STANDALONE_EP 匹配，集号不再设上限 → 保留
        val r = parser.parse("丹道至尊.E051.mp4")
        assertThat(r.season).isNull()
        assertThat(r.episodes).containsExactly(51)
    }

    // ---- P1.1 Edition 标签 ----

    @Test fun `edition directors cut`() {
        val r = parser.parse("Movie.2009.Directors.Cut.1080p.mkv")
        assertThat(r.edition).isEqualTo("Director's Cut")
    }

    @Test fun `edition imax`() {
        val r = parser.parse("Movie.2009.IMAX.mkv")
        assertThat(r.edition).isEqualTo("IMAX")
    }

    @Test fun `edition extended`() {
        val r = parser.parse("Movie.Extended.Edition.2009.mkv")
        assertThat(r.edition).isEqualTo("Extended")
    }

    // ---- P1.2 HDR / 3D 标签 ----

    @Test fun `hdr10 plus detected`() {
        val r = parser.parse("Movie.2023.2160p.UHD.HDR10+.mkv")
        assertThat(r.hdr).isEqualTo("HDR10+")
    }

    @Test fun `dolby vision detected`() {
        val r = parser.parse("Movie.2023.2160p.UHD.DV.mkv")
        assertThat(r.hdr).isEqualTo("Dolby Vision")
    }

    @Test fun `three d sbs detected`() {
        val r = parser.parse("Movie.2010.3D.SBS.mkv")
        assertThat(r.threeD).isEqualTo("3D SBS")
    }

    // ---- P1.3 Anime CRC 方括号命名 ----

    @Test fun `anime crc bracket naming`() {
        val r = parser.parse("[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv")
        assertThat(r.title).isEqualTo("Series Name")
        assertThat(r.episodes).containsExactly(12)
        assertThat(r.isAbsoluteEpisode).isTrue()
        assertThat(r.group).isEqualTo("Group")
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }

    // ---- P1.4 Stacking 扩展 ----

    @Test fun `part letter CDa`() {
        val r = parser.parse("Movie.CDa.mkv")
        assertThat(r.partIndex).isEqualTo(1)
    }

    @Test fun `part of N of M`() {
        val r = parser.parse("Movie.1of2.mkv")
        assertThat(r.partIndex).isEqualTo(1)
    }

    // ---- P1.5 IMDb ID 提取 ----

    @Test fun `imdb id plain`() {
        val r = parser.parse("Movie.tt1234567.mkv")
        assertThat(r.imdbId).isEqualTo("tt1234567")
    }

    @Test fun `imdb id in brackets`() {
        val r = parser.parse("Movie.[tt1234567].mkv")
        assertThat(r.imdbId).isEqualTo("tt1234567")
    }

    // ---- P1.6 流媒体来源 ----

    @Test fun `streaming source amzn`() {
        val r = parser.parse("Movie.2023.2160p.WEB-DL.DDP5.1.Atmos.HDR10+.AMZN.mkv")
        assertThat(r.streamingSource).isEqualTo("Amazon")
    }

    @Test fun `streaming source nf`() {
        val r = parser.parse("Show.S01E01.2160p.NF.WEB-DL.mkv")
        assertThat(r.streamingSource).isEqualTo("Netflix")
    }

    // ---- P1.7 字幕语言标签 ----

    @Test fun `subtitle forced zh`() {
        val r = parser.parse("Movie.zh.forced.srt")
        assertThat(r.subtitleInfo).isNotNull()
        assertThat(r.subtitleInfo!!.language).isEqualTo("zh")
        assertThat(r.subtitleInfo!!.forced).isTrue()
    }

    @Test fun `subtitle plain en`() {
        val r = parser.parse("Movie.en.srt")
        assertThat(r.subtitleInfo).isNotNull()
        assertThat(r.subtitleInfo!!.language).isEqualTo("en")
        assertThat(r.subtitleInfo!!.forced).isFalse()
    }

    // ---- P2.5 Extras 识别 ----

    @Test fun `extra type trailer`() {
        val r = parser.parse("Movie.2009.Trailer.mkv")
        assertThat(r.extraType).isEqualTo(ExtraType.TRAILER)
    }

    @Test fun `extra type sample`() {
        val r = parser.parse("Movie.Sample.mkv")
        assertThat(r.extraType).isEqualTo(ExtraType.SAMPLE)
    }

    // ---- P3.0 Provider ID ----
    @Test fun `tmdb id from bracket`() {
        val r = parser.parse("Movie [tmdbid-12345].mkv")
        assertThat(r.tmdbId).isEqualTo(12345)
    }
    @Test fun `tmdb id from url`() {
        val r = parser.parse("Movie.themoviedb.org-movie-123.mkv")
        assertThat(r.tmdbId).isEqualTo(123)
    }
    @Test fun `tvdb id from bracket`() {
        val r = parser.parse("Show [tvdbid-12345].mkv")
        assertThat(r.tvdbId).isEqualTo(12345)
    }
    @Test fun `imdb id from url`() {
        val r = parser.parse("Show.imdb.com-title-tt0123456.mkv")
        assertThat(r.imdbId).isEqualTo("tt0123456")
    }
    @Test fun `imdb id six digits`() {
        val r = parser.parse("Movie tt012345.mkv")
        assertThat(r.imdbId).isEqualTo("tt012345")
    }

    // ---- P3.0 Season/Episode 字面词 ----
    @Test fun `Season X Episode Y literal`() {
        val r = parser.parse("Show Season 1 Episode 2.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
    }
    @Test fun `Episode N standalone`() {
        val r = parser.parse("Show Episode 16.mkv")
        assertThat(r.episodes).containsExactly(16)
    }
    @Test fun `Episode N range`() {
        val r = parser.parse("Show Episode 16-20.mkv")
        assertThat(r.episodes).containsExactly(16, 17, 18, 19, 20).inOrder()
    }
    @Test fun `Staffel X Episode Y`() {
        val r = parser.parse("Show Staffel 2 Episode 5.mkv")
        assertThat(r.season).isEqualTo(2)
        assertThat(r.episodes).containsExactly(5)
    }
    @Test fun `Saison X only`() {
        val r = parser.parse("Show Saison 3.mkv")
        assertThat(r.season).isEqualTo(3)
        assertThat(r.episodes).isEmpty()
    }

    // ---- P3.0 Episode 字母后缀 ----
    @Test fun `S01E02a letter suffix`() {
        val r = parser.parse("Show S01E02a.mkv")
        assertThat(r.season).isEqualTo(1)
        assertThat(r.episodes).containsExactly(2)
        assertThat(r.episodeSubPart).isEqualTo("a")
    }
    @Test fun `1x02b letter suffix`() {
        val r = parser.parse("Show 1x02b.mkv")
        assertThat(r.episodes).containsExactly(2)
        assertThat(r.episodeSubPart).isEqualTo("b")
    }
    @Test fun `S01E02 no suffix`() {
        val r = parser.parse("Show S01E02.mkv")
        assertThat(r.episodeSubPart).isNull()
    }

    // ---- P3.0 Anime Special/OVA ----
    @Test fun `OVA detected as season 0`() {
        val r = parser.parse("Show OVA.mkv")
        assertThat(r.season).isEqualTo(0)
    }
    @Test fun `Special in bracket`() {
        val r = parser.parse("[Group] Show - [Special].mkv")
        assertThat(r.season).isEqualTo(0)
    }

    // ---- P3.0 日期与分辨率 ----
    @Test fun `daily show EU date`() {
        val r = parser.parse("Show 15.01.2024.mkv")
        assertThat(r.isDailyShow).isTrue()
    }
    @Test fun `WxH resolution`() {
        val r = parser.parse("Movie 1920x1080.mkv")
        assertThat(r.resolution).isEqualTo("1080p")
    }
    @Test fun `WxH does not override height+p`() {
        val r = parser.parse("Movie 1080p 1920x1080.mkv")
        assertThat(r.resolution).isEqualTo("1080p")
    }
    @Test fun `Part Roman II`() {
        val r = parser.parse("Movie Part II.mkv")
        assertThat(r.partIndex).isEqualTo(2)
    }
    @Test fun `Pt IV roman`() {
        val r = parser.parse("Movie Pt IV.mkv")
        assertThat(r.partIndex).isEqualTo(4)
    }

    // ---- P3.0 全括号命名兜底 ----
    @Test fun `all bracket naming with hyphen in group`() {
        val r = parser.parse("[GM-Team][国漫][光阴之外][Beyond Time's Gaze].mkv")
        assertThat(r.title).isEqualTo("光阴之外")
        assertThat(r.titleAliases).containsExactly("Beyond Time's Gaze")
        assertThat(r.group).isEqualTo("GM-Team")
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }
    @Test fun `all bracket naming with tech tags`() {
        val r = parser.parse("[SubsPlease][CHS][JP][One Piece][1000][1080p][HEVC].mkv")
        assertThat(r.title).isEqualTo("One Piece")
        assertThat(r.episodes).containsExactly(1000)
        assertThat(r.isAbsoluteEpisode).isTrue()
        assertThat(r.mediaType).isEqualTo(MediaType.EPISODE)
    }
    @Test fun `all bracket naming without episode`() {
        val r = parser.parse("[LoliHouse][简繁内封][天气之子][Weathering With You][BDRip].mkv")
        assertThat(r.title).isEqualTo("天气之子")
        assertThat(r.titleAliases).containsExactly("Weathering With You")
        assertThat(r.group).isEqualTo("LoliHouse")
        assertThat(r.mediaType).isEqualTo(MediaType.MOVIE)
    }
}
