package xa.refile.ui.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.tmdb.Episode
import xa.refile.core.tmdb.SeasonDetail
import xa.refile.core.tmdb.TmdbImages
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.data.repository.TmdbSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 批量匹配编辑 ViewModel（集位槽模型）。
 *
 * 编辑对象 = [MatchSessionViewModel.matches] 整个批次。核心模型为「集位槽（Season Board）」：
 * 当前季的每一集是一个槽位，文件通过拖拽 / BottomSheet 点选绑定到槽位。
 *
 * 唯一事实源：[UiState.bindings]（filePath -> episodeNumber）。slots/unboundFiles/duplicates/
 * emptySlots/dirty 等 均由 bindings + episodeList + files 派生（在 [UiState] 内以 computed
 * property 暴露，UI 用 collectAsStateWithLifecycle 订阅单一 StateFlow）。
 *
 * 与 [EditMatchViewModel] 同款 TMDB 加载逻辑（loadTvDetails/loadSeason/checkApiKeyOrError），
 * Episode.toEpisodeInfo / MediaMetadata.toMediaCandidate 工具复制自 EditMatchViewModel，不跨 VM 注入。
 *
 * Task 20：搜索类请求走 [TmdbSearchRepository]（会话级内存缓存），详情类请求走
 * [TmdbDetailRepository]（7 天数据库缓存），与 [EditMatchViewModel] 一致。
 *
 * 安全：API Key 仅从 [SettingsRepository] 读取，不进 UI 状态或日志。
 */
@HiltViewModel
class BatchMatchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tmdbSearch: TmdbSearchRepository,
    private val tmdbDetail: TmdbDetailRepository,
) : ViewModel() {

    /**
     * 集位槽复合键：(season, episode)。支持「全部季」模式下跨季唯一标识一个槽位。
     * 单季模式下 season 即为 [UiState.seasonNumber]，仍走同一套逻辑。
     */
    data class SlotKey(val season: Int, val episode: Int)

    /** 集位槽行：slotKey + episode + 绑定到该槽的文件列表。 */
    data class SlotRow(
        val slotKey: SlotKey,
        val episode: EditMatchViewModel.EpisodeInfo,
        val files: List<MatchViewModel.FileMatch>,
    )

    /** UI 状态。bindings 为唯一事实源，slots/unboundFiles/duplicates 等均派生。 */
    data class UiState(
        val files: List<MatchViewModel.FileMatch> = emptyList(),
        val selectedMedia: EditMatchViewModel.MediaCandidate? = null,
        val numberOfSeasons: Int? = null,
        /** null = 全部季（默认）；非 null = 单季。 */
        val seasonNumber: Int? = null,
        val episodeList: List<EditMatchViewModel.EpisodeInfo> = emptyList(),
        /** filePath -> SlotKey，唯一事实源。 */
        val bindings: Map<String, SlotKey> = emptyMap(),
        /** dirty 对比快照（load 完成后同步，含可能的自动 smartAssign 结果）。 */
        val initialBindings: Map<String, SlotKey> = emptyMap(),
        val mediaSearchQuery: String = "",
        val mediaSearchResults: List<EditMatchViewModel.MediaCandidate> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val batchSaved: List<MatchViewModel.FileMatch>? = null,
    ) {
        /** 派生：每集槽位 + 绑定到该槽的文件列表（按 episodeList 顺序）。 */
        val slots: List<SlotRow>
            get() = episodeList.map { ep ->
                val key = SlotKey(ep.seasonNumber, ep.episodeNumber)
                SlotRow(
                    slotKey = key,
                    episode = ep,
                    files = bindings.entries
                        .filter { it.value == key }
                        .mapNotNull { e -> files.firstOrNull { it.filePath == e.key } },
                )
            }

        /** 派生：未绑定文件（files 中不在 bindings 里的）。 */
        val unboundFiles: List<MatchViewModel.FileMatch>
            get() = files.filter { it.filePath !in bindings }

        /** 派生：被 ≥2 文件绑定的槽位键。 */
        val duplicates: List<SlotKey>
            get() = bindings.values
                .groupingBy { it }
                .eachCount()
                .filter { it.value >= 2 }
                .keys
                .toList()

        /** 派生：无文件绑定的槽位键。 */
        val emptySlots: List<SlotKey>
            get() = episodeList.map { SlotKey(it.seasonNumber, it.episodeNumber) }.filter { ep ->
                bindings.values.none { it == ep }
            }

        /** 派生：bindings != initialBindings。 */
        val dirty: Boolean get() = bindings != initialBindings

        /** 派生：已绑定文件数。 */
        val boundCount: Int get() = bindings.size

        /** 派生：摘要文本「N 个文件将绑定到 M 个槽位，K 个保持原样」。 */
        val summaryText: String
            get() {
                val total = files.size
                val bound = bindings.size
                val unbound = total - bound
                return buildString {
                    append("$bound 个文件将绑定")
                    if (episodeList.isNotEmpty()) {
                        val first = episodeList.first()
                        val last = episodeList.last()
                        append("到 S${"%02d".format(first.seasonNumber)}E${"%02d".format(first.episodeNumber)}")
                        append("–S${"%02d".format(last.seasonNumber)}E${"%02d".format(last.episodeNumber)}")
                    }
                    append("，$unbound 个保持原样")
                }
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var mediaSearchJob: Job? = null

    /**
     * 由 BatchMatchScreen 进入时调用：取批次快照；从多数文件的 matched 推断 selectedMedia；
     * 默认「全部季」（seasonNumber = null），加载所有季的集列表；bindings 初始化为各文件
     * matched 的 (season, episode)（仅落在 episodeList 范围内的）；initialBindings 同步；
     * 若初始 bindings 为空则自动执行一次 [smartAssignFromParsed]（不弹窗）。
     */
    fun load(allFiles: List<MatchViewModel.FileMatch>) {
        // 守卫：避免重复加载（VM 复用时）
        if (_uiState.value.files.isNotEmpty()) return
        _uiState.update { it.copy(files = allFiles, loading = true, error = null) }

        // 多数投票：在 matched.type == EPISODE 的文件中按 (id ?: tmdbId) 分组取最大组
        val episodeFiles = allFiles.filter { it.matched?.type == MediaType.EPISODE }
        val majorityKey = episodeFiles
            .mapNotNull { it.matched?.id ?: it.matched?.tmdbId }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val basisFile = episodeFiles.firstOrNull {
            (it.matched?.id ?: it.matched?.tmdbId) == majorityKey
        }
        val basisMeta = basisFile?.matched
        if (basisMeta == null) {
            // 无剧集匹配：留空 selectedMedia，UI 提示用户搜索
            _uiState.update { it.copy(loading = false) }
            return
        }
        _uiState.update {
            it.copy(
                selectedMedia = basisMeta.toMediaCandidate(MediaType.EPISODE),
                seasonNumber = null, // 默认「全部季」
            )
        }
        val tvId = basisMeta.id ?: basisMeta.tmdbId
        if (tvId != null) {
            loadTvDetails(tvId, null, initBindings = true)
        } else {
            _uiState.update { it.copy(loading = false) }
        }
    }

    /**
     * 影视候选搜索（剧集标题）。手动 debounce 350ms，空查询清空结果。
     * 走 [TmdbSearchRepository] 会话级内存缓存。
     */
    fun searchMedia(query: String) {
        _uiState.update { it.copy(mediaSearchQuery = query) }
        mediaSearchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _uiState.update { it.copy(mediaSearchResults = emptyList()) }
            return
        }
        mediaSearchJob = viewModelScope.launch {
            delay(350)
            try {
                _uiState.update { it.copy(loading = true, error = null) }
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val results = tmdbSearch.searchTv(q, null, language)
                val candidates = results.map { it.toMediaCandidate(MediaType.EPISODE) }
                _uiState.update { it.copy(mediaSearchResults = candidates, loading = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "搜索失败") }
            }
        }
    }

    /**
     * 选定一个搜索候选：拉 TV 详情 + 默认「全部季」集列表，**清空全部 bindings**。
     *
     * UI 调用前应先弹 AlertDialog 确认「将清空全部绑定」（仅当 bindings 非空时）。
     * 简化做法：本方法直接清空 bindings，由 UI 决定是否先弹确认。
     */
    fun selectMedia(candidate: EditMatchViewModel.MediaCandidate) {
        _uiState.update {
            it.copy(
                selectedMedia = candidate,
                mediaSearchResults = emptyList(),
                mediaSearchQuery = "",
                bindings = emptyMap(),
                seasonNumber = null, // 默认「全部季」
            )
        }
        if (candidate.mediaType == MediaType.EPISODE) {
            loadTvDetails(candidate.tmdbId, null, initBindings = false)
        }
    }

    /**
     * 切季：重新加载该季集列表，**清空全部 bindings**。
     *
     * season = null 表示「全部季」，加载所有季的集列表。
     * UI 调用前应先弹 AlertDialog 确认（仅当 bindings 非空时）。
     */
    fun setSeason(season: Int?) {
        val tvId = _uiState.value.selectedMedia?.tmdbId ?: return
        _uiState.update { it.copy(bindings = emptyMap()) }
        if (season == null) {
            loadAllSeasons(tvId, _uiState.value.numberOfSeasons, initBindings = false)
        } else {
            loadSeason(tvId, season, initBindings = false)
        }
    }

    /**
     * 智能识别（重绑定语义）：按各文件 parsed 的 (season, episodes.first) 重新分配槽位。
     *
     * 修复「点击智能没反应」：旧实现 `if (f.filePath in newBindings) return@forEach` 只填空槽，
     * 初始加载（matched 初始化）或点过「顺序」后所有文件均已绑定 → 点击智能为空操作，
     * 错误绑定（如 E12 文件被顺序填到 E01 槽）无法纠正。现改为：
     * - 可解析集号的文件一律按解析结果**重绑**（覆盖旧的错误/顺序绑定）；
     * - 「全部季」模式下 parsed.season 为 null（如 `家业.第12集`）时不再跳过，
     *   按集号在所有季中查找槽位（取最早一季）；解析季越界时同样回退全季查找；
     * - 无法解析集号的文件保留原绑定（仅当该槽未被智能结果占用），避免覆盖手动绑定；
     * - 两个文件解析到同一槽时先到者得，后到者解绑。
     *
     * 单季模式（seasonNumber != null）下，parsed.season 与当前季不符的文件不参与智能重绑。
     */
    fun smartAssignFromParsed() {
        val s = _uiState.value
        if (s.episodeList.isEmpty()) return
        val currentSeason = s.seasonNumber
        val slotByKey = s.episodeList.associateBy { SlotKey(it.seasonNumber, it.episodeNumber) }

        val newBindings = LinkedHashMap<String, SlotKey>()
        val occupied = mutableSetOf<SlotKey>()
        // 不可解析（无集号/季不符/越界）文件的原绑定，稍后仅在槽位未被占用时保留
        val keepCandidates = mutableListOf<Pair<String, SlotKey>>()

        s.files.forEach { f ->
            val parsedSeason = f.parsed.season
            val ep = f.parsed.episodes.firstOrNull()
            val seasonMismatch = currentSeason != null && parsedSeason != null && parsedSeason != currentSeason
            if (ep == null || seasonMismatch) {
                s.bindings[f.filePath]?.takeIf { it in slotByKey }?.let { keepCandidates.add(f.filePath to it) }
                return@forEach
            }
            val key = resolveSmartSlot(currentSeason, parsedSeason, ep, slotByKey)
            if (key == null || key in occupied) {
                // 越界或与他文件解析到同一槽：不绑定（重复时先到者得）
                if (key != null) return@forEach
                s.bindings[f.filePath]?.takeIf { it in slotByKey }?.let { keepCandidates.add(f.filePath to it) }
                return@forEach
            }
            newBindings[f.filePath] = key
            occupied.add(key)
        }
        // 不可解析文件：原绑定槽未被智能结果占用时保留（不覆盖正确绑定）
        keepCandidates.forEach { (fp, key) ->
            if (key !in occupied) {
                newBindings[fp] = key
                occupied.add(key)
            }
        }
        _uiState.update { it.copy(bindings = newBindings) }
    }

    /**
     * 为解析出的 (season?, episode) 定位槽位：
     * - 单季模式：固定 (currentSeason, ep)；
     * - 全部季模式：优先 (parsedSeason, ep)；无季号或解析季不在集列表时，
     *   按集号在所有季中查找（取最早一季），支持 `第12集` 这类无季号命名。
     */
    private fun resolveSmartSlot(
        currentSeason: Int?,
        parsedSeason: Int?,
        ep: Int,
        slotByKey: Map<SlotKey, EditMatchViewModel.EpisodeInfo>,
    ): SlotKey? {
        if (currentSeason != null) {
            return SlotKey(currentSeason, ep).takeIf { it in slotByKey }
        }
        if (parsedSeason != null) {
            SlotKey(parsedSeason, ep).takeIf { it in slotByKey }?.let { return it }
        }
        return slotByKey.keys.filter { it.episode == ep }.minByOrNull { it.season }
    }

    /**
     * 顺序填充：全部文件按文件名自然排序（数字感知，避免字典序导致 E10 排在 E02 前），
     * 从锚定槽起依次绑（覆盖式：先清所有 bindings 再顺序填；超过槽位数的文件留未绑定）。
     *
     * 修复「顺序填充后集数错位」：旧实现固定从 episodeList 首槽（S01E01）起填，
     * 当批次文件不从第 1 集开始（如 E12-E42）时产生固定偏移（E12 文件 → E01 槽）。
     * 现以自然排序后首个文件的解析集号锚定起始槽（E12 文件 → 从 E12 槽起填），
     * 后续文件依次落 E13、E14…；首文件无解析集号或越界时回退首槽（原行为）。
     */
    fun fillSequential() {
        val s = _uiState.value
        if (s.episodeList.isEmpty()) return
        // B10 修复：用自然排序 Comparator 替代 String.compareTo，使 S01E02 排在 S01E10 前。
        val sortedFiles = s.files.sortedWith { a, b ->
            NATURAL_FILE_NAME_COMPARATOR.compare(
                a.filePath.substringAfterLast('/'),
                b.filePath.substringAfterLast('/'),
            )
        }
        // 锚定起始槽：首个文件（自然序）解析出的集号对应槽；单季模式列表只含当季按集号匹配，
        // 全部季模式优先按 (解析季, 集号) 匹配，无季号则取该集号最早出现的槽。
        val anchorIndex = run {
            val p = sortedFiles.firstOrNull()?.parsed ?: return@run 0
            val ep = p.episodes.firstOrNull() ?: return@run 0
            val idx = s.episodeList.indexOfFirst { e ->
                e.episodeNumber == ep &&
                    (p.season == null || s.seasonNumber != null || e.seasonNumber == p.season)
            }
            if (idx >= 0) idx else 0
        }
        val newBindings = buildMap {
            for ((i, f) in sortedFiles.withIndex()) {
                val slotIdx = anchorIndex + i
                if (slotIdx >= s.episodeList.size) break
                val ep = s.episodeList[slotIdx]
                put(f.filePath, SlotKey(ep.seasonNumber, ep.episodeNumber))
            }
        }
        _uiState.update { it.copy(bindings = newBindings) }
    }

    /**
     * B10：自然排序 Comparator —— 把文件名按「文本段 + 数字段」交替拆分比较，
     * 数字段按数值比较（`02` < `10`），文本段按 String.compareTo（不区分大小写）。
     */
    private val NATURAL_FILE_NAME_COMPARATOR = java.util.Comparator<String> { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // 提取数字段
                var ni = i; while (ni < a.length && a[ni].isDigit()) ni++
                var nj = j; while (nj < b.length && b[nj].isDigit()) nj++
                val numA = a.substring(i, ni).trimLeadingZerosOrZero()
                val numB = b.substring(j, nj).trimLeadingZerosOrZero()
                // 先按长度比（长的大），长度相同按字典序
                val cmp = numA.length.compareTo(numB.length)
                if (cmp != 0) return@Comparator cmp
                if (numA != numB) return@Comparator numA.compareTo(numB)
                i = ni; j = nj
            } else {
                // 文本段逐字符比（不区分大小写）
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return@Comparator cmp
                i++; j++
            }
        }
        a.length.compareTo(b.length) // 短的排前
    }

    /** 去前导零，空串返回 "0"。 */
    private fun String.trimLeadingZerosOrZero(): String {
        val t = dropWhile { it == '0' }
        return if (t.isEmpty()) "0" else t
    }

    /** 全部解绑：清空 bindings。 */
    fun unbindAll() {
        _uiState.update { it.copy(bindings = emptyMap()) }
    }

    /**
     * 绑定结算核心（原拖拽 onDropFile，现点击交换复用同一逻辑）：
     * - targetSlot 为 null → 从 bindings 移除该 filePath（解绑）
     * - 目标槽无其他文件（或只有自己）→ bindings[filePath] = targetSlot
     * - 目标槽已有其他文件 → **交换**：找到该槽原文件 otherPath，
     *   bindings[otherPath] = 原 draggedFile 的槽(若 dragged 原本已绑定)，
     *   bindings[filePath] = targetSlot。若 dragged 原本未绑定，则仅把 otherFile 解绑(移除)。
     */
    fun onDropFile(filePath: String, targetSlot: SlotKey?) {
        val s = _uiState.value
        val newBindings = s.bindings.toMutableMap()
        if (targetSlot == null) {
            newBindings.remove(filePath)
            _uiState.update { it.copy(bindings = newBindings) }
            return
        }
        val draggedOldSlot = newBindings[filePath]
        val otherFilePath = newBindings.entries
            .firstOrNull { it.value == targetSlot && it.key != filePath }
            ?.key
        if (otherFilePath != null) {
            if (draggedOldSlot != null) {
                // 交换
                newBindings[otherFilePath] = draggedOldSlot
            } else {
                // dragged 原本未绑定，仅把 otherFile 解绑
                newBindings.remove(otherFilePath)
            }
        }
        newBindings[filePath] = targetSlot
        _uiState.update { it.copy(bindings = newBindings) }
    }

    /**
     * BottomSheet 点选：slot=null → 解绑；否则直接写入（允许重复，触发重复警告）。
     */
    fun setBinding(filePath: String, slot: SlotKey?) {
        val s = _uiState.value
        val newBindings = s.bindings.toMutableMap()
        if (slot == null) {
            newBindings.remove(filePath)
        } else {
            newBindings[filePath] = slot
        }
        _uiState.update { it.copy(bindings = newBindings) }
    }

    /**
     * 批量应用：仅回写绑定发生变更的文件（bindings[fp] != initialBindings[fp]）。
     *
     * 对每个变更文件，构造 file.copy(matched = matched.copy(type=EPISODE,
     * seasonNumber=slot.season, episodeNumbers=listOf(slot.episode),
     * episodeTitles/episodeAirDates 从 episodeList 取, seasonName 按季拉取,
     * manuallyEdited=true), status=CONFIRMED)。未绑定文件保持原 FileMatch 不动。
     * 结果写入 [UiState.batchSaved]。越界槽位拒绝并置 error。
     *
     * 「全部季」模式下 bindings 可能跨多个季，按 slot.season 分组拉取各季详情。
     */
    fun batchApply() {
        val s = _uiState.value
        val media = s.selectedMedia
        if (media == null || media.mediaType != MediaType.EPISODE) {
            _uiState.update { it.copy(error = "请先选择剧集") }
            return
        }
        if (s.episodeList.isEmpty()) {
            _uiState.update { it.copy(error = "请先加载季集列表") }
            return
        }
        val slotByKey = s.episodeList.associateBy { SlotKey(it.seasonNumber, it.episodeNumber) }

        // 越界校验
        val outOfRange = s.bindings.values.firstOrNull { it !in slotByKey }
        if (outOfRange != null) {
            _uiState.update { it.copy(error = "槽位 S${outOfRange.season}E${outOfRange.episode} 超出范围") }
            return
        }

        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val tv = tmdbDetail.getTv(media.tmdbId, language)
                // 按 slot.season 分组拉取季详情，缓存避免重复请求
                val seasonCache = mutableMapOf<Int, SeasonDetail?>()
                suspend fun getSeasonDetail(season: Int): SeasonDetail? {
                    return seasonCache.getOrPut(season) {
                        runCatching { tmdbDetail.getSeason(media.tmdbId, season, language) }.getOrNull()
                    }
                }

                val edited = s.files.map { f ->
                    val newSlot = s.bindings[f.filePath]
                    val oldSlot = s.initialBindings[f.filePath]
                    if (newSlot == null || newSlot == oldSlot) {
                        // 未绑定 或 绑定未变：保持原 FileMatch
                        f
                    } else {
                        val epInfo = slotByKey[newSlot]
                        val seasonDetail = getSeasonDetail(newSlot.season)
                        val titles = listOfNotNull(epInfo?.name?.takeIf { it.isNotBlank() })
                        val airDates = listOfNotNull(epInfo?.airDate)
                        val meta = tv.copy(
                            seasonNumber = newSlot.season,
                            episodeNumbers = listOf(newSlot.episode),
                            episodeTitles = titles,
                            episodeAirDates = airDates,
                            seasonName = seasonDetail?.name,
                        )
                        f.copy(
                            status = MatchViewModel.MatchStatus.CONFIRMED,
                            matched = meta,
                            manuallyEdited = true,
                            multiEpisodeRange = null,
                            candidates = emptyList(),
                            error = null,
                        )
                    }
                }
                _uiState.update { it.copy(loading = false, batchSaved = edited) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "批量应用失败") }
            }
        }
    }

    /** 消费 batchSaved 信号（UI 回写会话 VM 后调用）。 */
    fun consumeBatchSaved() = _uiState.update { it.copy(batchSaved = null) }

    /** 清空一次性错误提示。 */
    fun clearError() = _uiState.update { it.copy(error = null) }

    // ---- 内部：TMDB 加载 ----

    /**
     * 拉取 TV 详情获取 [UiState.numberOfSeasons]，再加载集列表。
     * [initialSeason] = null 表示「全部季」，加载所有季；非 null 加载单季。
     * 若 initBindings=true，加载完集列表后初始化 bindings。
     */
    private fun loadTvDetails(
        tvId: Int,
        initialSeason: Int?,
        initBindings: Boolean = false,
    ) {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val tv = try {
                    tmdbDetail.getTv(tvId, language)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                val total = tv?.numberOfSeasons
                val safeSeason: Int? = if (initialSeason != null && total != null && total > 0) {
                    if (initialSeason in 1..total) initialSeason else 1
                } else {
                    initialSeason // null 保留为 null（全部季）；非 null 在 total 未知时原样使用
                }
                // 用 TV 详情刷新 selectedMedia 的 overview/posterUrl：批量匹配的 basis 文件
                // 元数据可能未含 overview（搜索结果不完整），此处用完整 TV 详情补全，使
                // 已选剧集卡能展示剧集简介（与单集编辑页一致）。
                val refreshedMedia = tv?.let { meta ->
                    _uiState.value.selectedMedia?.let { cur ->
                        cur.copy(
                            overview = cur.overview?.takeIf { it.isNotBlank() }
                                ?: meta.info["overview"],
                            posterUrl = cur.posterUrl?.takeIf { it.isNotBlank() }
                                ?: meta.info["posterPath"]?.let { TmdbImages.poster(path = it) },
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        numberOfSeasons = total,
                        seasonNumber = safeSeason,
                        selectedMedia = refreshedMedia ?: it.selectedMedia,
                    )
                }
                if (safeSeason == null) {
                    loadAllSeasons(tvId, total, initBindings = initBindings)
                } else {
                    loadSeason(tvId, safeSeason, initBindings = initBindings)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载剧集详情失败") }
            }
        }
    }

    /** 加载某季集列表。若 initBindings=true，加载后初始化 bindings。 */
    private fun loadSeason(
        tvId: Int,
        season: Int,
        initBindings: Boolean = false,
    ) {
        _uiState.update { it.copy(seasonNumber = season, loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val detail = tmdbDetail.getSeason(tvId, season, language)
                val episodes = detail.episodes.map { it.toEpisodeInfo() }
                _uiState.update {
                    it.copy(episodeList = episodes, loading = false)
                }
                if (initBindings) {
                    initializeBindings()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载季失败") }
            }
        }
    }

    /**
     * 加载所有季（1..[numberOfSeasons]）的集列表，合并为单一 episodeList。
     * 某季加载失败跳过该季，不影响其他季。每集的 [EpisodeInfo.seasonNumber] 标记所属季。
     */
    private fun loadAllSeasons(
        tvId: Int,
        numberOfSeasons: Int?,
        initBindings: Boolean = false,
    ) {
        _uiState.update { it.copy(seasonNumber = null, loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val total = numberOfSeasons ?: 1
                // P5 修复：并发拉取所有季详情，避免串行等待（15 季 × 200ms = 3s → ~200ms）。
                val allEpisodes = coroutineScope {
                    val seasonDetails = (1..total).map { season ->
                        async {
                            season to (runCatching {
                                tmdbDetail.getSeason(tvId, season, language)
                            }.getOrNull())
                        }
                    }.awaitAll()
                    val episodes = mutableListOf<EditMatchViewModel.EpisodeInfo>()
                    for ((season, detail) in seasonDetails) {
                        detail ?: continue
                        detail.episodes.map { it.toEpisodeInfo() }.forEach { ep ->
                            // 强制 seasonNumber 为该季（Episode.seasonNumber 通常与 detail.seasonNumber 一致）
                            episodes.add(ep.copy(seasonNumber = detail.seasonNumber ?: season))
                        }
                    }
                    episodes
                }
                _uiState.update {
                    it.copy(episodeList = allEpisodes, loading = false)
                }
                if (initBindings) {
                    initializeBindings()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载所有季失败") }
            }
        }
    }

    /**
     * 初始化 bindings：各文件 matched.episodeNumbers.firstOrNull()，
     * 仅当 matched.type==EPISODE 且 (season, episode) 落在 episodeList 范围内。
     * 单季模式额外校验 matched.seasonNumber == 当前季；全部季模式按 matched.seasonNumber 匹配。
     * 若结果为空则自动 smartAssignFromParsed。最后同步 initialBindings 到当前 bindings（含
     * 可能的 smartAssign 结果），保证进入页面时 dirty=false。
     */
    private fun initializeBindings() {
        val s = _uiState.value
        if (s.episodeList.isEmpty()) return
        val currentSeason = s.seasonNumber
        val slotByKey = s.episodeList.associateBy { SlotKey(it.seasonNumber, it.episodeNumber) }
        val bindings = buildMap {
            s.files.forEach { f ->
                val m = f.matched ?: return@forEach
                if (m.type != MediaType.EPISODE) return@forEach
                val season = m.seasonNumber ?: return@forEach
                if (currentSeason != null && season != currentSeason) return@forEach
                val ep = m.episodeNumbers.firstOrNull() ?: return@forEach
                val key = SlotKey(season, ep)
                if (key in slotByKey) put(f.filePath, key)
            }
        }
        _uiState.update { it.copy(bindings = bindings) }
        if (bindings.isEmpty()) {
            smartAssignFromParsed()
        }
        // 同步 initialBindings 到当前 bindings（post-init / post-smartAssign）
        _uiState.update { it.copy(initialBindings = it.bindings.toMap()) }
    }

    // ---- 内部：工具 ----

    /**
     * 校验 API Key 是否已配置；空 key 写错误并返回 null，调用方据此提前返回。
     */
    private suspend fun checkApiKeyOrError(): Boolean? {
        val apiKey = settings.apiKey.first()
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(loading = false, error = "请先在设置中填入 TMDB API Key") }
            return null
        }
        return true
    }

    /** Episode → UI 友好的 EpisodeInfo（复制自 EditMatchViewModel，不跨 VM 注入）。 */
    private fun Episode.toEpisodeInfo(): EditMatchViewModel.EpisodeInfo = EditMatchViewModel.EpisodeInfo(
        episodeNumber = episodeNumber ?: 0,
        seasonNumber = seasonNumber ?: 0,
        name = name?.takeIf { it.isNotBlank() } ?: "第 ${episodeNumber ?: 0} 集",
        overview = overview ?: "",
        airDate = airDate,
        stillUrl = stillPath?.let { TmdbImages.still(path = it) },
    )

    /** MediaMetadata → UI 友好的 MediaCandidate（复制自 EditMatchViewModel）。 */
    private fun MediaMetadata.toMediaCandidate(type: MediaType): EditMatchViewModel.MediaCandidate =
        EditMatchViewModel.MediaCandidate(
            tmdbId = tmdbId ?: id ?: 0,
            name = name ?: "",
            year = year,
            overview = info["overview"],
            posterUrl = info["posterPath"]?.let { TmdbImages.poster(path = it) },
            mediaType = type,
        )
}
