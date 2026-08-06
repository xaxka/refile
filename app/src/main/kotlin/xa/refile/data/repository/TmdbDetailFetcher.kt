package xa.refile.data.repository

import xa.refile.core.matcher.MatchCandidate
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.ParsedFilename
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 详情拉取编排器。
 *
 * 消除 MatchViewModel 与 PreviewViewModel 中完全重复的 `fetchDetail()` +
 * `resolveAbsoluteEpisode()` 逻辑（~80 行重复代码）。
 *
 * 职责：
 * - 按候选与解析文件拉取完整 TMDB 元数据（电影/剧集）
 * - 剧集类型补全季号（显式 > 绝对集号定位 > 回退 1）
 * - 剧集类型补全集号标题与播出日期
 *
 * 纯调用 [TmdbDetailRepository] 与 [TmdbMapper]，无 UI 状态、无协程作用域。
 * `@Singleton` + `@Inject constructor` 供 Hilt 注入到 ViewModel。
 */
@Singleton
class TmdbDetailFetcher @Inject constructor(
    private val tmdbDetail: TmdbDetailRepository,
) {

    /**
     * 拉取完整 TMDB 详情：电影 → [TmdbDetailRepository.getMovie]；
     * 剧集 → [TmdbDetailRepository.getTv] + [TmdbDetailRepository.getSeason]。
     *
     * 季号解析顺序：显式 parsed.season > 绝对集号按季累加定位 > 回退 1。
     */
    suspend fun fetchDetail(
        candidate: MatchCandidate,
        parsed: ParsedFilename,
        language: String,
    ): MediaMetadata {
        val id = candidate.tmdbId
        return if (candidate.mediaType == MediaType.EPISODE) {
            val tv = tmdbDetail.getTv(id, language)
            var seasonNumber = parsed.season
            var episodes = parsed.episodes
            if (seasonNumber == null && parsed.isAbsoluteEpisode && episodes.isNotEmpty()) {
                resolveAbsoluteEpisode(id, episodes.first(), tv.numberOfSeasons, language)
                    ?.let { (s, e) ->
                        seasonNumber = s
                        episodes = listOf(e)
                    }
            }
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
                tv.copy(
                    seasonNumber = finalSeason,
                    episodeNumbers = episodes,
                    episodeTitles = if (titles.size > 1) listOf(titles.joinToString(" & ")) else titles,
                    episodeAirDates = airDates,
                    seasonName = season?.name,
                )
            }
        } else {
            tmdbDetail.getMovie(id, language)
        }
    }

    /**
     * 绝对集号 → (season, episodeInSeason) 定位：按季顺序累加常规集数（跳过 Season 0 特典）。
     * 任一季请求失败则跳过该季继续；总集数不足以覆盖绝对集号时返回 null。
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
            val count = season.episodes.count { (it.episodeNumber ?: 0) > 0 }
            if (count <= 0) continue
            if (remaining <= count) return s to remaining
            remaining -= count
        }
        return null
    }
}
