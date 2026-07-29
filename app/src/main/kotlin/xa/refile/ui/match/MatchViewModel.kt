package xa.refile.ui.match

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.matcher.ConfidenceScorer
import xa.refile.core.matcher.MatchCandidate
import xa.refile.core.matcher.MatchDecision
import xa.refile.core.matcher.MatchEngine
import xa.refile.core.matcher.ScoredCandidate
import xa.refile.core.matcher.SeriesNameMatcher
import xa.refile.core.matcher.VideoListResolver
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.FilenameParser
import xa.refile.core.parser.ParsedFilename
import xa.refile.core.tmdb.TmdbImages
import xa.refile.core.tmdb.TmdbMapper
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.data.repository.TmdbSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

/**
 * TMDB 匹配编排 ViewModel（计划 §M2 Task 2.4）。
 *
 * 流程：浏览器选中的视频路径 → [FilenameParser] 解析 → 判定类型（自动）
 * → [TmdbSearchRepository] 搜索 → [MatchEngine] 决策：
 * - [MatchDecision.Auto]：拉详情（剧集补 [MediaMetadata.seasonNumber]/[MediaMetadata.episodeTitles]）→ 自动✅
 * - [MatchDecision.NeedsConfirm]：待确认⚠️，保留候选供 UI 选择
 * - [MatchDecision.NoMatch]：无匹配❌，用户可手动搜索
 *
 * Task 2.3.4 / Task 20：TMDB 访问拆分为 [TmdbSearchRepository]（搜索类，会话级内存缓存）
 * 与 [TmdbDetailRepository]（详情类，Room 持久缓存）。API Key 仅从 [SettingsRepository]
 * 读取用于网络请求，绝不进入 UI 状态或日志。
 */
@HiltViewModel
class MatchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tmdbSearch: TmdbSearchRepository,
    private val tmdbDetail: TmdbDetailRepository,
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
    // Feature #23 / #24：批处理预取用，从一批文件名中找公共剧名 / 把多版本电影归组，
    // 让批量匹配只发一次搜索请求复用给全部文件，避免逐文件 searchTv 发 N 次网络请求。
    private val seriesMatcher = SeriesNameMatcher()
    private val videoListResolver = VideoListResolver()

    /** 接收浏览器选中的视频完整路径列表。 */
    fun setFiles(files: List<String>) {
        _uiState.update {
            if (it.selectedFiles == files) {
                it
            } else {
                // 文件列表变化时才清空会话搜索缓存：同一批文件重新匹配时保留缓存，
                // 搜索/详情命中内存缓存或 Room 持久缓存，避免重复联网（用户反馈"重复匹配仍联网"）。
                tmdbSearch.clearSessionCache()
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
     *
     * Feature #23 / #24 批处理预取：
     * - TV 文件：用 [SeriesNameMatcher] 从文件名中提取公共剧名，每个剧名只发 1 次 [TmdbSearchRepository.searchTv]，
     *   同剧多集文件共享候选列表（避免逐文件 `searchTv("Show")` 发 N 次请求；并抗单文件噪音）。
     * - 电影文件：用 [VideoListResolver] 把多版本归组（同标题同年份），每组发 1 次 [TmdbSearchRepository.searchMovie]，
     *   组内文件共享候选 + 在命名时统一加版本标记。
     * - 预取失败的文件回退到 [runMatchForFile] 的 per-file 搜索（[preFetchedCandidates] = null）。
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

            // Feature #23 / #24：批量预取候选
            // 1. 一次性解析所有文件 → parsedByIndex
            // 2. TV 文件用 SeriesNameMatcher 找公共剧名 → 每个剧名一次 searchTv
            // 3. 电影文件用 VideoListResolver 归组 → 每组一次 searchMovie
            // 4. 后续 per-file 走 runMatchForFile，命中预取的文件直接复用候选，
            //    未命中（预取失败 / 多剧混合中未归到任何剧）回退到原 per-file 搜索
            val parsedByIndex = files.map { parser.parse(it.substringAfterLast('/')) }
            val types = parsedByIndex.map { resolveType(forceType, it) }

            val tvCandidatesByFileIdx = preFetchTvCandidates(parsedByIndex, types, files, language)
            val movieCandidatesByFileIdx = preFetchMovieCandidates(parsedByIndex, types, language)

            val results = mutableListOf<FileMatch>()
            val pending = mutableListOf<FileMatch>()

            files.forEachIndexed { index, path ->
                val p = parsedByIndex[index]
                val type = types[index]
                val preFetched = when (type) {
                    MatchType.TV -> tvCandidatesByFileIdx[index]
                    MatchType.MOVIE -> movieCandidatesByFileIdx[index]
                    else -> null
                }
                val fm = try {
                    runMatchForFile(p, type, language, path, preFetched)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    FileMatch(path, p, MatchStatus.NO_MATCH, error = t.message ?: "未知错误")
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

    /**
     * Feature #23：批量预取 TV 候选。
     *
     * 对所有 TV 类型文件用 [SeriesNameMatcher] 找公共剧名 → 每个剧名 1 次 [TmdbSearchRepository.searchTv]。
     * 同剧多集文件（如 `Show.S01E01.mkv` ~ `Show.S01E03.mkv`）共享同一候选列表，
     * 避免逐文件发 searchTv("Show") 共 N 次请求；并抵消单文件名噪音（如某个文件多带了 `1080p.BluRay.x264-GROUP`
     * 让 [FilenameParser] 误解析为别的标题）。
     *
     * 返回：fileIndex → 共享的 [MediaMetadata] 列表（已 [TmdbSearchRepository] 自动走 sessionCache 去重）。
     * 未归到任何剧的 TV 文件不在返回 map 中（调用方走 per-file 搜索）。
     */
    private suspend fun preFetchTvCandidates(
        parsedByIndex: List<ParsedFilename>,
        types: List<MatchType>,
        files: List<String>,
        language: String,
    ): Map<Int, List<MediaMetadata>> {
        val tvFileIndices = parsedByIndex.indices.filter { types[it] == MatchType.TV }
        if (tvFileIndices.size < 2) return emptyMap()  // 单文件无"公共"可言，走 per-file 路径

        val tvFileNames = tvFileIndices.map { files[it].substringAfterLast('/') }
        val seriesResult = seriesMatcher.match(tvFileNames)
        if (seriesResult.seriesNames.isEmpty()) return emptyMap()

        // 每个剧名并发预取一次 searchTv（剧名间无依赖；sessionCache 也会去重相同 query）。
        // 用组内代表文件的原始标题（parsed.title + titleAliases）作为查询，而非 SeriesNameMatcher
        // 归一后的拼音 seriesName —— TMDB search 无法用拼音匹配 CJK 原名/英文名，会导致中文剧
        // （如「天命大神皇」→ 拼音 "tian ming da shen huang"）批量匹配搜不到。
        val seriesCandidates = coroutineScope {
            seriesResult.seriesNames.associateWith { sn ->
                async {
                    val repLocalIdx = seriesResult.fileIndices[sn]?.firstOrNull()
                        ?: return@async emptyList<MediaMetadata>()
                    if (repLocalIdx !in tvFileIndices.indices) return@async emptyList<MediaMetadata>()
                    val repParsed = parsedByIndex[tvFileIndices[repLocalIdx]]
                    val searchTitles = listOfNotNull(repParsed.title?.takeIf { it.isNotBlank() }) +
                        repParsed.titleAliases.filter { it.isNotBlank() }
                    if (searchTitles.isEmpty()) return@async emptyList<MediaMetadata>()
                    searchTitles.map { q ->
                        runCatching { tmdbSearch.searchTv(q, year = null, language = language) }
                            .getOrNull() ?: emptyList()
                    }.flatten().distinctBy { it.id }
                }
            }.mapValues { it.value.await() }
        }
        // 把剧名共享的候选分发给到该剧下的每个 TV 文件
        val out = mutableMapOf<Int, List<MediaMetadata>>()
        for ((sn, localIdxList) in seriesResult.fileIndices) {
            val cands = seriesCandidates[sn] ?: continue
            for (localIdx in localIdxList) {
                if (localIdx in tvFileIndices.indices) {
                    out[tvFileIndices[localIdx]] = cands
                }
            }
        }
        return out
    }

    /**
     * Feature #24：批量预取电影候选。
     *
     * 对所有 MOVIE 类型文件用 [VideoListResolver] 归组（按归一标题 + 年份）→ 每组 1 次
     * [TmdbSearchRepository.searchMovie]（query=primary.title, year=primary.year）。
     * 组内文件（不同分辨率 / 编码 / HDR）共享同一候选列表，避免逐文件搜索 + 重复打分；
     * primary 用组内最高画质版本（用于 TMDB 搜索 query 来源稳定）。
     *
     * 单文件组（无多版本）不预取（避免无收益的网络往返）→ 走 per-file 搜索。
     *
     * 返回：fileIndex → 共享的 [MediaMetadata] 列表。
     */
    private suspend fun preFetchMovieCandidates(
        parsedByIndex: List<ParsedFilename>,
        types: List<MatchType>,
        language: String,
    ): Map<Int, List<MediaMetadata>> {
        val movieFileIndices = parsedByIndex.indices.filter { types[it] == MatchType.MOVIE }
        if (movieFileIndices.size < 2) return emptyMap()

        val movieParsed = movieFileIndices.map { parsedByIndex[it] }
        val groups = videoListResolver.resolve(movieParsed)
        // 仅对多文件组预取（单文件组无收益）
        val multiFileGroups = groups.filter { it.files.size >= 2 }
        if (multiFileGroups.isEmpty()) return emptyMap()

        val groupCandidates = coroutineScope {
            multiFileGroups.map { g ->
                async {
                    val q = g.title.takeIf { it.isNotBlank() } ?: return@async g to emptyList()
                    val cands = runCatching { tmdbSearch.searchMovie(q, g.year, language) }
                        .getOrNull() ?: emptyList()
                    g to cands
                }
            }.map { it.await() }
        }
        val out = mutableMapOf<Int, List<MediaMetadata>>()
        for ((g, cands) in groupCandidates) {
            for (localIdx in g.fileIndices) {
                if (localIdx in movieFileIndices.indices) {
                    out[movieFileIndices[localIdx]] = cands
                }
            }
        }
        return out
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
     * P3.0：tmdbId 短路时 MediaType 必须确定（[TmdbClient.findByTmdbId] 参数非空）。
     * - MOVIE → [MediaType.MOVIE]
     * - TV → [MediaType.EPISODE]
     * - AUTO → 按 parsed.season/episodes 推断：有则 EPISODE，否则 MOVIE
     */
    private fun tmdbMediaType(parsed: ParsedFilename, type: MatchType): MediaType = when (type) {
        MatchType.MOVIE -> MediaType.MOVIE
        MatchType.TV -> MediaType.EPISODE
        MatchType.AUTO ->
            if (parsed.season != null || parsed.episodes.isNotEmpty()) MediaType.EPISODE else MediaType.MOVIE
    }

    /**
     * P3.0：tvdbId / imdbId 短路时 MediaType 可为 null（让 [TmdbClient] 自己分桶）。
     * - MOVIE → [MediaType.MOVIE]
     * - TV → [MediaType.EPISODE]
     * - AUTO → null
     */
    private fun nullableMediaType(type: MatchType): MediaType? = when (type) {
        MatchType.MOVIE -> MediaType.MOVIE
        MatchType.TV -> MediaType.EPISODE
        MatchType.AUTO -> null
    }

    /**
     * 单文件匹配：搜索 → 决策 → 拉详情。
     *
     * P3.0：Provider ID 短路 —— 若 [ParsedFilename] 携带 tmdbId/tvdbId/imdbId（优先级 tmdbId > tvdbId > imdbId），
     * 优先走 [TmdbDetailRepository.findByTmdbId] / [TmdbSearchRepository.findByTvdbId] /
     * [TmdbSearchRepository.findByImdbId] 精确查找，命中即视为权威候选直接 Auto（ID 比 ParsedFilename.title
     * 更可靠）。短路未命中（端点 404 / 网络失败 / parsed 无 ID）时回退到原 search + 相似度打分路径。
     *
     * P2.2：若首决策为 NeedsConfirm 且 parsed 携带 SxE，预拉 top 候选的季详情做 SxE 互校，
     * 命中则填充 candidate.season/episodes 重打分，可能升级为 Auto。
     *
     * Feature #23 / #24：[preFetchedCandidates] 非空时跳过 per-file 搜索（候选已由批量预取步骤提供，
     * 如 [SeriesNameMatcher] / [VideoListResolver] 派发的同剧 / 同片共享候选）；为 null 或空列表则回退到
     * 原 per-file 搜索（批量预取失败/无结果时不丢失匹配机会）。
     */
    private suspend fun runMatchForFile(
        parsed: ParsedFilename,
        type: MatchType,
        language: String,
        filePath: String,
        preFetchedCandidates: List<MediaMetadata>? = null,
    ): FileMatch {
        // P3.0：Provider ID 短路（tmdbId > tvdbId > imdbId）。
        // matchByIds 内部检查 parsed 是否携带任一 ID；未携带或 lookup 返回 null 时返回 null，调用方回退。
        var hitMeta: MediaMetadata? = null
        val idDecision = engine.matchByIds(parsed) { p ->
            // 局部 val 可被智能转换；跨模块 public 属性不可智能转换，故先取值到局部变量
            val tmdbId = p.tmdbId
            val tvdbId = p.tvdbId
            val imdbId = p.imdbId
            val hit: MediaMetadata? = when {
                // tmdbId 短路：MediaType 必须确定，AUTO 时按 parsed.season/episodes 推断
                tmdbId != null -> try {
                    tmdbDetail.findByTmdbId(tmdbId, tmdbMediaType(p, type), language)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                // tvdbId 短路：MediaType 可为 null（让 TmdbClient 自己分桶）
                tvdbId != null -> try {
                    tmdbSearch.findByTvdbId(tvdbId, nullableMediaType(type), language)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                // imdbId 短路：保留 P2.4 原行为，AUTO 时传 null
                !imdbId.isNullOrBlank() -> try {
                    tmdbSearch.findByImdbId(imdbId, nullableMediaType(type), language)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                else -> null
            }
            hitMeta = hit
            hit?.toMatchCandidate()
        }
        if (idDecision is MatchDecision.Auto) {
            // 短路命中：fetchDetail 拉完整详情（TV 类型补 seasonNumber/episodeTitles），避免 hitMeta 是轻量元数据
            val found = hitMeta!!
            val meta = fetchDetail(idDecision.best.candidate, parsed, language)
            return FileMatch(
                filePath = filePath,
                parsed = parsed,
                status = MatchStatus.AUTO,
                matched = meta,
                candidates = listOf(mediaMetadataToCandidate(found, 1.0)),
            )
            // 短路未命中（matchByIds 返回 null）→ 回退到 search + score 路径
        }

        // Feature #23 / #24：批量预取候选命中 → 跳过 per-file 搜索；否则回退到原 per-file 搜索路径。
        val searchResults: List<MediaMetadata>
        val candidates: List<MatchCandidate>
        if (!preFetchedCandidates.isNullOrEmpty()) {
            searchResults = preFetchedCandidates
            candidates = preFetchedCandidates.map { it.toMatchCandidate() }
        } else {
            val title = parsed.title?.takeIf { it.isNotBlank() } ?: ""
            // P2.6：主标题 + 别名分别搜 TMDB，合并去重候选（中英混合文件名 `寒战1994 Cold War` 场景）。
            // 多个关键词并发搜索（受共享限流器 + 请求合并约束），单文件匹配延迟从 Σ(latency) 降为 max(latency)。
            val searchTitles = listOf(title) + parsed.titleAliases.filter { it.isNotBlank() }
            searchResults = coroutineScope {
                searchTitles.map { q ->
                    async {
                        if (type == MatchType.TV) tmdbSearch.searchTv(q, parsed.year, language)
                        else tmdbSearch.searchMovie(q, parsed.year, language)
                    }
                }.awaitAll()
            }.flatMap { it }.distinctBy { it.id }
            candidates = searchResults.map { it.toMatchCandidate() }
        }
        val decision = engine.match(parsed, candidates)
        // P2.2：SxE 互校 — NeedsConfirm 时若 parsed 有 SxE，预拉季详情验证后重打分
        val finalDecision = if (decision is MatchDecision.NeedsConfirm) {
            enrichWithSxe(parsed, decision.candidates, language)?.let { enriched ->
                engine.match(parsed, enriched)
            } ?: decision
        } else {
            decision
        }
        return when (finalDecision) {
            is MatchDecision.Auto -> {
                val meta = fetchDetail(finalDecision.best.candidate, parsed, language)
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
        parsed: ParsedFilename,
        scored: List<ScoredCandidate>,
        language: String,
    ): List<MatchCandidate>? {
        val seasonNum = parsed.season ?: return null
        if (parsed.episodes.isEmpty()) return null
        val top = scored.firstOrNull()?.candidate ?: return null
        if (top.mediaType != MediaType.EPISODE) return null
        val season = try {
            tmdbDetail.getSeason(top.tmdbId, seasonNum, language)
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
                fetchDetail(candidate, fm.parsed, language)
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
                    tmdbSearch.searchTv(q, null, language)
                } else {
                    tmdbSearch.searchMovie(q, null, language)
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
     * 拉详情：电影 → [TmdbDetailRepository.getMovie]；剧集 → [TmdbDetailRepository.getTv] + [TmdbDetailRepository.getSeason]
     * 填 [MediaMetadata.seasonNumber]/[MediaMetadata.episodeNumbers]/[MediaMetadata.episodeTitles]
     * /[MediaMetadata.episodeAirDates]（多集标题 `A & B` 合并，对齐 [TmdbMapper] 规则）。
     * Task 2.3.4：详情请求经 [TmdbDetailRepository] 自动走 Room 缓存（7 天 TTL）。
     *
     * 季号解析顺序：显式 parsed.season > 绝对集号按季累加定位 (season, episodeInSeason) > 回退 1。
     * 不再硬填 season=1：仅当无法从 [ParsedFilename.isAbsoluteEpisode] 定位时才回退。
     */
    private suspend fun fetchDetail(
        candidate: MatchCandidate,
        parsed: ParsedFilename,
        language: String,
    ): MediaMetadata {
        val id = candidate.tmdbId
        return if (candidate.mediaType == MediaType.EPISODE) {
            val tv = tmdbDetail.getTv(id, language)
            // 季号解析：显式 > 绝对集号按季累加定位 > 回退 1
            var seasonNumber = parsed.season
            var episodes = parsed.episodes
            if (seasonNumber == null && parsed.isAbsoluteEpisode && episodes.isNotEmpty()) {
                resolveAbsoluteEpisode(id, episodes.first(), tv.numberOfSeasons, language)
                    ?.let { (s, e) ->
                        seasonNumber = s
                        episodes = listOf(e)
                    }
            }
            // TODO: 后续可优先调用 tmdbDetail.getEpisodeGroup 按 TMDB episode group 分组定位绝对集号
            //       （需 TmdbMapper 在 tv.info 暴露 episode group id 列表）；当前采用按季顺序累加集数的回退策略。
            val finalSeason = seasonNumber ?: 1
            if (episodes.isEmpty()) {
                tv.copy(seasonNumber = finalSeason)
            } else {
                val season = try {
                    tmdbDetail.getSeason(id, finalSeason, language)
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
                // DVD 顺序换算：当文件名给出绝对集号、且 TMDB 返回含 DVD 顺序 episode group 时，
                // 拉取 group detail 把"原集号→DVD 顺序集号"映射填入 media.order["dvd"]，
                // 模板可用 {order.dvd.e} 输出 DVD 顺序集号（增强项，失败静默跳过）。
                val dvdOrder = if (parsed.isAbsoluteEpisode) {
                    resolveDvdOrder(tv, season)
                } else null
                tv.copy(
                    seasonNumber = finalSeason,
                    episodeNumbers = episodes,
                    episodeTitles = if (titles.size > 1) listOf(titles.joinToString(" & ")) else titles,
                    episodeAirDates = airDates,
                    seasonName = season?.name,
                    order = dvdOrder?.let { tv.order + ("dvd" to it) } ?: tv.order,
                )
            }
        } else {
            tmdbDetail.getMovie(id, language)
        }
    }

    /**
     * 绝对集号 → (season, episodeInSeason) 定位：按季顺序累加常规集数（跳过 Season 0 特典）。
     * 任一季请求失败则跳过该季继续；总集数不足以覆盖绝对集号时返回 null（调用方回退 season=1）。
     */
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
                tmdbDetail.getSeason(tvId, s, language)
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

    /**
     * DVD 顺序换算：读取 [MediaMetadata.info] 中由 [TmdbMapper] 暴露的 `dvdEpisodeGroupId`，
     * 拉取 episode group 详情并把当前季 episodes 按 TMDB episode id 对齐到 DVD 顺序集号。
     *
     * 返回 `originalEpisodeNumber(String) → dvdEpisodeNumber(Int)` 映射；任一环节缺失或失败
     * （无 DVD group / group 详情拉取失败 / 无匹配集）返回 null，调用方保持原 order 不变。
     */
    private suspend fun resolveDvdOrder(
        tv: MediaMetadata,
        season: xa.refile.core.tmdb.SeasonDetail?,
    ): Map<String, Int>? {
        val dvdGroupId = tv.info["dvdEpisodeGroupId"]?.takeIf { it.isNotBlank() } ?: return null
        val seasonEpisodes = season?.episodes ?: return null
        return try {
            val group = tmdbDetail.getEpisodeGroup(dvdGroupId)
            TmdbMapper.dvdOrderMap(seasonEpisodes, group)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }?.takeIf { it.isNotEmpty() }
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
        voteAverage = rating ?: 0.0,
        voteCount = votes ?: 0,
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
