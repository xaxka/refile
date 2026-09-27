package xa.refile.core.util

import xa.refile.core.webdav.WebDavEntry
import java.net.URLDecoder

/**
 * 通用 WebDAV 路径工具函数。
 *
 * 消除 PreviewViewModel / RenameExecutor / WebDavClient / BrowserViewModel 中
 * 重复的路径操作代码，统一维护。
 *
 * P1-1（审查报告 2026-09-25）：[nameFromHref] 此前仅替换 `%20`，中文（`%E4%B8%AD` 等）、
 * `+`、`&` 等保留字符全部保持编码形态——文件列表显示 `%XX` 串且与已解码路径比较失败。
 * 现改为完整 [URLDecoder] 解码（容错：非法转义回退原文），并为 app 层唯一实现
 * （BrowserViewModel / PreviewViewModel 的私有同名副本已删除，统一复用本工具）。
 */
object WebDavPathUtils {

    /** 规范化路径：保证以 "/" 开头，去除多余末尾斜杠（根 "/" 保留）。 */
    fun normalizePath(p: String): String {
        var s = p.trim()
        if (!s.startsWith("/")) s = "/$s"
        while (s.length > 1 && s.endsWith("/")) s = s.removeSuffix("/")
        if (s.isEmpty()) s = "/"
        return s
    }

    /** 拼接目录与子路径（子路径可含 `/` 分层）。根目录 "/" 时不产生重复斜杠。 */
    fun joinPath(dir: String, child: String): String {
        val d = normalizePath(dir)
        val c = child.trim().trimStart('/')
        if (c.isEmpty()) return d
        val base = if (d == "/") "" else d
        return normalizePath("$base/$c")
    }

    /** 取路径的父目录。无 `/` 或仅根 `/` 时返回 `/`。 */
    fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    /** 取路径末段文件名。 */
    fun fileNameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/')

    /**
     * 从 WebDAV href 取末段文件名并完整解码（仅当 displayName 缺失时回退用）。
     *
     * P1-1（审查报告 2026-09-25）：href 是百分号编码路径，需先 [URLDecoder] 完整解码
     * （覆盖中文/`+`/`&`/`%2B` 等，而非旧实现仅替换 `%20`）再取末段。
     * `runCatching` 容错：个别服务器返回非法转义序列时回退原始 href，不抛异常。
     * 注意先解码后取末段：编码串中的 `%2F` 解码为 `/` 会引入额外分隔。
     */
    fun nameFromHref(href: String): String {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        return decoded.trimEnd('/').substringAfterLast('/')
    }

    /**
     * PROPFIND Depth 1 结果按 href 剔除目录自身（P1-2，审查报告 2026-09-25）。
     *
     * 旧实现直接 `drop(1)` 假设 multistatus 首项必是请求 URI 自身——RFC 4918 并不保证
     * 响应顺序：部分服务器（某些 nginx-webdav、网关代理）不返回目录项或放在非首位，
     * 前者会静默丢弃首个真实文件，后者会把目录项混入文件列表。
     * 改为按 [requestedPath] 匹配剔除；匹配容错：末尾斜杠差异、百分号编码差异
     * （entry.href 解码后比较）。服务器不返回目录项时一个都不剔除（保持全部子项）。
     */
    fun excludeSelfEntry(entries: List<WebDavEntry>, requestedPath: String): List<WebDavEntry> {
        val selfKey = normalizePath(requestedPath)
        return entries.filterNot { entry ->
            val decoded = runCatching { URLDecoder.decode(entry.href, "UTF-8") }.getOrDefault(entry.href)
            normalizePath(decoded) == selfKey
        }
    }

    /** 在文件名扩展名前插入 ` (n)` 后缀：`/d/a.mkv` → `/d/a (1).mkv`。无扩展名则追加到末尾。 */
    fun appendSuffix(path: String, n: Int): String {
        val dir = parentDir(path)
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return joinPath(dir, "$base ($n)$ext")
    }

    /** 路径深度：以 `/` 分隔的非空段数。如 `/a/b.mkv` → 2，`/` → 0。 */
    fun pathDepth(path: String): Int =
        path.split('/').count { it.isNotEmpty() }

    /** 取目标路径的所有祖先目录（不含根 `/`，不含文件本身）。如 `/a/b/c.mkv` → [`/a`, `/a/b`]。 */
    fun ancestorDirs(path: String): List<String> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return emptyList()
        val dirs = mutableListOf<String>()
        val sb = StringBuilder()
        for (i in 0 until segments.size - 1) {
            sb.append('/').append(segments[i])
            dirs.add(sb.toString())
        }
        return dirs
    }

    /** 取文件名去扩展名的基名：`/d/a.mkv` → `a`。无扩展名返回整个文件名。 */
    fun baseName(path: String): String {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 取文件名扩展名（含点）：`/d/a.mkv` → `.mkv`。无扩展名返回空串。 */
    fun extensionOf(path: String): String {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    /** 把路径文件名的基名从 [oldBase] 替换为 [newBase]（扩展名不变）；基名不匹配则原样返回。 */
    fun replaceBase(path: String, oldBase: String, newBase: String): String {
        val dir = parentDir(path)
        val ext = extensionOf(path)
        val name = fileNameOf(path)
        val base = if (ext.isNotEmpty()) name.removeSuffix(ext) else name
        return if (base == oldBase) joinPath(dir, newBase + ext) else path
    }
}
