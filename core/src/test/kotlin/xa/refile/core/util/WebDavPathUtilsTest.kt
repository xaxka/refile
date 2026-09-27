package xa.refile.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [WebDavPathUtils.nameFromHref] 解码单元测试（P1-1，审查报告 2026-09-25）。
 *
 * WebDAV href 是百分号编码路径，旧实现仅替换 `%20`，导致中文（`%E4%B8%AD` 等）、
 * `%2B`（+）、`%26`（&）等保持编码形态——文件列表显示 `%XX` 串且与已解码路径比较失败。
 * 验收：中文、含 `+`、`&`、空格混合命名的目录在浏览器与预览页显示与行为均正确。
 */
class WebDavPathUtilsTest {

    @Test
    fun `decodes percent-encoded chinese filename`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/%E7%94%B5%E5%BD%B1%20(2024).mkv"))
            .isEqualTo("电影 (2024).mkv")
    }

    @Test
    fun `decodes space percent 20`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/a%20b.mkv")).isEqualTo("a b.mkv")
    }

    @Test
    fun `decodes plus sign as percent 2B`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/a%2Bb.mkv")).isEqualTo("a+b.mkv")
    }

    @Test
    fun `decodes ampersand as percent 26`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/a%26b.mkv")).isEqualTo("a&b.mkv")
    }

    @Test
    fun `decodes literal plus to space following URLDecoder semantics`() {
        // URLDecoder 语义：路径中的裸 `+` 解码为空格（与 core CompanionResolver B12+B13
        // 修复一致）。规范编码的服务器会把文件名中的 `+` 编码为 %2B（见上一用例）。
        assertThat(WebDavPathUtils.nameFromHref("/dav/a+b.mkv")).isEqualTo("a b.mkv")
    }

    @Test
    fun `plain ascii name unchanged`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/Movie.mkv")).isEqualTo("Movie.mkv")
    }

    @Test
    fun `trailing slash yields directory name`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/合集/")).isEqualTo("合集")
    }

    @Test
    fun `illegal percent escape falls back to raw href`() {
        // 容错：非法转义序列（%zz）不抛异常，回退原文后再取末段
        assertThat(WebDavPathUtils.nameFromHref("/dav/%zz.mkv")).isEqualTo("%zz.mkv")
    }

    @Test
    fun `decodes chinese filename from mixed directory`() {
        assertThat(WebDavPathUtils.nameFromHref("/dav/%E4%B8%AD%E6%96%87.mkv")).isEqualTo("中文.mkv")
    }
}
