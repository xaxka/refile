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
    }

    @Test fun `standalone EP02`() {
        val r = parser.parse("SomeShow.EP02.mkv")
        assertThat(r.episodes).containsExactly(2)
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
    }

    @Test fun `video extensions recognized`() {
        listOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb", "iso")
            .forEach { assertThat(parser.videoExtensions).contains(it) }
    }

    @Test fun `subtitle extensions recognized`() {
        listOf("srt", "ass", "ssa", "sub", "idx").forEach {
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
}
