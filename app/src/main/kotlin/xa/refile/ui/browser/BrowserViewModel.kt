package xa.refile.ui.browser

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.webdav.MediaFileTypes
import xa.refile.core.webdav.WebDavClient
import xa.refile.core.webdav.WebDavEntry
import xa.refile.core.webdav.WebDavException
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 文件浏览器 ViewModel（计划 §M1 SubTask 1.5.1–1.5.6）。
 *
 * 职责：
 * - [init] 按 serverId 取配置，解密密码构造 [WebDavClient]，加载服务器 rootPath。
 * - [loadDirectory] 对 path 发 PROPFIND Depth 1，过滤掉返回的第一项（当前目录本身），
 *   按当前排序规则排序。目录始终排在文件前。
 * - 导航：[navigateInto] / [navigateTo] / [goUp] / [refresh]。
 * - 排序：[toggleSort] / [toggleSortOrder]，改变规则即对当前列表重排。
 * - 多选：长按视频或目录进入多选；可选范围为「视频文件 + 目录」（[isSelectable]）；
 *   字幕/nfo/图片/iso 仅显示，不可选。选中目录在「匹配」时通过
 *   [expandSelectionToFiles] 递归展开为视频文件路径。
 *
 * 安全约束：密码仅在 [init] 内解密用于构造 [WebDavClient]，绝不进入日志/UI 状态（红线）。
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val serverRepo: ServerRepository,
    private val crypto: KeystoreCrypto,
) : ViewModel() {

    /** 排序字段。 */
    enum class SortField { NAME, SIZE, TIME }

    /** 浏览器 UI 状态。 */
    data class UiState(
        val serverName: String = "",
        val rootPath: String = "/",
        val currentPath: String = "/",
        val entries: List<WebDavEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val sortField: SortField = SortField.NAME,
        val sortAsc: Boolean = true,
        val multiSelectMode: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        /** 递归展开选中目录为视频文件中（「匹配」点击后）。 */
        val expanding: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var webDavClient: WebDavClient? = null

    /** 会话级目录缓存：key=规范路径，value=未排序的子项。VM 销毁即释放。 */
    // B20: 改用 ConcurrentHashMap，避免快速切换目录时多协程并发读写 mutableMapOf 导致的竞态。
    private val pathEntriesCache = ConcurrentHashMap<String, List<WebDavEntry>>()

    /** 当前正在进行的目录加载协程；新调用会取消前一个，避免快速切换目录时的竞态。 */
    private var loadJob: Job? = null
    /**
     * 取服务器配置，构造 [WebDavClient] 并加载根目录。
     *
     * 按测试反馈简化：[ServerConfigEntity.baseUrl] 已存完整 URL（含 scheme/host/port/路径），
     * 直接作为 WebDavClient baseUrl 使用，不再用 https/port 字段拼装。
     * 兼容旧数据：缺 scheme 时按 https 字段补 `https://`/`http://`。
     * 浏览根路径固定为 "/"（WebDavClient 会自动补末尾斜杠请求 baseUrl 本身）。
     */
    suspend fun init(serverId: Long) {
        _uiState.update { it.copy(loading = true, error = null) }
        val entity = serverRepo.getServer(serverId)
        if (entity == null) {
            _uiState.update { it.copy(loading = false, error = "未找到服务器配置") }
            return
        }
        val raw = entity.baseUrl.trim()
        val fullBaseUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw.trimEnd('/')
        } else {
            val scheme = if (entity.https) "https" else "http"
            val host = raw.removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (entity.port != null) "$scheme://$host:${entity.port}" else "$scheme://$host"
        }
        val password = entity.encryptedPassword?.let { crypto.decrypt(it) }
        webDavClient = WebDavClient(fullBaseUrl, entity.username, password)
        pathEntriesCache.clear()
        _uiState.update {
            it.copy(serverName = entity.name, rootPath = "/", currentPath = "/")
        }
        loadDirectory("/")
    }

    /**
     * 对 [path] 发 PROPFIND Depth 1，过滤掉返回的第一项（当前目录本身）并排序后写入状态。
     *
     * 缓存优先（Task 5）：命中 [pathEntriesCache] 时直接展示，不发网络请求；
     * 未命中走网络，成功后回写缓存（存未排序 children，排序在展示时按当前规则进行）。
     *
     * 错误区分（测试反馈 Item 5）：
     * - [WebDavException]：HTTP 错误。401→认证失败；404→路径不存在；其余→按状态码提示。
     * - 成功但解析为空（raw 为空）：服务器返回了非 multistatus 内容，提示检查配置。
     * - 成功且 raw 非空：即使 children 为空（空目录）也属正常，不报错。
     *
     * SubTask 1.5.6：PROPFIND 一次性返回，列表项 > [LARGE_DIR_THRESHOLD] 时仅记 warning；
     * LazyColumn 自身虚拟化，无需分页。
     */
    fun loadDirectory(path: String) {
        loadJob?.cancel()
        val client = webDavClient ?: run {
            _uiState.update { it.copy(loading = false, error = "客户端未初始化") }
            return
        }
        val normalized = normalizePath(path)
        // 缓存优先：命中直接展示，跳过网络请求。
        pathEntriesCache[normalized]?.let { cached ->
            _uiState.update {
                it.copy(
                    loading = false,
                    entries = sortEntries(cached, it.sortField, it.sortAsc),
                    error = null,
                    currentPath = normalized,
                )
            }
            return
        }
        _uiState.update { it.copy(loading = true, error = null, currentPath = normalized) }
        loadJob = viewModelScope.launch {
            try {
                val raw = client.propfind(normalized, depth = 1)
                if (raw.isEmpty()) {
                    // 成功（2xx/207）但解析不到任何条目：服务器响应非标准 multistatus。
                    _uiState.update {
                        it.copy(
                            loading = false,
                            entries = emptyList(),
                            error = "无法读取目录，请检查路径或服务器配置",
                        )
                    }
                    return@launch
                }
                // 过滤掉返回的第一项（当前目录本身）。children 为空即空目录，属正常。
                val children = raw.drop(1)
                if (children.size > LARGE_DIR_THRESHOLD) {
                    Log.w(TAG, "Large directory detected: ${children.size} entries under '$normalized'")
                }
                pathEntriesCache[normalized] = children
                _uiState.update {
                    it.copy(
                        loading = false,
                        entries = sortEntries(children, it.sortField, it.sortAsc),
                        error = null,
                    )
                }
            } catch (e: WebDavException) {
                val msg = when (e.code) {
                    401 -> "认证失败，请检查用户名和密码"
                    403 -> "无访问权限（403）"
                    404 -> "路径不存在（404），请检查 URL"
                    405, 501 -> "服务器不支持 WebDAV PROPFIND（${e.code}）"
                    in 500..599 -> "服务器错误（${e.code}），请稍后重试"
                    else -> "无法读取目录（${e.code}），请检查路径或服务器配置"
                }
                _uiState.update { it.copy(loading = false, error = msg) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update {
                    it.copy(loading = false, error = "网络错误：${t.message ?: "无法连接服务器"}")
                }
            }
        }
    }

    /** 进入子目录。非目录忽略。 */
    fun navigateInto(entry: WebDavEntry) {
        if (!entry.isCollection) return
        val name = entry.displayName ?: nameFromHref(entry.href)
        loadDirectory(joinPath(_uiState.value.currentPath, name))
    }

    /** 跳到面包屑某层：校验 path 合法（非空、以/开头）后直接加载目标路径。 */
    fun navigateTo(path: String) {
        if (path.isBlank() || !path.startsWith("/")) return
        loadDirectory(path)
    }

    /** 返回上一级。若已在根目录返回 false（由调用方决定回退到上一屏）。 */
    fun goUp(): Boolean {
        val s = _uiState.value
        if (isRoot(s.currentPath, s.rootPath)) return false
        loadDirectory(parentPath(s.currentPath, s.rootPath))
        return true
    }

    /** 重新加载当前目录：清空当前路径缓存后强制走网络。 */
    fun refresh() {
        pathEntriesCache.remove(normalizePath(_uiState.value.currentPath))
        loadDirectory(_uiState.value.currentPath)
    }

    /** 切换排序字段，并对当前列表重排。 */
    fun toggleSort(field: SortField) {
        _uiState.update {
            it.copy(sortField = field, entries = sortEntries(it.entries, field, it.sortAsc))
        }
    }

    /** 切换升/降序，并对当前列表重排。 */
    fun toggleSortOrder() {
        _uiState.update {
            val asc = !it.sortAsc
            it.copy(sortAsc = asc, entries = sortEntries(it.entries, it.sortField, asc))
        }
    }

    /** 长按可勾选项（视频或目录）进入多选模式并预选该项；非可勾选项忽略。 */
    fun enterMultiSelect(seedPath: String, isCollection: Boolean) {
        if (!isSelectable(fileNameOf(seedPath), isCollection)) return
        _uiState.update { it.copy(multiSelectMode = true, selectedPaths = setOf(seedPath)) }
    }

    /** 勾选/取消勾选某项（视频或目录）；非可勾选项忽略。 */
    fun toggleSelected(path: String, isCollection: Boolean) {
        if (!isSelectable(fileNameOf(path), isCollection)) return
        _uiState.update {
            val next = if (path in it.selectedPaths) it.selectedPaths - path else it.selectedPaths + path
            it.copy(selectedPaths = next)
        }
    }

    /** 全选当前列表中的可勾选项（保留其它目录已选中的项）。 */
    fun selectAll() {
        _uiState.update { s ->
            s.copy(selectedPaths = s.selectedPaths + currentSelectablePaths(s))
        }
    }

    /** 反选当前列表中的可勾选项（保留其它目录已选中的项）。 */
    fun invertSelection() {
        _uiState.update { s ->
            val currentSelectables = currentSelectablePaths(s).toSet()
            val keepOutOfView = s.selectedPaths - currentSelectables
            val invertedCurrent = currentSelectables - s.selectedPaths
            s.copy(selectedPaths = invertedCurrent + keepOutOfView)
        }
    }

    /** 退出多选并清空选中。 */
    fun exitMultiSelect() {
        _uiState.update { it.copy(multiSelectMode = false, selectedPaths = emptySet()) }
    }

    /**
     * 递归展开当前选中的项为视频文件路径列表，供「匹配」使用。
     *
     * - 视频文件（按扩展名判定）直接加入结果。
     * - 目录递归 PROPFIND Depth 1：子目录继续递归，视频文件加入结果，其他文件（字幕/nfo/图片等）忽略。
     * - 单条路径异常（[WebDavException]）记 warning 跳过，不中断整体。
     * - 递归深度上限 [MAX_EXPAND_DEPTH]，防止循环引用或服务器异常导致无限递归。
     * - 结果去重后返回。
     *
     * 调用方应在协程中 launch 调用；执行期间 [UiState.expanding] 为 true。
     */
    suspend fun expandSelectionToFiles(): List<String> {
        val client = webDavClient ?: return emptyList()
        val seeds = _uiState.value.selectedPaths
        if (seeds.isEmpty()) return emptyList()
        _uiState.update { it.copy(expanding = true) }
        try {
            val result = LinkedHashSet<String>()
            for (path in seeds) {
                val normalizedName = fileNameOf(path)
                if (MediaFileTypes.isSelectableVideo(normalizedName)) {
                    // 视频文件：直接加入，无需 PROPFIND（也避免对文件 URL 强制补斜杠引发 301/405）。
                    result.add(normalizePath(path))
                    continue
                }
                // 非视频：按 UI 选择规则视为目录，递归 PROPFIND。
                try {
                    collectVideoFilesInDir(client, normalizePath(path), result, depth = 0)
                } catch (e: WebDavException) {
                    Log.w(TAG, "Skip directory '$path' during expansion: ${e.message}")
                }
            }
            return result.toList()
        } finally {
            _uiState.update { it.copy(expanding = false) }
        }
    }

    /** 递归收集 [dirPath] 下的所有视频文件完整路径到 [out]。超过 [MAX_EXPAND_DEPTH] 截断。 */
    private suspend fun collectVideoFilesInDir(
        client: WebDavClient,
        dirPath: String,
        out: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_EXPAND_DEPTH) return
        val entries = try {
            client.propfind(dirPath, depth = 1)
        } catch (e: WebDavException) {
            Log.w(TAG, "PROPFIND failed for '$dirPath': ${e.message}")
            return
        }
        if (entries.isEmpty()) return
        // 第一项通常为目录自身，跳过；遍历子项。
        for (child in entries.drop(1)) {
            val childName = child.displayName ?: nameFromHref(child.href)
            if (child.isCollection) {
                collectVideoFilesInDir(client, joinPath(dirPath, childName), out, depth + 1)
            } else if (MediaFileTypes.isSelectableVideo(childName)) {
                out.add(joinPath(dirPath, childName))
            }
            // 字幕/nfo/图片/iso 等伴随文件忽略。
        }
    }

    /** 可被勾选：目录或可重命名视频。字幕/nfo/图片/iso 不可勾选。 */
    private fun isSelectable(name: String, isCollection: Boolean): Boolean =
        isCollection || MediaFileTypes.isSelectableVideo(name)

    /** 当前列表中可勾选项（目录 + 视频）的完整路径集合。 */
    private fun currentSelectablePaths(s: UiState): List<String> =
        s.entries
            .filter { isSelectable(it.displayName ?: nameFromHref(it.href), it.isCollection) }
            .map { joinPath(s.currentPath, it.displayName ?: nameFromHref(it.href)) }

    /**
     * 排序：目录始终排在文件前；组内按 [field] 比较，升/降序。
     * - NAME：按 displayName 忽略大小写。
     * - SIZE：按 contentLength（null 视为最大，排末尾）。
     * - TIME：按 lastModified 字符串（RFC1123 同格式下字典序与时间序一致）。
     */
    private fun sortEntries(entries: List<WebDavEntry>, field: SortField, asc: Boolean): List<WebDavEntry> {
        val cmp = Comparator<WebDavEntry> { a, b ->
            when (field) {
                SortField.NAME -> (a.displayName ?: "").compareTo(b.displayName ?: "", ignoreCase = true)
                SortField.SIZE ->
                    (a.contentLength ?: Long.MAX_VALUE).compareTo(b.contentLength ?: Long.MAX_VALUE)
                SortField.TIME -> (a.lastModified ?: "").compareTo(b.lastModified ?: "")
            }
        }
        val ordered = if (asc) cmp else cmp.reversed()
        val dirs = entries.filter { it.isCollection }.sortedWith(ordered)
        val files = entries.filterNot { it.isCollection }.sortedWith(ordered)
        return dirs + files
    }

    companion object {
        private const val TAG = "BrowserViewModel"

        /** SubTask 1.5.6：超大目录阈值，超过仅记 warning，LazyColumn 已虚拟化无需分页。 */
        private const val LARGE_DIR_THRESHOLD = 2000

        /** expandSelectionToFiles 递归最大深度，防止循环引用/异常服务器导致无限递归。 */
        private const val MAX_EXPAND_DEPTH = 15
    }
}

// ---- 路径工具（供 ViewModel 与 Screen 共用） ----

/** 规范化路径：保证以 "/" 开头，去除多余末尾斜杠（根 "/" 保留）。 */
internal fun normalizePath(p: String): String {
    var s = p.trim()
    if (!s.startsWith("/")) s = "/$s"
    while (s.length > 1 && s.endsWith("/")) s = s.removeSuffix("/")
    if (s.isEmpty()) s = "/"
    return s
}

/** 拼接父路径与子段，结果规范化。根目录 "/" 时 base 置空，避免产生 "//子段" 双斜杠。 */
internal fun joinPath(parent: String, child: String): String {
    val p = normalizePath(parent)
    val c = child.trim().trimStart('/')
    if (c.isEmpty()) return p
    val base = if (p == "/") "" else p
    return normalizePath("$base/$c")
}

/** 当前路径是否即根路径。 */
internal fun isRoot(current: String, root: String): Boolean = normalizePath(current) == normalizePath(root)

/** 返回上一级路径，且不低于根。 */
internal fun parentPath(current: String, root: String): String {
    val c = normalizePath(current)
    val r = normalizePath(root)
    if (c == r || c == "/") return r
    val idx = c.lastIndexOf('/')
    val parent = if (idx <= 0) "/" else c.substring(0, idx)
    return if (parent == r || parent.length >= r.length) parent else r
}

/**
 * 计算面包屑各级：返回 (label, path) 列表，首项为根。
 * 根标签取 rootPath 末段，根为 "/" 时显示友好占位「根目录」（避免与分隔符拼成 `/ / foo`）。
 */
internal fun breadcrumbs(current: String, root: String): List<Pair<String, String>> {
    val c = normalizePath(current)
    val r = normalizePath(root)
    val rootTrimmed = r.trim('/')
    val rootLabel = if (rootTrimmed.isEmpty()) "根目录" else rootTrimmed.substringAfterLast('/')
    val result = mutableListOf(rootLabel to r)
    if (c == r) return result
    val rel = if (c.startsWith(r)) c.removePrefix(r).trimStart('/') else c.trimStart('/')
    if (rel.isEmpty()) return result
    var acc = r
    for (seg in rel.split('/').filter { it.isNotBlank() }) {
        acc = joinPath(acc, seg)
        result.add(seg to acc)
    }
    return result
}

/** 从完整路径取末段文件名。 */
internal fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** 从 WebDAV href 取末段并做最小 %20 解码（仅当 displayName 缺失时回退用）。 */
internal fun nameFromHref(href: String): String =
    href.trimEnd('/').substringAfterLast('/').replace("%20", " ")
