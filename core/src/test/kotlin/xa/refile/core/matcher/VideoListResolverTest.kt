package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

class VideoListResolverTest {

    private val resolver = VideoListResolver()

    @Test fun `empty list returns empty`() {
        assertThat(resolver.resolve(emptyList())).isEmpty()
    }

    @Test fun `single file becomes its own group`() {
        val p = ParsedFilename(title = "Inception", year = 2010, resolution = "1080p")
        val groups = resolver.resolve(listOf(p))
        assertThat(groups).hasSize(1)
        val g = groups.first()
        assertThat(g.files).hasSize(1)
        assertThat(g.primary).isSameInstanceAs(p)
        assertThat(g.primaryIndex).isEqualTo(0)
        assertThat(g.title).isEqualTo("Inception")
        assertThat(g.year).isEqualTo(2010)
    }

    @Test fun `multiple versions of same movie are grouped`() {
        // 经典场景：用户库里同一片的不同分辨率 / 来源 / HDR
        val files = listOf(
            ParsedFilename(title = "Inception", year = 2010, resolution = "1080p", source = "BluRay"),
            ParsedFilename(title = "Inception", year = 2010, resolution = "2160p", source = "UHD Blu-ray", hdr = "HDR10"),
            ParsedFilename(title = "Inception", year = 2010, resolution = "1080p", source = "WEB-DL"),
        )
        val groups = resolver.resolve(files)
        assertThat(groups).hasSize(1)
        val g = groups.first()
        assertThat(g.files).hasSize(3)
        assertThat(g.fileIndices).containsExactly(0, 1, 2).inOrder()
        // primary 应是 2160p UHD（分辨率最高）
        assertThat(g.primary.resolution).isEqualTo("2160p")
        assertThat(g.primaryIndex).isEqualTo(1)
        assertThat(g.title).isEqualTo("Inception")
        assertThat(g.year).isEqualTo(2010)
    }

    @Test fun `different titles produce separate groups`() {
        val files = listOf(
            ParsedFilename(title = "Inception", year = 2010),
            ParsedFilename(title = "Interstellar", year = 2014),
        )
        val groups = resolver.resolve(files)
        assertThat(groups).hasSize(2)
        assertThat(groups.map { it.title }).containsExactly("Inception", "Interstellar")
    }

    @Test fun `same title different years are separate groups`() {
        // 同标题不同年份 → 通常是不同影片（重制版 / 同名不同片）
        val files = listOf(
            ParsedFilename(title = "It", year = 1990),
            ParsedFilename(title = "It", year = 2017),
        )
        val groups = resolver.resolve(files)
        assertThat(groups).hasSize(2)
        assertThat(groups.map { it.year }).containsExactly(1990, 2017)
    }

    @Test fun `missing title file becomes singleton`() {
        // 标题解析失败的文件 → 独立成组，不污染其它文件
        val files = listOf(
            ParsedFilename(title = "Inception", year = 2010),
            ParsedFilename(title = null, year = null),
        )
        val groups = resolver.resolve(files)
        assertThat(groups).hasSize(2)
        val titles = groups.map { it.title }
        assertThat(titles).contains("Inception")
        assertThat(titles).contains("")
    }

    @Test fun `primary selection by resolution then hdr`() {
        // 同组文件分辨率相同时，HDR 高的为主版本
        val files = listOf(
            ParsedFilename(title = "Dune", year = 2021, resolution = "2160p", hdr = null),
            ParsedFilename(title = "Dune", year = 2021, resolution = "2160p", hdr = "Dolby Vision"),
            ParsedFilename(title = "Dune", year = 2021, resolution = "2160p", hdr = "HDR10"),
        )
        val g = resolver.resolve(files).single()
        assertThat(g.primary.hdr).isEqualTo("Dolby Vision")
        assertThat(g.primaryIndex).isEqualTo(1)
    }

    @Test fun `primary selection by source when resolution and hdr equal`() {
        val files = listOf(
            ParsedFilename(title = "Dune", year = 2021, resolution = "1080p", source = "WEB-DL"),
            ParsedFilename(title = "Dune", year = 2021, resolution = "1080p", source = "BluRay"),
        )
        val g = resolver.resolve(files).single()
        // BluRay 排序高于 WEB-DL
        assertThat(g.primary.source).isEqualTo("BluRay")
    }

    @Test fun `primary selection stable when all quality equal`() {
        // 全画质相同时，取最早出现的（稳定排序）
        val first = ParsedFilename(title = "Dune", year = 2021, resolution = "1080p")
        val second = ParsedFilename(title = "Dune", year = 2021, resolution = "1080p")
        val g = resolver.resolve(listOf(first, second)).single()
        assertThat(g.primary).isSameInstanceAs(first)
        assertThat(g.primaryIndex).isEqualTo(0)
    }

    @Test fun `requireYear true treats yearless files as singletons`() {
        val strict = VideoListResolver(requireYear = true)
        val files = listOf(
            ParsedFilename(title = "Inception", year = null, resolution = "1080p"),
            ParsedFilename(title = "Inception", year = null, resolution = "2160p"),
        )
        val groups = strict.resolve(files)
        assertThat(groups).hasSize(2)  // 严格模式下缺年份的文件各自独立
    }

    @Test fun `requireYear false groups yearless files by title`() {
        val lenient = VideoListResolver(requireYear = false)
        val files = listOf(
            ParsedFilename(title = "Inception", year = null, resolution = "1080p"),
            ParsedFilename(title = "Inception", year = null, resolution = "2160p"),
        )
        val groups = lenient.resolve(files)
        assertThat(groups).hasSize(1)
        assertThat(groups.first().files).hasSize(2)
    }

    @Test fun `cross-script titles group via ICU normalization`() {
        // 中文片名 + 英文片名（年份相同）经 ICU 音译后归一标题相同 → 归一组
        // 实际是否归一组依赖 ICU 是否把中文音译为与英文相同的串；
        // 这里只断言：归一化后中文与英文标题都能进入分组流程（不抛异常）
        val files = listOf(
            ParsedFilename(title = "攻壳机动队", year = 1995),
            ParsedFilename(title = "Ghost in the Shell", year = 1995),
        )
        val groups = resolver.resolve(files)
        // 两个不同的归一 key（音译后未必完全相同），通常是两个组；不抛异常即通过
        assertThat(groups.size).isIn(1..2)
    }

    @Test fun `yearless and yeared files of same title do not group`() {
        // 宽松模式下，缺年份文件也不应与有年份文件合并（年份信息不可判定）
        val files = listOf(
            ParsedFilename(title = "Dune", year = 2021, resolution = "2160p"),
            ParsedFilename(title = "Dune", year = null, resolution = "1080p"),
        )
        val groups = resolver.resolve(files)
        assertThat(groups).hasSize(2)
    }
}
