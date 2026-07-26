package xa.refile.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.matcher.MatchCandidate
import xa.refile.core.model.MediaType
import xa.refile.core.naming.BatchContext
import xa.refile.core.naming.BindingResolver
import xa.refile.core.naming.FileContext
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.naming.NamingOptions
import xa.refile.core.naming.Preset
import xa.refile.core.naming.PresetRepository
import xa.refile.core.naming.TemplateEngine
import xa.refile.core.parser.ParsedFilename
import xa.refile.core.rename.CompanionRename
import xa.refile.core.rename.CompanionResolver
import xa.refile.core.rename.RenameOperation
import xa.refile.core.webdav.MediaFileTypes
import xa.refile.core.webdav.WebDavClient
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.ServerRepository
import xa.refile.data.repository.TmdbCacheRepository
import xa.refile.ui.match.MatchViewModel
import xa.refile.worker.RenameWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import javax.inject.Inject

/**
 * 重命名预览页 ViewModel（计划 §M3 Task 3.4，只预览不执行）。
 *
 * 流程：取已匹配文件（来自 [MatchSessionViewModel.matches]，经 [load] 入参传入）
 * → 对每个用 [TemplateEngine] 并发渲染 targetPath（伴随文件不在渲染期发现，改由 [loadCompanions] 按需拉取）
 * → 两轮冲突检测（目标目录并发 PROPFIND + 同批次内重名）→ 供 UI 展示。
 *
 * 「只预览不执行」：本页不直接 MOVE/MKCOL，仅在用户确认后经 [RenameWorkScheduler]
 * 把 [RenameOperation] 列表入队 WorkManager，再导航到进度页。
 *
 * 安全：密码仅在 [ServerRepository.clientFor] 内解密用于构造 [WebDavClient]，绝不进入 UI 状态/日志。
 *
 * 依赖注入：[ServerRepository]/[SettingsRepository]/[PresetRepository]/[RenameWorkScheduler]
 * 均由 Hilt 提供；[CompanionResolver]/[TemplateEngine]/[BindingResolver] 为无状态/每项构造，不注入。
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val serverRepo: ServerRepository,
    private val settings: SettingsRepository,
    private val presetRepo: PresetRepository,
    private val workScheduler: RenameWorkScheduler,
    private val tmdbCache: TmdbCacheRepository,
) : ViewModel() {

    /** 预览项状态：自动✅ / 待确认⚠️ / 冲突❌。 */
    enum class PreviewStatus { AUTO, NEEDS_CONFIRM, CONFLICT }

    /**
     * 顶部统计行点击筛选的可见项类别。
     *
     * 用户要求顶部分类为「全部 / 未匹配 / 冲突」：未匹配即 [PreviewStatus.NEEDS_CONFIRM]
     * （待确认/无匹配），冲突即 [PreviewStatus.CONFLICT]。AUTO（自动✅）为常态不单列筛选。
     */
    enum class StatusFilter { ALL, UNMATCHED, CONFLICT }

    /**
     * 单条预览项（对应 LazyColumn 一行）。
     *
     * @property sourcePath     主文件源路径（小字灰色展示）。
     * @property targetPath     主文件目标路径（大字主题色展示；冲突时标红）。
     * @property targetFileName 仅文件名（[targetPath] 末段），用于无目录时单独展示（Task 4.1）。
     * @property hasDirectory   模板渲染结果是否含 `/`（即目标是否落在子目录）（Task 4.1）。
     * @param mediaTitle        剧集/电影标题摘要（如「贵人多旺事 · S01E03」），让用户直观确认这是哪部剧哪一集。
     * @property companions     伴随文件重命名（字幕/nfo/图片，跟随主文件改名）。
     * @property mediaType      媒体类型，用于构造 [RenameOperation]。
     * @property status         当前状态（自动/待确认/冲突），由渲染与冲突检测共同决定。
     * @property warnings       模板渲染警告（缺失变量等），驱动「待确认」状态。
     * @property conflictReason 冲突原因（仅 [PreviewStatus.CONFLICT] 时非空）。
     * @property manuallyEdited 是否经用户手动修改过目标路径。
     * @property candidates     待确认项的候选列表（仅 PENDING/NO_MATCH 项非空，Task 3.3/3.4）。
     */
    data class PreviewItem(
        val sourcePath: String,
        val targetPath: String,
        val targetFileName: String = "",
        val hasDirectory: Boolean = false,
        val mediaTitle: String? = null,
        val companions: List<CompanionRename>,
        val mediaType: MediaType,
        val status: PreviewStatus,
        val warnings: List<String>,
        val conflictReason: String? = null,
        val manuallyEdited: Boolean = false,
        val candidates: List<MatchViewModel.Candidate> = emptyList(),
    )

    /** 预览页 UI 状态。 */
    data class UiState(
        val loading: Boolean = false,
        val detecting: Boolean = false,
        val previewItems: List<PreviewItem> = emptyList(),
        val filter: StatusFilter = StatusFilter.ALL,
        val error: String? = null,
    ) {
        /** 经当前 [filter] 过滤后的可见项（LazyColumn 渲染依据）。 */
        val activeItems: List<PreviewItem>
            get() = when (filter) {
                StatusFilter.ALL -> previewItems
                StatusFilter.UNMATCHED -> previewItems.filter { it.status == PreviewStatus.NEEDS_CONFIRM }
                StatusFilter.CONFLICT -> previewItems.filter { it.status == PreviewStatus.CONFLICT }
            }

        /** 自动✅ 数（不受 [filter] 影响，用于顶部摘要）。 */
        val autoCount: Int get() = previewItems.count { it.status == PreviewStatus.AUTO }

        /** 待确认⚠️ 数。 */
        val needsConfirmCount: Int get() = previewItems.count { it.status == PreviewStatus.NEEDS_CONFIRM }

        /** 冲突❌ 数。 */
        val conflictCount: Int get() = previewItems.count { it.status == PreviewStatus.CONFLICT }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var webDavClient: WebDavClient? = null

    @Volatile
    private var serverId: Long = 0L

    @Volatile
    private var initialized: Boolean = false

    // Task 14.1：冲突检测协程，快速连续触发时取消上一次，仅保留最后一次结果。
    private var detectJob: kotlinx.coroutines.Job? = null

    // Task 3.4：渲染上下文快照，供 confirmPending 就地重渲染使用。
    private val _matches = MutableStateFlow<List<MatchViewModel.FileMatch>>(emptyList())
    @Volatile private var renderRootPath: String = "/"
    @Volatile private var renderPreset: Preset? = null
    @Volatile private var renderNamingOptions: NamingOptions? = null
    @Volatile private var renderToday: String? = null
    @Volatile private var renderMovieTemplate: String? = null
    @Volatile private var renderEpisodeTemplate: String? = null
    @Volatile private var renderCustomTemplate: String? = null

    /**
     * 加载预览：取服务器配置构造 [WebDavClient]，并发渲染目标路径（伴随文件改由 [loadCompanions] 按需发现），再触发冲突检测。
     *
     * 用 [initialized] 守卫，避免 [matches] 变化导致重复加载（Activity 作用域的 matches Flow
     * 可能在首次组合时先空后非空，触发两次 [load]）。
     */
    fun load(serverId: Long, matches: List<MatchViewModel.FileMatch>) {
        if (initialized) return
        if (matches.isEmpty()) return
        initialized = true
        this.serverId = serverId
        _matches.value = matches
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val entity = serverRepo.getServer(serverId)
                if (entity == null) {
                    // Task 3.1：服务器被删除导致 client 缺失，重置 initialized 允许后续重试，避免死锁。
                    initialized = false
                    _uiState.update { it.copy(loading = false, error = "未找到服务器配置") }
                    return@launch
                }
                val client = serverRepo.clientFor(entity)
                webDavClient = client
                // baseUrl 已含路径，浏览/重命名根固定为 "/"（不再追加 entity.rootPath，
                // 否则会把路径重复拼接）
                val rootPath = "/"

                val presetId = settings.presetId.first()
                val customTemplate = settings.templateString.first()
                val movieTemplate = settings.movieTemplateString.first()
                val episodeTemplate = settings.episodeTemplateString.first()
                val namingOptions = settings.visualOptions.first().toNamingOptions()
                val preset = Preset.byId(presetId)
                val today = LocalDate.now().toString()

                // Task 3.4：保存渲染上下文供 confirmPending 重渲染。
                renderRootPath = rootPath
                renderPreset = preset
                renderNamingOptions = namingOptions
                renderToday = today
                renderMovieTemplate = movieTemplate
                renderEpisodeTemplate = episodeTemplate
                renderCustomTemplate = customTemplate

                // SubTask 1.2：并发渲染（Semaphore(16) 限流）；renderItem 移除网络后为纯 CPU，可高并发。
                val items = coroutineScope {
                    val sem = Semaphore(16)
                    matches.map { fm ->
                        async {
                            sem.withPermit {
                                renderItem(fm, rootPath, preset, resolveTemplate(fm), namingOptions, today)
                            }
                        }
                    }.awaitAll()
                }
                _uiState.update { it.copy(loading = false, previewItems = items) }
                detectConflicts()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Task 3.1：渲染前抛异常导致 client 缺失，重置 initialized 允许后续重试，避免死锁。
                initialized = false
                _uiState.update { it.copy(loading = false, error = "加载预览失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * EditMatch 回写后用最新 matches 重新渲染预览（不重新取服务器配置）。
     *
     * 复用 [load] 时缓存的渲染上下文（[renderRootPath]/[renderPreset]/[renderNamingOptions]/
     * [renderToday] 等）与 [webDavClient] 重新 map 所有项，再 [detectConflicts]。
     * 若未 load 过（[webDavClient] 或 [renderPreset] 为 null），回退到 [load]。
     */
    fun reload(matches: List<MatchViewModel.FileMatch>) {
        _matches.value = matches
        webDavClient ?: return load(serverId, matches)
        val preset = renderPreset ?: return load(serverId, matches)
        val namingOptions = renderNamingOptions ?: return
        val today = renderToday ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                // SubTask 1.2：并发渲染（Semaphore(16) 限流）。
                val items = coroutineScope {
                    val sem = Semaphore(16)
                    matches.map { fm ->
                        async {
                            sem.withPermit {
                                renderItem(fm, renderRootPath, preset, resolveTemplate(fm), namingOptions, today)
                            }
                        }
                    }.awaitAll()
                }
                _uiState.update { it.copy(loading = false, previewItems = items) }
                detectConflicts()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = "重新加载预览失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 测试反馈 Item 9：优先用对应类型的独立模板，回退到旧版单模板，再回退到预设。
     *
     * 媒体类型判定优先用 [MatchViewModel.FileMatch.matched] 的 [MediaMetadata.type]（用户在
     * EditMatch 里手动改过类型后以实际匹配类型为准，修复「剧集改电影后重命名规则仍是剧集」）；
     * 未匹配时回退到文件名解析结果 [ParsedFilename.isEpisode]。
     */
    private fun resolveTemplate(fm: MatchViewModel.FileMatch): String? =
        when {
            isEpisode(fm) ->
                renderEpisodeTemplate?.takeIf { it.isNotBlank() }
                    ?: renderCustomTemplate?.takeIf { it.isNotBlank() }
            else ->
                renderMovieTemplate?.takeIf { it.isNotBlank() }
                    ?: renderCustomTemplate?.takeIf { it.isNotBlank() }
        }

    /**
     * 判定文件是否为剧集：优先用已匹配元数据的 [MediaMetadata.isEpisode]（尊重用户在 EditMatch
     * 里的类型切换），未匹配时回退到文件名解析 [ParsedFilename.isEpisode]。
     */
    private fun isEpisode(fm: MatchViewModel.FileMatch): Boolean =
        fm.matched?.isEpisode ?: fm.parsed.isEpisode

    /**
     * 渲染单个文件的目标路径（纯 CPU，不含网络请求，可高并发）。
     *
     * SubTask 1.1：伴随文件不在本方法发现（避免万级文件万次请求），companions 留空；
     * 由 [loadCompanions] 在 UI 展开时按需拉取。
     *
     * 模板字符串优先取用户自定义（[SettingsRepository.templateString]），为空则按预设 ID 与
     * 媒体类型（电影/剧集）从 [PresetRepository.templateFor] 取内置预设模板。
     * 渲染结果为相对库根的路径（如 `Movies/The Movie (2023)/The Movie (2023)`），
     * 追加主文件扩展名后拼到 [rootPath] 之下得到完整目标路径。
     *
     * 渲染为空（缺关键变量）时保持源路径不变，并标 [PreviewStatus.NEEDS_CONFIRM]。
     *
     * Task 3.3：待确认项（PENDING/NO_MATCH，[MatchViewModel.FileMatch.matched]==null）渲染为
     * 待确认卡片——不渲染目标路径（留空），保留 [MatchViewModel.FileMatch.candidates] 供预览页
     * 就地展开选择候选后 [confirmPending] 转为已匹配并重新渲染。
     */
    private suspend fun renderItem(
        fm: MatchViewModel.FileMatch,
        rootPath: String,
        preset: Preset,
        resolvedTemplate: String?,
        namingOptions: NamingOptions,
        today: String,
    ): PreviewItem {
        // 媒体类型优先用已匹配元数据的 type（尊重用户在 EditMatch 里的类型切换），
        // 未匹配时回退到文件名解析结果。修复「剧集改电影后重命名规则仍是剧集」。
        val mediaType = fm.matched?.type ?: if (fm.parsed.isEpisode) MediaType.EPISODE else MediaType.MOVIE

        // Task 3.3：待确认项不渲染目标路径，留候选供就地确认。
        if (fm.matched == null) {
            return PreviewItem(
                sourcePath = fm.filePath,
                targetPath = "",
                targetFileName = "",
                hasDirectory = false,
                mediaTitle = fm.multiEpisodeRange,
                companions = emptyList(),
                mediaType = mediaType,
                status = PreviewStatus.NEEDS_CONFIRM,
                warnings = emptyList(),
                candidates = fm.candidates,
            )
        }

        val fileName = fm.filePath.substringAfterLast('/')
        val ext = MediaFileTypes.extension(fileName) ?: ""
        val media = fm.matched!!
        // resolvedTemplate 为空时回退到内置预设对应类型的模板。
        // 用 media.isEpisode（实际匹配类型）而非文件名解析，确保剧集改电影后用电影模板。
        val template = resolvedTemplate?.takeIf { it.isNotBlank() }
            ?: presetRepo.templateFor(preset, media.isEpisode)
        val fileCtx = FileContext(
            displayName = fileName,
            ext = ext,
            fullPath = fm.filePath,
            parsed = fm.parsed,
        )
        val batchCtx = BatchContext(today = today)
        val resolver = BindingResolver(media, fileCtx, batchCtx, namingOptions)
        val engine = TemplateEngine(resolver, namingOptions)
        val rendered = engine.render(template)
        val targetRel = rendered.path
        val hasDirectory = targetRel.contains('/')
        val targetFull = if (targetRel.isNotBlank()) {
            val withExt = if (ext.isBlank()) targetRel else "$targetRel.$ext"
            // 模板未含目录分隔符时，目标留在源文件父目录，避免被挪到服务器根目录
            val baseDir = if (hasDirectory) rootPath else parentDir(fm.filePath)
            joinPath(baseDir, withExt)
        } else {
            // 渲染为空（缺关键变量）→ 保持源路径，标待确认
            fm.filePath
        }
        // SubTask 1.1：伴随文件不在渲染期发现（避免万级文件万次请求），companions 留空。
        // 伴随文件改由 [loadCompanions] 按需发现（UI 展开某行时调用）。
        val status = if (rendered.warnings.isNotEmpty() || targetRel.isBlank()) {
            PreviewStatus.NEEDS_CONFIRM
        } else {
            PreviewStatus.AUTO
        }
        return PreviewItem(
            sourcePath = fm.filePath,
            targetPath = targetFull,
            targetFileName = targetFull.substringAfterLast('/'),
            hasDirectory = hasDirectory,
            mediaTitle = formatMediaTitle(media, fm.multiEpisodeRange),
            companions = emptyList(),
            mediaType = mediaType,
            status = status,
            warnings = rendered.warnings,
        )
    }

    /**
     * 构造人类可读的剧集/电影摘要，供预览卡顶部展示「这是哪部剧哪一集」。
     *
     * - 剧集：「剧名 · S01E03」/「剧名 · S01E01-E03」；缺季号回退到 multiEpisodeRange 或集号。
     * - 电影：「电影名 (2024)」；缺年份时仅返回名字。
     * - 名字缺失时返回 null（不展示标题行）。
     */
    private fun formatMediaTitle(media: MediaMetadata, multiEpisodeRange: String?): String? {
        val name = media.name?.takeIf { it.isNotBlank() } ?: media.originalName?.takeIf { it.isNotBlank() } ?: return null
        return if (media.isEpisode) {
            val ep = multiEpisodeRange?.takeIf { it.isNotBlank() }
                ?: media.seasonNumber?.let { s ->
                    val eps = media.episodeNumbers
                    if (eps.isEmpty()) {
                        "S%02d".format(s)
                    } else {
                        eps.joinToString("-") { "S%02dE%02d".format(s, it) }
                    }
                }
            if (ep != null) "$name · $ep" else name
        } else {
            media.year?.let { "$name ($it)" } ?: name
        }
    }

    /**
     * SubTask 1.1/1.6：按需发现伴随文件（字幕/nfo/图片等）。
     *
     * [renderItem] 移除网络请求后，伴随文件不在渲染期发现（避免万级文件万次请求）。
     * UI 在用户展开某行「伴随文件」时调用本方法，用缓存的 [webDavClient] 调
     * [CompanionResolver.resolve] 返回伴随文件列表。
     *
     * @return 伴随文件重命名列表；[webDavClient] 为空或请求异常时返回空列表。
     */
    suspend fun loadCompanions(item: PreviewItem): List<CompanionRename> {
        val client = webDavClient ?: return emptyList()
        return try {
            CompanionResolver(client).resolve(item.sourcePath, item.targetPath)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * SubTask 3.4.2：两轮冲突检测。
     *
     * 第一轮：对每个唯一目标父目录发 PROPFIND Depth 1，收集已存在文件名；目标名命中即冲突。
     * 第二轮：统计同批次内相同 targetPath，出现 >1 次即冲突（同批次重名）。
     *
     * 目标与源同路径（未改名）不算冲突。冲突项标 [PreviewStatus.CONFLICT] 并填 [PreviewItem.conflictReason]。
     */
    fun detectConflicts() {
        val client = webDavClient ?: return
        val items = _uiState.value.previewItems
        if (items.isEmpty()) return
        // Task 14.1：取消上一次未完成的检测，避免快速连续触发时后完成者覆盖先完成者的结果。
        detectJob?.cancel()
        detectJob = viewModelScope.launch {
            _uiState.update { it.copy(detecting = true) }
            try {
                // 第一轮：PROPFIND 各目标父目录，收集已存在文件名（按目录分组）。
                // SubTask 1.3：并发 PROPFIND（Semaphore(8) 限流 + withTimeout(10s) 容错，单请求失败回退空列表）。
                // Task 3.2：跳过待确认项（targetPath 为空），避免空路径父目录误判与重复统计。
                val targetDirs = items.filter { it.targetPath.isNotBlank() }.map { parentDir(it.targetPath) }.distinct()
                val existingNames = mutableMapOf<String, Set<String>>()
                coroutineScope {
                    val sem = Semaphore(8)
                    val deferreds = targetDirs.map { dir ->
                        async {
                            val entries = try {
                                sem.withPermit { withTimeout(10_000L) { client.propfind(dir, 1) } }
                            } catch (e: Exception) {
                                emptyList()
                            }
                            dir to entries
                                .filterNot { it.isCollection }
                                .mapNotNull { entry ->
                                    entry.displayName?.takeIf { n -> n.isNotEmpty() }
                                        ?: nameFromHref(entry.href)
                                }
                                .toSet()
                        }
                    }
                    deferreds.awaitAll().forEach { (dir, names) -> existingNames[dir] = names }
                }
                // B30: awaitAll 到下方 _uiState.update 之间无显式挂起点，依赖
                // viewModelScope 默认的 Dispatchers.Main.immediate 单线程特性保证这段同步代码
                // 原子执行（detectJob.cancel() 不会在这段中间插入取消点）。若日后把
                // viewModelScope 改为非 Main 派发器，或在此插入挂起调用，需用 Mutex 保护
                // 「读 items → 算 updated → 写 _uiState」这一段，否则取消时序会出竞态。
                // 第二轮：同批次内重名统计。Task 3.2：跳过待确认项，避免空路径被误统计为重复。
                val targetCounts = items.filter { it.targetPath.isNotBlank() }.groupingBy { it.targetPath }.eachCount()

                val updated = items.map { item ->
                    // Task 3.2：待确认项（targetPath 为空）保持原状态，不参与冲突判定。
                    if (item.targetPath.isBlank()) return@map item
                    val dir = parentDir(item.targetPath)
                    val targetName = fileNameOf(item.targetPath)
                    val existsOnServer = existingNames[dir]?.contains(targetName) == true
                    val isDuplicate = (targetCounts[item.targetPath] ?: 0) > 1
                    // 目标与源同路径（未改名）不算冲突
                    val unchanged = item.targetPath == item.sourcePath
                    when {
                        unchanged -> item.copy(
                            status = if (item.warnings.isNotEmpty()) PreviewStatus.NEEDS_CONFIRM else PreviewStatus.AUTO,
                            conflictReason = null,
                        )
                        existsOnServer -> item.copy(
                            status = PreviewStatus.CONFLICT,
                            conflictReason = "目标路径在服务器已存在",
                        )
                        isDuplicate -> item.copy(
                            status = PreviewStatus.CONFLICT,
                            conflictReason = "批次内目标路径重复",
                        )
                        item.warnings.isNotEmpty() -> item.copy(
                            status = PreviewStatus.NEEDS_CONFIRM,
                            conflictReason = null,
                        )
                        else -> item.copy(
                            status = PreviewStatus.AUTO,
                            conflictReason = null,
                        )
                    }
                }
                _uiState.update { it.copy(previewItems = updated, detecting = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(detecting = false, error = "冲突检测失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * SubTask 3.4.2：一键自动加序号后缀解决冲突。
     *
     * 对每个冲突项，在主文件名扩展名前插入 ` (n)`（n 从 1 递增）直到目标既不与同批次已占用
     * 目标重复、也不与服务器已存在文件名重复。解决后状态置 [PreviewStatus.AUTO] 并标记手动编辑。
     */
    fun autoResolveConflicts() {
        val items = _uiState.value.previewItems
        if (items.none { it.status == PreviewStatus.CONFLICT }) return
        val client = webDavClient
        viewModelScope.launch {
            _uiState.update { it.copy(detecting = true) }
            try {
                // 收集服务器已存在文件名（按目标父目录），用于避免新后缀仍撞名。
                val existingNames = mutableMapOf<String, MutableSet<String>>()
                if (client != null) {
                    val dirs = items.map { parentDir(it.targetPath) }.distinct()
                    for (dir in dirs) {
                        val entries = try {
                            client.propfind(dir, 1)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        existingNames[dir] = entries
                            .filterNot { it.isCollection }
                            .mapNotNull { entry ->
                                entry.displayName?.takeIf { n -> n.isNotEmpty() }
                                    ?: nameFromHref(entry.href)
                            }
                            .toMutableSet()
                    }
                }
                // 非冲突项目标先占位
                val usedTargets = mutableSetOf<String>()
                items.filter { it.status != PreviewStatus.CONFLICT }.forEach { usedTargets.add(it.targetPath) }

                val resolved = items.map { item ->
                    if (item.status != PreviewStatus.CONFLICT) return@map item
                    val dir = parentDir(item.targetPath)
                    val existing = existingNames[dir] ?: mutableSetOf()
                    var attempt = item.targetPath
                    var n = 1
                    while (attempt in usedTargets || existing.contains(fileNameOf(attempt))) {
                        attempt = appendSuffix(item.targetPath, n)
                        n++
                    }
                    usedTargets.add(attempt)
                    existing.add(fileNameOf(attempt))
                    item.copy(
                        targetPath = attempt,
                        status = PreviewStatus.AUTO,
                        conflictReason = null,
                        manuallyEdited = true,
                    )
                }
                _uiState.update { it.copy(previewItems = resolved, detecting = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(detecting = false, error = "解决冲突失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /** 顶部统计行点击切换筛选（再次点击同一筛选回退到 ALL）。 */
    fun setFilter(filter: StatusFilter) {
        _uiState.update {
            val next = if (it.filter == filter) StatusFilter.ALL else filter
            it.copy(filter = next)
        }
    }

    /**
     * Task 3.4：待确认项就地确认——拉取候选详情转为已匹配，重新渲染该条 PreviewItem 并重检冲突。
     *
     * 用 [tmdbCache] 拉详情（[fetchDetail]），把对应 [MatchViewModel.FileMatch] 更新为
     * [MatchViewModel.MatchStatus.CONFIRMED] 后用渲染上下文快照重渲染。
     */
    fun confirmPending(filePath: String, candidate: MatchCandidate) {
        val fm = _matches.value.firstOrNull { it.filePath == filePath } ?: return
        viewModelScope.launch {
            try {
                val apiKey = settings.apiKey.first()
                if (apiKey.isBlank()) {
                    _uiState.update { it.copy(error = "请先在设置中填入 TMDB API Key") }
                    return@launch
                }
                val language = settings.language.first()
                val meta = fetchDetail(candidate, fm.parsed, language)
                // B19: 校验拉取的元数据是否完整——缺 name 等关键字段会导致 renderItem 渲染出
                // 空文件名（用户确认了一个无效匹配却看不到错误）。此时报错并保留待确认状态。
                if (meta.name.isNullOrBlank() && meta.originalName.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(error = "候选元数据不完整（无标题），请选择其它候选或检查 TMDB API Key")
                    }
                    return@launch
                }
                val confirmed = fm.copy(
                    status = MatchViewModel.MatchStatus.CONFIRMED,
                    matched = meta,
                )
                _matches.value = _matches.value.map { if (it.filePath == filePath) confirmed else it }

                val preset = renderPreset
                val namingOptions = renderNamingOptions
                val today = renderToday
                if (webDavClient == null || preset == null || namingOptions == null || today == null) return@launch
                val newItem = renderItem(
                    confirmed, renderRootPath, preset,
                    resolveTemplate(confirmed), namingOptions, today,
                )
                _uiState.update { s ->
                    s.copy(previewItems = s.previewItems.map { if (it.sourcePath == filePath) newItem else it })
                }
                detectConflicts()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(error = "确认匹配失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 入队执行：把可见、已匹配（[PreviewStatus.AUTO]）项构造为 [RenameOperation] 列表经 [RenameWorkScheduler] 入队。
     *
     * Task 3.5：待确认项不阻塞执行但被排除在执行列表外（仅入队 AUTO 项）。
     * 返回 workId（UUID 字符串）供 UI 导航到进度页；无可执行项或仍有冲突时返回 null 并写错误状态。
     * 本页不直接 MOVE/MKCOL（只预览不执行），实际执行由 [xa.refile.worker.RenameWorker] 完成。
     */
    fun enqueueRename(): String? {
        val state = _uiState.value
        if (state.conflictCount > 0) {
            _uiState.update { it.copy(error = "存在 ${state.conflictCount} 个冲突，请先解决") }
            return null
        }
        val items = state.activeItems.filter { it.status == PreviewStatus.AUTO }
        if (items.isEmpty()) {
            _uiState.update { it.copy(error = "无可执行的重命名项") }
            return null
        }
        return try {
            val ops = items.map {
                RenameOperation(it.sourcePath, it.targetPath, it.companions, it.mediaType)
            }
            workScheduler.enqueue(serverId, ops, batchName = "重命名 ${items.size} 项").toString()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _uiState.update { it.copy(error = "入队失败：${t.message ?: "未知错误"}") }
            null
        }
    }

    /** 清除一次性错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ---- Task 3.4：待确认项就地确认的详情拉取（与 MatchViewModel.fetchDetail 同语义） ----

    /**
     * 拉详情：电影 → [TmdbCacheRepository.getMovie]；剧集 → [TmdbCacheRepository.getTv] +
     * [TmdbCacheRepository.getSeason] 填 [MediaMetadata.seasonNumber]/[MediaMetadata.episodeNumbers] 等。
     *
     * 季号解析顺序：显式 parsed.season > 绝对集号按季累加定位 (season, episodeInSeason) > 回退 1。
     */
    private suspend fun fetchDetail(
        candidate: MatchCandidate,
        parsed: ParsedFilename,
        language: String,
    ): MediaMetadata {
        val id = candidate.tmdbId
        return if (candidate.mediaType == MediaType.EPISODE) {
            val tv = tmdbCache.getTv(id, language)
            var seasonNumber = parsed.season
            var episodes = parsed.episodes
            if (seasonNumber == null && parsed.isAbsoluteEpisode && episodes.isNotEmpty()) {
                resolveAbsoluteEpisode(id, episodes.first(), tv.numberOfSeasons, language)?.let { (s, e) ->
                    seasonNumber = s
                    episodes = listOf(e)
                }
            }
            val finalSeason = seasonNumber ?: 1
            if (episodes.isEmpty()) {
                tv.copy(seasonNumber = finalSeason)
            } else {
                val season = try {
                    tmdbCache.getSeason(id, finalSeason, language)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                val byNum = season?.episodes
                    ?.filter { it.episodeNumber != null }
                    ?.associateBy { it.episodeNumber!! }
                    ?: emptyMap()
                val titles = episodes.mapNotNull { byNum[it]?.name }
                val airDates = episodes.mapNotNull { byNum[it]?.airDate }
                tv.copy(
                    seasonNumber = finalSeason,
                    episodeNumbers = episodes,
                    episodeTitles = if (titles.size > 1) listOf(titles.joinToString(" & ")) else titles,
                    episodeAirDates = airDates,
                    seasonName = season?.name,
                )
            }
        } else {
            tmdbCache.getMovie(id, language)
        }
    }

    /** 绝对集号 → (season, episodeInSeason) 定位：按季顺序累加常规集数（跳过 Season 0 特典）。 */
    private suspend fun resolveAbsoluteEpisode(
        tvId: Int,
        absEp: Int,
        numberOfSeasons: Int?,
        language: String,
    ): Pair<Int, Int>? {
        val maxSeason = numberOfSeasons ?: return null
        if (maxSeason <= 0 || absEp <= 0) return null
        var remaining = absEp
        for (s in 1..maxSeason) {
            val season = try {
                tmdbCache.getSeason(tvId, s, language)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            } ?: continue
            val count = season.episodes.count { (it.episodeNumber ?: 0) > 0 }
            if (count <= 0) continue
            if (remaining <= count) return s to remaining
            remaining -= count
        }
        return null
    }

    // ---- 路径工具（与 BrowserViewModel 同语义的本地实现，避免跨包依赖） ----

    /** 规范化路径：保证以 "/" 开头，去除多余末尾斜杠（根 "/" 保留）。 */
    private fun normalizePath(p: String): String {
        var s = p.trim()
        if (!s.startsWith("/")) s = "/$s"
        while (s.length > 1 && s.endsWith("/")) s = s.removeSuffix("/")
        if (s.isEmpty()) s = "/"
        return s
    }

    /** 拼接目录与子路径（子路径可含 `/` 分层）。根目录 "/" 时不产生重复斜杠。 */
    private fun joinPath(dir: String, child: String): String {
        val d = normalizePath(dir)
        val c = child.trim().trimStart('/')
        if (c.isEmpty()) return d
        val base = if (d == "/") "" else d
        return normalizePath("$base/$c")
    }

    /** 取路径的父目录。无 `/` 或仅根 `/` 时返回 `/`。 */
    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    /** 取路径末段文件名。 */
    private fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** 从 WebDAV href 取末段并做最小 %20 解码（仅当 displayName 缺失时回退用）。 */
    private fun nameFromHref(href: String): String =
        href.trimEnd('/').substringAfterLast('/').replace("%20", " ")

    /** 在文件名扩展名前插入 ` (n)` 后缀：`/d/a.mkv` → `/d/a (1).mkv`。无扩展名则追加到末尾。 */
    private fun appendSuffix(path: String, n: Int): String {
        val dir = parentDir(path)
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return joinPath(dir, "$base ($n)$ext")
    }
}
