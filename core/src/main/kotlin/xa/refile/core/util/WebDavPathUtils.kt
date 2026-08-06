package xa.refile.core.util

/**
 * 通用 WebDAV 路径工具函数。
 *
 * 消除 PreviewViewModel / RenameExecutor / WebDavClient / BrowserViewModel 中
 * 重复的路径操作代码，统一维护。
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

    /** 从 WebDAV href 取末段并做最小 %20 解码（仅当 displayName 缺失时回退用）。 */
    fun nameFromHref(href: String): String =
        href.trimEnd('/').substringAfterLast('/').replace("%20", " ")

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
