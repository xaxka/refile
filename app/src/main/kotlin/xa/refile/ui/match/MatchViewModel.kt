package xa.refile.ui.match

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.matcher.ConfidenceScorer
import xa.refile.core.matcher.MatchCandidate
import xa.refile.core.matcher.MatchDecision
import xa.refile.core.matcher.MatchEngine
import xa.refile.core.matcher.ScoredCandidate
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.FilenameParser
import xa.refile.core.parser.ParsedFilename
import xa.refile.core.tmdb.TmdbImages
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbCacheRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TMDB 匹配编排 ViewModel（计划 §M2 Task 2.4）。
 *
 * 流程：浏览器选中的视频路径 → [FilenameParser] 解析 → 判定类型（自动）
 * → [TmdbCacheRepository] 搜索 → [MatchEngine] 决策：
 * - [MatchDecision.Auto]：拉详情（剧集补 [MediaMetadata.seasonNumber]/[MediaMetadata.episodeTitles]）→ 自动✅
 * - [MatchDecision.NeedsConfirm]：待确认⚠️，保留候选供 UI 选择
 * - [MatchDecision.NoMatch]：无匹配❌，用户可手动搜索
 *
 * Task 2.3.4：所有 TMDB 访问改走 [TmdbCacheRepository]（详情类请求自动走 Room 缓存，
 * 搜索类请求透传）。API Key 仅从 [SettingsRepository] 读取用于网络请求，绝不进入 UI 状态或日志。
 */
@HiltViewModel
class MatchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tmdbCache: TmdbCacheRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 匹配方式：自动识别 / 强制电影 / 强制剧集。 */
    enum class MatchType { AUTO, MOVIE, TV }

    /** 单文件匹配状态。 */
    enum class MatchStatus { AUTO, PENDING, NO_MATCH, CONFIRMED }

    /** 整体进度。 */
    sealed class Progress {
        data object Idle : Progress()
        data class Running(val current: Int, val total: Int) : Progress()
        data object Done : Progress()
    }

    /**
     * UI 候选：在 [MatchCandidate] 之上附加海报 URL、简介首行与置信度得分，
     * 供待确认列表展示（[MatchCandidate] 本身无海报字段）。
     */
    data class Candidate(
        val candidate: MatchCandidate,
        val posterUrl: String?,
        val overview: String?,
        val score: Double,
    )

    /**
     * 单文件匹配结果。
     * @param filePath 视频完整路径
     * @param parsed 文件名解析结果
     * @param status 匹配状态
     * @param matched 已拉取详情的元数据（AUTO/CONFIRMED 时非空）
     * @param candidates 待确认候选（PENDING 时非空）
     * @param error 搜索/拉详情异常信息
     * @param manuallyEdited 是否经 Edit Match 手动修正（Task 2.5.1）
     * @param multiEpisodeRange 多集组合显示标签，如 `S01E01-E02` / `S01E01,E03`（Task 2.5.2）；
     *                           单集或电影为 null。便于 UI 与预览直接渲染，无需重新计算。
     */
    data class FileMatch(
        val filePath: String,
        val parsed: ParsedFilename,
        val status: MatchStatus,
        val matched: MediaMetadata? = null,
        val candidates: List<Candidate> = emptyList(),
        val error: String? = null,
        val manuallyEdited: Boolean = false,
        val multiEpisodeRange: String? = null,
    )

    /** 匹配页 UI 状态。 */
    data class UiState(
        val selectedFiles: List<String> = emptyList(),
        val matchType: MatchType = MatchType.AUTO,
        val progress: Progress = Progress.Idle,
        val results: List<FileMatch> = emptyList(),
        val pending: List<FileMatch> = emptyList(),
        val error: String? = null,
        val manualSearchingPath: String? = null,
    ) {
        /** 待确认是否已全部处理（无 PENDING 残留）。 */
        val allResolved: Boolean get() = pending.none { it.status == MatchStatus.PENDING }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val parser = FilenameParser()
    private val engine = MatchEngine()
    private val scorer = ConfidenceScorer()

    /** 接收浏览器选中的视频完整路径列表。 */
    fun setFiles(files: List<String>) {
        _uiState.update {
            if (it.selectedFiles == files) {
                it
            } else {
                // 文件列表变化时才清空会话搜索缓存：同一批文件重新匹配时保留缓存，
                // 搜索/详情命中内存缓存或 Room 持久缓存，避免重复联网（用户反馈"重复匹配仍联网"）。
                tmdbCache.clearSessionCache()
                it.copy(
                    selectedFiles = files,
                    progress = Progress.Idle,
                    results = emptyList(),
                    pending = emptyList(),
                    error = null,
                    manualSearchingPath = null,
                )
            }
        }
    }

    /** 重置匹配状态，从预览页返回时调用，清空上次匹配结果。 */
    fun resetMatch() {
        _uiState.update {
            it.copy(
                progress = Progress.Idle,
                results = emptyList(),
                pending = emptyList(),
                error = null,
                manualSearchingPath = null,
            )
        }
    }

    /** 阶段 1：用户切换匹配方式。 */
    fun setMatchType(type: MatchType) {
        _uiState.update { it.copy(matchType = type) }
    }

    /** 清除顶部一次性错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 阶段 1 → 阶段 2：开始匹配。
     *
     * 1. 读 API Key（空 → 报错不进行）。
     * 2. 逐文件解析 → 判定类型 → 搜索 → 决策 → 拉详情。
     * 3. 实时更新 [Progress.Running] 与 results/pending。
     */
    fun startMatch(forceType: MatchType) {
        val files = _uiState.value.selectedFiles
        if (files.isEmpty()) {
            _uiState.update { it.copy(error = "未选择任何文件") }
            return
        }
        _uiState.update {
            it.copy(
                matchType = forceType,
                progress = Progress.Running(current = 0, total = files.size),
                results = emptyList(),
                pending = emptyList(),
                error = null,
            )
        }
        viewModelScope.launch {
            // 会话搜索缓存的清空已移至 setFiles：仅当文件列表变化时清空，
            // 同一批文件重新匹配时保留缓存命中，避免重复联网。
            val apiKey = settings.apiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(progress = Progress.Idle, error = "请先在设置中填入 TMDB API Key")
                }
                return@launch
            }
            val language = settings.language.first()

            val results = mutableListOf<FileMatch>()
            val pending = mutableListOf<FileMatch>()

            files.forEachIndexed { index, path ->
                val fileName = path.substringAfterLast('/')
                val parsed = parser.parse(fileName)
                val type = resolveType(forceType, parsed)

                val fm = try {
                    runMatchForFile(tmdbCache, parsed, type, language, path)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    FileMatch(path, parsed, MatchStatus.NO_MATCH, error = t.message ?: "未知错误")
                }

                if (fm.status == MatchStatus.AUTO || fm.status == MatchStatus.CONFIRMED) {
                    results.add(fm)
                } else {
                    pending.add(fm)
                }
                _uiState.update {
                    it.copy(
                        progress = Progress.Running(current = index + 1, total = files.size),
                        results = results.toList(),
                        pending = pending.toList(),
                    )
                }
            }
            _uiState.update { it.copy(progress = Progress.Done) }
        }
    }

    /** 强制 > 自动（按季/集推断）。 */
    private fun resolveType(forceType: MatchType, parsed: ParsedFilename): MatchType =
        when (forceType) {
            MatchType.MOVIE -> MatchType.MOVIE
            MatchType.TV -> MatchType.TV
            MatchType.AUTO ->
                if (parsed.season != null || parsed.episodes.isNotEmpty()) MatchType.TV else MatchType.MOVIE
        }

    /**
     * 单文件匹配：搜索 → 决策 → 拉详情。
     *
     * P2.4：若 [ParsedFilename.imdbId] 非空，优先走 [TmdbCacheRepository.findByImdbId] 精确查找，
     * 命中即视为权威候选直接 Auto（IMDb ID 比 ParsedFilename.title 更可靠）。
     *
     * P2.2：若首决策为 NeedsConfirm 且 parsed 携带 SxE，预拉 top 候选的季详情做 SxE 互校，
     * 命中则填充 candidate.season/episodes 重打分，可能升级为 Auto。
     */
    private suspend fun runMatchForFile(
        tmdbCache: TmdbCacheRepository,
        parsed: ParsedFilename,
        type: MatchType,
        language: String,
        filePath: String,
    ): FileMatch {
        // P2.4：IMDb ID 优先级查找
        val imdbId = parsed.imdbId
        if (!imdbId.isNullOrBlank()) {
            val findMediaType = when (type) {
                MatchType.MOVIE -> MediaType.MOVIE
                MatchType.TV -> MediaType.EPISODE
                MatchType.AUTO -> null
            }
            val found = try {
                tmdbCache.findByImdbId(imdbId, findMediaType, language)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
            if (found != null) {
                val candidate = found.toMatchCandidate()
                val meta = fetchDetail(tmdbCache, candidate, parsed, language)
                return FileMatch(
                    filePath = filePath,
                    parsed = parsed,
                    status = MatchStatus.AUTO,
                    matched = meta,
                    candidates = listOf(mediaMetadataToCandidate(found, 1.0)),
                )
            }
            // IMDb 未命中，回退标题搜索
        }

        val title = parsed.title?.takeIf { it.isNotBlank() } ?: ""
        // P2.6：主标题 + 别名分别搜 TMDB，合并去重候选（中英混合文件名 `寒战1994 Cold War` 场景）。
        val searchTitles = listOf(title) + parsed.titleAliases.filter { it.isNotBlank() }
        val seenIds = mutableSetOf<Int?>()
        val searchResults = buildList {
            searchTitles.forEach { q ->
                val rs = if (type == MatchType.TV) {
                    tmdbCache.searchTv(q, parsed.year, language)
                } else {
                    tmdbCache.searchMovie(q, parsed.year, language)
                }
                rs.forEach { if (seenIds.add(it.id)) add(it) }
            }
        }
        val candidates = searchResults.map { it.toMatchCandidate() }
        val decision = engine.match(parsed, candidates)
        // P2.2：SxE 互校 — NeedsConfirm 时若 parsed 有 SxE，预拉季详情验证后重打分
        val finalDecision = if (decision is MatchDecision.NeedsConfirm) {
            enrichWithSxe(tmdbCache, parsed, decision.candidates, language)?.let { enriched ->
                engine.match(parsed, enriched)
            } ?: decision
        } else {
            decision
        }
        return when (finalDecision) {
            is MatchDecision.Auto -> {
                val meta = fetchDetail(tmdbCache, finalDecision.best.candidate, parsed, language)
                FileMatch(
                    filePath = filePath,
                    parsed = parsed,
                    status = MatchStatus.AUTO,
                    matched = meta,
                    candidates = listOf(toCandidate(finalDecision.best, searchResults)),
                )
            }
            is MatchDecision.NeedsConfirm -> FileMatch(
                filePath = filePath,
                parsed = parsed,
                status = MatchStatus.PENDING,
                candidates = finalDecision.candidates.map { toCandidate(it, searchResults) },
            )
            MatchDecision.NoMatch -> FileMatch(
                filePath = filePath,
                parsed = parsed,
                status = MatchStatus.NO_MATCH,
            )
        }
    }

    /**
     * P2.2：SxE 互校 — 预拉 top 候选的季详情，验证 parsed.season/episodes 是否存在于该季。
     *
     * 仅当 [parsed] 携带 season+episodes、且最高分候选为剧集时尝试；命中则返回填充了 SxE 的候选列表
     * （交由调用方重打分，sxeBonus 会抬升 top 得分，可能从 NeedsConfirm 升级为 Auto）。
     * 未命中或不可验证返回 null，调用方沿用原决策。
     */
    private suspend fun enrichWithSxe(
        tmdbCache: TmdbCacheRepository,
        parsed: ParsedFilename,
        scored: List<ScoredCandidate>,
        language: String,
    ): List<MatchCandidate>? {
        val seasonNum = parsed.season ?: return null
        if (parsed.episodes.isEmpty()) return null
        val top = scored.firstOrNull()?.candidate ?: return null
        if (top.mediaType != MediaType.EPISODE) return null
        val season = try {
            tmdbCache.getSeason(top.tmdbId, seasonNum, language)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        } ?: return null
        val seasonEps = season.episodes.mapNotNull { it.episodeNumber }.toSet()
        if (seasonEps.intersect(parsed.episodes.toSet()).isEmpty()) return null
        // SxE 验证通过：仅填充 top 候选，其余保持原样
        return scored.map { sc ->
            if (sc.candidate.tmdbId == top.tmdbId) {
                sc.candidate.copy(season = seasonNum, episodes = parsed.episodes)
            } else {
                sc.candidate
            }
        }
    }

    /** 把 [MediaMetadata]（IMDb 命中或搜索结果）转为 UI [Candidate]，海报/简介从 info 取。 */
    private fun mediaMetadataToCandidate(meta: MediaMetadata, score: Double): Candidate {
        val posterUrl = meta.info["posterPath"]?.let { TmdbImages.poster(path = it) }
        val overview = meta.info["overview"]
        return Candidate(meta.toMatchCandidate(), posterUrl, overview, score)
    }

    /**
     * 阶段 3：用户从候选中选择一个 → 拉详情填充 → 状态 CONFIRMED，从 pending 移到 results。
     */
    fun confirmMatch(filePath: String, candidate: MatchCandidate) {
        val current = _uiState.value
        val fm = current.pending.firstOrNull { it.filePath == filePath } ?: return
        _uiState.update { it.copy(manualSearchingPath = filePath) }
        viewModelScope.launch {
            val apiKey = settings.apiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(manualSearchingPath = null, error = "请先在设置中填入 TMDB API Key")
                }
                return@launch
            }
            val language = settings.language.first()
            val meta = try {
                fetchDetail(tmdbCache, candidate, fm.parsed, language)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
            val confirmed = fm.copy(
                status = MatchStatus.CONFIRMED,
                matched = meta,
                error = if (meta == null) "拉取详情失败" else null,
            )
            _uiState.update { s ->
                s.copy(
                    manualSearchingPath = null,
                    pending = s.pending.filterNot { it.filePath == filePath },
                    results = s.results + confirmed,
                )
            }
        }
    }

    /**
     * 阶段 3：待确认/无匹配条目手动搜索关键词，刷新候选列表（按置信度排序）。
     */
    fun manualSearch(filePath: String, query: String, type: MatchType) {
        val q = query.trim()
        if (q.isEmpty()) return
        _uiState.update { it.copy(manualSearchingPath = filePath) }
        viewModelScope.launch {
            val apiKey = settings.apiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(manualSearchingPath = null, error = "请先在设置中填入 TMDB API Key")
                }
                return@launch
            }
            val language = settings.language.first()
            val fm = _uiState.value.pending.firstOrNull { it.filePath == filePath }
            val parsed = fm?.parsed ?: parser.parse(q)
            try {
                val results = if (type == MatchType.TV) {
                    tmdbCache.searchTv(q, null, language)
                } else {
                    tmdbCache.searchMovie(q, null, language)
                }
                val candidates = results.map { it.toMatchCandidate() }
                val scored = candidates
                    .map { ScoredCandidate(it, scorer.score(parsed, it)) }
                    .sortedByDescending { it.score }
                val updated = (fm?.copy(status = MatchStatus.PENDING) ?: FileMatch(
                    filePath = filePath,
                    parsed = parsed,
                    status = MatchStatus.PENDING,
                )).copy(
                    candidates = scored.map { toCandidate(it, results) },
                    error = if (scored.isEmpty()) "无搜索结果" else null,
                )
                _uiState.update { s ->
                    s.copy(
                        manualSearchingPath = null,
                        pending = s.pending.map { if (it.filePath == filePath) updated else it },
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _uiState.update { s ->
                    s.copy(
                        manualSearchingPath = null,
                        pending = s.pending.map {
                            if (it.filePath == filePath) it.copy(error = t.message ?: "搜索失败") else it
                        },
                    )
                }
            }
        }
    }

    /**
     * Task 2.5：从 EditMatch 回写后，把外部编辑过的结果列表合并回当前 UI 状态。
     *
     * 按 [FileMatch.status] 重新分流到 results（AUTO/CONFIRMED）或 pending（PENDING/NO_MATCH），
     * 保持与 [startMatch] 一致的分区规则。filePath 不变，便于预览页继续按路径查找。
     */
    fun applyEditedResults(files: List<FileMatch>) {
        _uiState.update { s ->
            val results = files.filter { it.status == MatchStatus.AUTO || it.status == MatchStatus.CONFIRMED }
            val pending = files.filter { it.status == MatchStatus.PENDING || it.status == MatchStatus.NO_MATCH }
            s.copy(results = results, pending = pending)
        }
    }

    /**
     * 拉详情：电影 → [TmdbCacheRepository.getMovie]；剧集 → [TmdbCacheRepository.getTv] + [TmdbCacheRepository.getSeason]
     * 填 [MediaMetadata.seasonNumber]/[MediaMetadata.episodeNumbers]/[MediaMetadata.episodeTitles]
     * /[MediaMetadata.episodeAirDates]（多集标题 `A & B` 合并，对齐 [TmdbMapper] 规则）。
     * Task 2.3.4：详情请求经 [TmdbCacheRepository] 自动走 Room 缓存（7 天 TTL）。
     *
     * 季号解析顺序：显式 parsed.season > 绝对集号按季累加定位 (season, episodeInSeason) > 回退 1。
     * 不再硬填 season=1：仅当无法从 [ParsedFilename.isAbsoluteEpisode] 定位时才回退。
     */
    private suspend fun fetchDetail(
        tmdbCache: TmdbCacheRepository,
        candidate: MatchCandidate,
        parsed: ParsedFilename,
        language: String,
    ): MediaMetadata {
        val id = candidate.tmdbId
        return if (candidate.mediaType == MediaType.EPISODE) {
            val tv = tmdbCache.getTv(id, language)
            // 季号解析：显式 > 绝对集号按季累加定位 > 回退 1
            var seasonNumber = parsed.season
            var episodes = parsed.episodes
            if (seasonNumber == null && parsed.isAbsoluteEpisode && episodes.isNotEmpty()) {
                resolveAbsoluteEpisode(tmdbCache, id, episodes.first(), tv.numberOfSeasons, language)
                    ?.let { (s, e) ->
                        seasonNumber = s
                        episodes = listOf(e)
                    }
            }
            // TODO: 后续可优先调用 tmdbCache.getEpisodeGroup 按 TMDB episode group 分组定位绝对集号
            //       （需 TmdbMapper 在 tv.info 暴露 episode group id 列表）；当前采用按季顺序累加集数的回退策略。
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

    /**
     * 绝对集号 → (season, episodeInSeason) 定位：按季顺序累加常规集数（跳过 Season 0 特典）。
     * 任一季请求失败则跳过该季继续；总集数不足以覆盖绝对集号时返回 null（调用方回退 season=1）。
     */
    private suspend fun resolveAbsoluteEpisode(
        tmdbCache: TmdbCacheRepository,
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
            // 仅统计常规集（episodeNumber > 0），跳过特典
            val count = season.episodes.count { (it.episodeNumber ?: 0) > 0 }
            if (count <= 0) continue
            if (remaining <= count) return s to remaining
            remaining -= count
        }
        return null
    }

    /** 搜索结果轻量 [MediaMetadata] → [MatchCandidate]（popularity 轻量映射不含，置 0.0）。 */
    private fun MediaMetadata.toMatchCandidate(): MatchCandidate = MatchCandidate(
        tmdbId = tmdbId ?: id ?: 0,
        name = name ?: "",
        originalName = originalName,
        aliases = aliases,
        year = year,
        popularity = info["popularity"]?.toDoubleOrNull() ?: 0.0,
        mediaType = type,
    )

    /** 把评分候选 + 搜索结果拼成 UI [Candidate]（海报/简介从搜索结果 info 取）。 */
    private fun toCandidate(scored: ScoredCandidate, searchResults: List<MediaMetadata>): Candidate {
        val meta = searchResults.firstOrNull {
            (it.tmdbId ?: it.id) == scored.candidate.tmdbId
        }
        val posterUrl = meta?.info?.get("posterPath")?.let { TmdbImages.poster(path = it) }
        val overview = meta?.info?.get("overview")
        return Candidate(scored.candidate, posterUrl, overview, scored.score)
    }
}
