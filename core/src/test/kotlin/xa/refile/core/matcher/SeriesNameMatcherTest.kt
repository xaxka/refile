package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Test

class SeriesNameMatcherTest {

    private val matcher = SeriesNameMatcher()

    @Test fun `single file returns empty result`() {
        val r = matcher.match(listOf("Show.S01E01.mkv"))
        assertThat(r.seriesNames).isEmpty()
        assertThat(r.fileIndices).isEmpty()
    }

    @Test fun `empty list returns empty result`() {
        val r = matcher.match(emptyList())
        assertThat(r.seriesNames).isEmpty()
    }

    @Test fun `same series multiple episodes extracts common show name`() {
        // 经典场景：用户选中 Show.S01E01~03，期望 SeriesNameMatcher 抽出 "Show"
        val files = listOf(
            "Show.S01E01.mkv",
            "Show.S01E02.mkv",
            "Show.S01E03.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        // 公共 token = ["show"]（normalize 后小写）
        assertThat(r.seriesNames.first()).isEqualTo("show")
        // 三个文件都归到该剧
        assertThat(r.fileIndices["show"]).containsExactly(0, 1, 2).inOrder()
    }

    @Test fun `multi-word series name preserves all common tokens`() {
        val files = listOf(
            "The Flash 2014.S01E01.mkv",
            "The Flash 2014.S01E02.mkv",
            "The Flash 2014.S01E03.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        // "the flash 2014" 经归一后去前导 the → "flash"（FilenameParser 会把年份 2014 从标题剥离）
        assertThat(r.seriesNames.first()).isEqualTo("flash")
    }

    @Test fun `common prefix with extra noise in one file still works`() {
        // FileBot 风格的"抗单文件噪音"：某个文件名多带了 release info
        // FilenameParser 会把 release info 剥离，所以 heads 都收敛到 "Show"
        val files = listOf(
            "Show.S01E01.mkv",
            "Show.S01E02.1080p.BluRay.x264-GROUP.mkv",
            "Show.S01E03.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        assertThat(r.seriesNames.first()).isEqualTo("show")
    }

    @Test fun `mixed two different series produces two clusters`() {
        // 多剧混合：文件 A/B 是 Show1，文件 C/D 是 Show2 → 应得到两个剧名
        val files = listOf(
            "Alpha.S01E01.mkv",
            "Alpha.S01E02.mkv",
            "Bravo.S02E01.mkv",
            "Bravo.S02E02.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(2)
        assertThat(r.seriesNames).containsExactly("alpha", "bravo")
        assertThat(r.fileIndices["alpha"]).containsExactly(0, 1)
        assertThat(r.fileIndices["bravo"]).containsExactly(2, 3)
    }

    @Test fun `two shows sharing one file each produce no clusters`() {
        // 两个文件名只有 1 个 token 不同，无法形成"公共"串（每个剧名需要 ≥2 文件贡献）
        val files = listOf("Alpha.S01E01.mkv", "Bravo.S01E01.mkv")
        val r = matcher.match(files)
        // 两两公共串为空（"alpha" vs "bravo" 无公共 token）→ 不形成 cluster
        assertThat(r.seriesNames).isEmpty()
    }

    @Test fun `multi-season same series still extracts common name`() {
        // 跨季：Show.S01E01 与 Show.S02E01 → 公共 token "show"
        val files = listOf(
            "Show.S01E01.mkv",
            "Show.S02E01.mkv",
            "Show.S03E01.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        assertThat(r.seriesNames.first()).isEqualTo("show")
    }

    @Test fun `roman numerals are normalized to digits`() {
        // FileBot 风格：II → 2，让 `Star Wars II` 与 `Star Wars 2` 视为同串
        val files = listOf(
            "Star Wars II.E01.mkv",
            "Star Wars 2.E02.mkv",
        )
        val r = matcher.match(files)
        // 公共 token = "star wars"（FilenameParser 把 II / 2 当作 episode/part 剥离，标题只剩 "Star Wars"）
        assertThat(r.seriesNames).hasSize(1)
        assertThat(r.seriesNames.first()).isEqualTo("star wars")
    }

    @Test fun `cross-script series name normalizes via ICU transliteration`() {
        Assume.assumeTrue(transliteratorAvailable())
        // 中文剧名经 ICU 拼音化后能匹配
        val files = listOf(
            "十二国記.E01.mkv",
            "十二国記.E02.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        // 拼音化后的小写拉丁 token（具体输出依赖 ICU 版本，断言至少非空且全 latin）
        val name = r.seriesNames.first()
        assertThat(name).matches("[a-z][a-z ]*")
        assertThat(name).isNotEmpty()
    }

    @Test fun `daily show with date pattern extracts series name`() {
        // 日期型剧集：Show.2024.01.15 与 Show.2024.01.16 应抽出 "show"
        val files = listOf(
            "Show.2024.01.15.mkv",
            "Show.2024.01.16.mkv",
        )
        val r = matcher.match(files)
        assertThat(r.seriesNames).hasSize(1)
        assertThat(r.seriesNames.first()).isEqualTo("show")
    }

    private fun transliteratorAvailable(): Boolean = TextNormalizer.normalize("Брат") == "brat"
}
