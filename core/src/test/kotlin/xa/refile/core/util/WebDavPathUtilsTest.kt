package xa.refile.core.util

import com.google.common.truth.Truth.assertThat
import xa.refile.core.webdav.WebDavEntry
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

    // ---- P1-2（审查报告 2026-09-25）：excludeSelfEntry 按 href 剔除目录自身 ----

    private fun entry(href: String, name: String? = null, isCollection: Boolean = false) =
        WebDavEntry(href = href, displayName = name, isCollection = isCollection)

    @Test
    fun `excludeSelfEntry with self first keeps all children`() {
        val entries = listOf(
            entry("/dav", "dav", isCollection = true),
            entry("/dav/a.mkv", "a.mkv"),
            entry("/dav/b.mkv", "b.mkv"),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/dav")

        assertThat(children.map { it.displayName }).containsExactly("a.mkv", "b.mkv").inOrder()
    }

    @Test
    fun `excludeSelfEntry with self in middle keeps all children`() {
        // RFC 4918 不保证响应顺序：目录项位于中间时旧 drop(1) 会把 a.mkv 静默丢弃
        val entries = listOf(
            entry("/dav/a.mkv", "a.mkv"),
            entry("/dav", "dav", isCollection = true),
            entry("/dav/b.mkv", "b.mkv"),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/dav")

        assertThat(children.map { it.displayName }).containsExactly("a.mkv", "b.mkv").inOrder()
    }

    @Test
    fun `excludeSelfEntry with self last keeps all children`() {
        val entries = listOf(
            entry("/dav/a.mkv", "a.mkv"),
            entry("/dav/b.mkv", "b.mkv"),
            entry("/dav", "dav", isCollection = true),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/dav")

        assertThat(children.map { it.displayName }).containsExactly("a.mkv", "b.mkv").inOrder()
    }

    @Test
    fun `excludeSelfEntry without self entry excludes nothing`() {
        // 部分服务器不返回目录项：旧 drop(1) 会丢掉首个真实文件
        val entries = listOf(
            entry("/dav/a.mkv", "a.mkv"),
            entry("/dav/b.mkv", "b.mkv"),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/dav")

        assertThat(children.map { it.displayName }).containsExactly("a.mkv", "b.mkv").inOrder()
    }

    @Test
    fun `excludeSelfEntry tolerates trailing slash and percent encoding differences`() {
        // 目录项 href 带末尾斜杠、子项含中文编码路径：解码+规范化后仍能正确匹配剔除
        val entries = listOf(
            entry("/dav/%E7%94%B5%E5%BD%B1/", "电影", isCollection = true),
            entry("/dav/%E7%94%B5%E5%BD%B1.mkv", "电影.mkv"),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/dav/电影")

        assertThat(children.map { it.displayName }).containsExactly("电影.mkv")
    }

    @Test
    fun `excludeSelfEntry at root matches root`() {
        val entries = listOf(
            entry("/", "/", isCollection = true),
            entry("/Movies", "Movies", isCollection = true),
        )

        val children = WebDavPathUtils.excludeSelfEntry(entries, "/")

        assertThat(children.map { it.displayName }).containsExactly("Movies")
    }
}
