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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Edit Match ViewModel（Task 2.5.1–2.5.3）。
 *
 * 单条手动修正编排：切换电影/剧集 → 搜索候选 → 选定 → （剧集）选季选集 → 保存。
 * 批量编辑已迁移至 [BatchMatchViewModel]（集位槽模型）。
 *
 * 数据流：[MatchSessionViewModel]（Activity 作用域）持有 matchedFiles 快照；
 * EditMatchScreen 按导航参数 `matchIndex` 取出对应 [MatchViewModel.FileMatch] 调 [load]，
 * 保存后由 EditMatchScreen 回写 [MatchSessionViewModel]。本 VM 不直接持有会话 VM，
 * 避免跨 ViewModel 注入复杂度（Hilt 不便将一个 @HiltViewModel 注入另一个）。
 *
 * 缓存：搜索类请求走 [TmdbSearchRepository]（会话级内存缓存），详情类请求走
 * [TmdbDetailRepository]（7 天数据库缓存），避免重复网络请求。设置页「清空 TMDB 缓存」可清空。
 *
 * 剧集季选择：选定剧集后拉 TV 详情获取 [UiState.numberOfSeasons]，季选择器列出
 * 1..numberOfSeasons 供用户选择（避免猜错季号导致 404）。用户不选具体季（null=全部）时，
 * 合并加载所有季集列表供展示，保存时遍历查找匹配季。
 *
 * 安全：API Key 仅从 [SettingsRepository] 读取（经 [TmdbClientProvider] 内部构造 TmdbClient），
 * 不进 UI 状态或日志。
 */
@HiltViewModel
class EditMatchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tmdbSearch: TmdbSearchRepository,
    private val tmdbDetail: TmdbDetailRepository,
) : ViewModel() {

    /** UI 友好的集信息（从 TMDB [Episode] 映射）。 */
    data class EpisodeInfo(
        val episodeNumber: Int,
        val seasonNumber: Int = 0,
        val name: String,
        val overview: String,
        val airDate: String?,
        val stillUrl: String?,
    )

    /** 影视搜索候选（电影/剧集通用，带海报）。 */
    data class MediaCandidate(
        val tmdbId: Int,
        val name: String,
        val year: Int?,
        val overview: String?,
        val posterUrl: String?,
        val mediaType: MediaType,
    )

    /** 编辑页 UI 状态。 */
    data class UiState(
        val currentMatch: MatchViewModel.FileMatch? = null,
        val mediaType: MediaType = MediaType.MOVIE,
        val seasonNumber: Int? = null,
        /** 剧集总季数（选定剧集后从 TV 详情拉取），用于季选择器列出可选季；null=未拉取。 */
        val numberOfSeasons: Int? = null,
        val episodeList: List<EpisodeInfo> = emptyList(),
        val selectedEpisodeNumbers: Set<Int> = emptySet(),
        val mediaSearchQuery: String = "",
        val mediaSearchResults: List<MediaCandidate> = emptyList(),
        val selectedMedia: MediaCandidate? = null,
        /** 选定候选时保留的上次搜索结果，供点击已选目标时回显，避免重复搜索。 */
        val previousCandidates: List<MediaCandidate> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val saved: MatchViewModel.FileMatch? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 任务规约要求的细分 StateFlow（由 uiState 派生，便于外部按字段订阅）。
    val currentMatch: StateFlow<MatchViewModel.FileMatch?> =
        _uiState.map { it.currentMatch }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mediaType: StateFlow<MediaType> =
        _uiState.map { it.mediaType }.stateIn(viewModelScope, SharingStarted.Eagerly, MediaType.MOVIE)
    val seasonNumber: StateFlow<Int?> =
        _uiState.map { it.seasonNumber }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val numberOfSeasons: StateFlow<Int?> =
        _uiState.map { it.numberOfSeasons }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val episodeList: StateFlow<List<EpisodeInfo>> =
        _uiState.map { it.episodeList }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedEpisodeNumbers: StateFlow<Set<Int>> =
        _uiState.map { it.selectedEpisodeNumbers }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var mediaSearchJob: Job? = null

    /**
     * 由 EditMatchScreen 在进入时调用：载入待编辑的 [fileMatch]。若已有剧集匹配，
     * 预加载该季集列表与剧集总季数。
     *
     * 同时把 [MatchViewModel.FileMatch.candidates] 转入 [UiState.previousCandidates]，
     * 使点击已选目标时能直接回显「之前的匹配结果」而无需重新搜索。
     */
    fun load(fileMatch: MatchViewModel.FileMatch) {
        // Task 8.2：仅当已加载相同文件时跳过，否则重新加载（实例被复用编辑不同文件时正常刷新）。
        if (_uiState.value.currentMatch?.filePath == fileMatch.filePath) return
        val matched = fileMatch.matched
        val type = matched?.type
            ?: if (fileMatch.parsed.isEpisode) MediaType.EPISODE else MediaType.MOVIE
        val prevCandidates = fileMatch.candidates.map { it.toMediaCandidate() }
        _uiState.update { s ->
            s.copy(
                currentMatch = fileMatch,
                mediaType = type,
                // 默认「全部季」（null）：拉取 numberOfSeasons 并合并加载所有季集列表。
                seasonNumber = null,
                selectedEpisodeNumbers = (matched?.episodeNumbers ?: fileMatch.parsed.episodes).toSet(),
                selectedMedia = matched?.toMediaCandidate(type),
                previousCandidates = prevCandidates,
                loading = true,
                error = null,
            )
        }
        val tvId = matched?.id ?: matched?.tmdbId
        if (type == MediaType.EPISODE && tvId != null) {
            loadTvDetails(tvId, null)
        } else {
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** 切换电影/剧集类型（Task 2.5.1）。切换时清空已选候选与集列表。 */
    fun switchMediaType(type: MediaType) {
        _uiState.update {
            it.copy(
                mediaType = type,
                // 默认「全部季」（null）
                seasonNumber = null,
                numberOfSeasons = if (type == MediaType.EPISODE) it.numberOfSeasons else null,
                episodeList = if (type == MediaType.EPISODE) it.episodeList else emptyList(),
                selectedEpisodeNumbers = if (type == MediaType.EPISODE) it.selectedEpisodeNumbers else emptySet(),
                selectedMedia = null,
                mediaSearchResults = emptyList(),
                previousCandidates = emptyList(),
                mediaSearchQuery = "",
            )
        }
    }

    /**
     * 影视候选搜索（电影/剧集标题）。手动 debounce 350ms，避免连击打爆 API。
     * 空查询清空结果。走 [TmdbSearchRepository] 会话级内存缓存。
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
                val type = _uiState.value.mediaType
                val results = if (type == MediaType.EPISODE) {
                    tmdbSearch.searchTv(q, null, language)
                } else {
                    tmdbSearch.searchMovie(q, null, language)
                }
                val candidates = results.map { it.toMediaCandidate(type) }
                _uiState.update { it.copy(mediaSearchResults = candidates, loading = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "搜索失败") }
            }
        }
    }

    /**
     * 选定一个搜索候选；剧集则拉 TV 详情获取总季数并加载首季集列表。
     *
     * 季号初始值：文件名解析的季号（若 <= numberOfSeasons）否则 1；无解析季号时默认 1。
     * 用户可在季选择器改为「全部」（null），合并加载所有季集列表。
     *
     * 选定时把当前 [mediaSearchResults] 转入 [UiState.previousCandidates]，便于用户
     * 点击已选目标时回显「之前的匹配结果」而无需重新搜索。
     */
    fun selectMedia(candidate: MediaCandidate) {
        _uiState.update {
            it.copy(
                selectedMedia = candidate,
                previousCandidates = it.mediaSearchResults,
                mediaSearchResults = emptyList(),
                mediaSearchQuery = "",
            )
        }
        if (candidate.mediaType == MediaType.EPISODE) {
            // 默认「全部季」（null）：合并加载所有季集列表。
            loadTvDetails(candidate.tmdbId, null)
        }
    }

    /**
     * 取消当前已选目标（点击已选摘要触发）：清空 [UiState.selectedMedia]，
     * 并把 [UiState.previousCandidates] 回填到 [UiState.mediaSearchResults]，
     * 使「之前的匹配结果」直接显示，用户可点选其一退出而不必重新搜索匹配。
     */
    fun clearSelectedMedia() {
        _uiState.update {
            it.copy(
                selectedMedia = null,
                mediaSearchResults = it.previousCandidates,
                previousCandidates = emptyList(),
                mediaSearchQuery = "",
            )
        }
    }

    /**
     * 进入重新选择模式（点击已选摘要触发）：不清空 [UiState.selectedMedia]，
     * 保留季号/集列表以便季选择器在重新选择视图内仍可展示与切换；
     * 仅把 [UiState.previousCandidates] 回填到 [UiState.mediaSearchResults]，
     * 使「之前的匹配结果」直接显示，用户可点选其一或重新搜索。
     *
     * 与 [clearSelectedMedia] 的区别：本方法不清 selectedMedia，因此集列表/季号
     * 仍可在主页面回显后用于选集；用户选了新候选或关闭重新选择视图即可退出。
     */
    fun enterReselectMode() {
        _uiState.update {
            it.copy(
                mediaSearchResults = it.previousCandidates,
                previousCandidates = emptyList(),
                mediaSearchQuery = "",
            )
        }
    }

    /**
     * 改变季号并重新加载集列表。
     *
     * 传 null 表示「全部季」：合并加载所有季的集列表（参考 BatchMatchViewModel.loadAllSeasons），
     * 使集面板有数据可展示，而非空白。保存时仍由 [findSeasonContainingEpisodes] 遍历查找。
     */
    fun setSeason(season: Int?) {
        val tvId = _uiState.value.selectedMedia?.tmdbId
            ?: _uiState.value.currentMatch?.matched?.id
            ?: _uiState.value.currentMatch?.matched?.tmdbId
            ?: return
        if (season == null) {
            loadAllSeasons(tvId, _uiState.value.numberOfSeasons)
        } else {
            loadSeason(tvId, season)
        }
    }

    /**
     * 拉取 TV 详情获取 [UiState.numberOfSeasons]，再加载 [initialSeason] 的集列表。
     *
     * 先 getTv 拿总季数（供季选择器列出 1..numberOfSeasons），再 loadSeason 加载初始季。
     * [initialSeason] 为 null（「全部季」）时调用 [loadAllSeasons] 合并加载所有季集列表。
     * getTv 失败时仍尝试加载初始季（降级）。
     */
    private fun loadTvDetails(tvId: Int, initialSeason: Int?) {
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
                // 校验初始季号是否在可用范围内；超出或为 null 时回退到「全部季」。
                val safeSeason: Int? = if (initialSeason != null && total != null && total > 0) {
                    if (initialSeason in 1..total) initialSeason else null
                } else {
                    initialSeason // null 保留为 null（全部季）；非 null 在 total 未知时原样使用
                }
                _uiState.update {
                    it.copy(
                        numberOfSeasons = total,
                        seasonNumber = safeSeason,
                    )
                }
                if (safeSeason == null) {
                    // 「全部季」：合并加载所有季集列表（与 BatchMatch 一致）
                    loadAllSeasons(tvId, total)
                } else {
                    loadSeason(tvId, safeSeason)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载剧集详情失败") }
            }
        }
    }

    /** 加载某季集列表（Task 2.5.1/2.5.3）。走 [TmdbDetailRepository] 数据库缓存。 */
    fun loadSeason(tvId: Int, season: Int) {
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
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载季失败") }
            }
        }
    }

    /**
     * 加载所有季（1..[numberOfSeasons]）的集列表，合并为单一 [episodeList]。
     *
     * 参考 [BatchMatchViewModel.loadAllSeasons]：某季加载失败跳过，不影响其他季；
     * 每集的 [EpisodeInfo.seasonNumber] 标记所属季，便于面板展示季前缀。
     */
    private fun loadAllSeasons(tvId: Int, numberOfSeasons: Int?) {
        _uiState.update { it.copy(seasonNumber = null, loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val total = numberOfSeasons ?: 1
                val allEpisodes = mutableListOf<EpisodeInfo>()
                for (season in 1..total) {
                    val detail = runCatching {
                        tmdbDetail.getSeason(tvId, season, language)
                    }.getOrNull() ?: continue
                    detail.episodes.map { it.toEpisodeInfo() }.forEach { ep ->
                        allEpisodes.add(ep.copy(seasonNumber = detail.seasonNumber ?: season))
                    }
                }
                _uiState.update {
                    it.copy(episodeList = allEpisodes, loading = false)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "加载所有季失败") }
            }
        }
    }

    /** 勾选/取消勾选单集（Task 2.5.2）。单选互斥：选中替换，再点取消。 */
    fun toggleEpisode(num: Int) {
        _uiState.update { s ->
            s.copy(
                selectedEpisodeNumbers =
                    if (s.selectedEpisodeNumbers == setOf(num)) emptySet() else setOf(num),
            )
        }
    }

    /**
     * 应用单条编辑（Task 2.5.1/2.5.2/2.5.3）。
     *
     * 电影 → 拉详情写回；剧集 → 按所选集号（单个或空）合并元数据写回。
     * 季号为 null（「全部」）时遍历所有季查找匹配集号，避免猜错季号导致 404。
     * 结果通过 [UiState.saved] 暴露，由 EditMatchScreen 回写会话 VM。
     */
    fun applyEdit() {
        val s = _uiState.value
        val current = s.currentMatch ?: return
        val media = s.selectedMedia
        if (media == null) {
            _uiState.update { it.copy(error = if (s.mediaType == MediaType.EPISODE) "请先选择剧集" else "请先选择电影") }
            return
        }
        if (s.mediaType == MediaType.EPISODE && s.selectedEpisodeNumbers.isEmpty()) {
            _uiState.update { it.copy(error = "请至少选择一集") }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                checkApiKeyOrError() ?: return@launch
                val language = settings.language.first()
                val meta = if (s.mediaType == MediaType.EPISODE) {
                    buildEpisodeMetadata(
                        media.tmdbId,
                        s.seasonNumber, s.selectedEpisodeNumbers, language,
                        s.numberOfSeasons,
                    )
                } else {
                    tmdbDetail.getMovie(media.tmdbId, language)
                }
                val edited = current.copy(
                    status = MatchViewModel.MatchStatus.CONFIRMED,
                    matched = meta,
                    manuallyEdited = true,
                    multiEpisodeRange = null,
                    candidates = emptyList(),
                    error = null,
                )
                _uiState.update { it.copy(loading = false, saved = edited) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { it.copy(loading = false, error = t.message ?: "保存失败") }
            }
        }
    }

    // ---- 生命周期收尾 ----

    fun consumeSaved() = _uiState.update { it.copy(saved = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    /** 取消编辑：清空待保存信号与错误，不写回。 */
    fun cancel() {
        _uiState.update { it.copy(saved = null, error = null, loading = false) }
    }

    // ---- 内部工具 ----

    /**
     * 校验 API Key 是否已配置；空 key 写错误并返回 null，调用方据此提前返回。
     * 实际 [TmdbClient] 由 [TmdbClientProvider] 内部按 apiKey/baseUrl 构造。
     */
    private suspend fun checkApiKeyOrError(): Boolean? {
        val apiKey = settings.apiKey.first()
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(loading = false, error = "请先在设置中填入 TMDB API Key") }
            return null
        }
        return true
    }

    /**
     * 构造剧集 [MediaMetadata]：拉 TV 详情 + 当季详情，按所选集号合并
     * `seasonNumber / episodeNumbers / episodeTitles(多集 A & B) / episodeAirDates / seasonName`。
     *
     * [seasonNumber] 为 null（「全部季」）时遍历 [numberOfSeasons] 所有季查找包含所选集号的季，
     * 找到后用该季详情合并；找不到则回退季号 1。对齐 [PreviewViewModel.fetchDetail] 的规则。
     */
    private suspend fun buildEpisodeMetadata(
        tvId: Int,
        seasonNumber: Int?,
        episodes: Set<Int>,
        language: String,
        numberOfSeasons: Int?,
    ): MediaMetadata {
        val tv = tmdbDetail.getTv(tvId, language)
        val (resolvedSeason, season) = if (seasonNumber != null) {
            seasonNumber to runCatching { tmdbDetail.getSeason(tvId, seasonNumber, language) }.getOrNull()
        } else {
            // 「全部季」：遍历所有季查找包含所选集号的季
            findSeasonContainingEpisodes(tvId, episodes, numberOfSeasons, language)
        }
        val byNum = season?.episodes
            ?.filter { it.episodeNumber != null }
            ?.associateBy { it.episodeNumber!! }
            ?: emptyMap()
        val sortedEps = episodes.sorted()
        val titles = sortedEps.mapNotNull { byNum[it]?.name }
        val airDates = sortedEps.mapNotNull { byNum[it]?.airDate }
        return tv.copy(
            seasonNumber = resolvedSeason,
            episodeNumbers = sortedEps,
            episodeTitles = if (titles.size > 1) listOf(titles.joinToString(" & ")) else titles,
            episodeAirDates = airDates,
            seasonName = season?.name,
        )
    }

    /**
     * 遍历 1..[numberOfSeasons] 查找包含 [episodes] 中集号的季。
     *
     * 每季 getSeason 用 runCatching 容错（某季不存在返回 null 跳过，不抛 404）。
     * 找到第一个包含所有选中集号的季即返回；找不到则回退 (1, null)。
     */
    private suspend fun findSeasonContainingEpisodes(
        tvId: Int,
        episodes: Set<Int>,
        numberOfSeasons: Int?,
        language: String,
    ): Pair<Int, SeasonDetail?> {
        val maxSeason = numberOfSeasons ?: return 1 to null
        if (maxSeason <= 0 || episodes.isEmpty()) return 1 to null
        for (s in 1..maxSeason) {
            val season = runCatching { tmdbDetail.getSeason(tvId, s, language) }.getOrNull() ?: continue
            val epNums = season.episodes.mapNotNull { it.episodeNumber }.toSet()
            if (episodes.all { it in epNums }) return s to season
        }
        return 1 to null
    }

    private fun Episode.toEpisodeInfo(): EpisodeInfo = EpisodeInfo(
        episodeNumber = episodeNumber ?: 0,
        seasonNumber = seasonNumber ?: 0,
        name = name?.takeIf { it.isNotBlank() } ?: "第 ${episodeNumber ?: 0} 集",
        overview = overview ?: "",
        airDate = airDate,
        stillUrl = stillPath?.let { TmdbImages.still(path = it) },
    )

    private fun MediaMetadata.toMediaCandidate(type: MediaType): MediaCandidate = MediaCandidate(
        tmdbId = tmdbId ?: id ?: 0,
        name = name ?: "",
        year = year,
        overview = info["overview"],
        posterUrl = info["posterPath"]?.let { TmdbImages.poster(path = it) },
        mediaType = type,
    )

    /** 把待确认候选 [MatchViewModel.Candidate] 转为 [MediaCandidate]，用于回显「之前的匹配结果」。 */
    private fun MatchViewModel.Candidate.toMediaCandidate(): MediaCandidate = MediaCandidate(
        tmdbId = candidate.tmdbId,
        name = candidate.name,
        year = candidate.year,
        overview = overview,
        posterUrl = posterUrl,
        mediaType = candidate.mediaType,
    )
}
