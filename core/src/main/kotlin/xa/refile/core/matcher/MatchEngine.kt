package xa.refile.core.matcher

import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename

/**
 * TMDB 搜索候选（计划 §5.4）。
 */
data class MatchCandidate(
    val tmdbId: Int,
    val name: String,
    val originalName: String? = null,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val popularity: Double = 0.0,
    val mediaType: MediaType = MediaType.MOVIE,
)

/**
 * 单个候选的评分结果。
 */
data class ScoredCandidate(
    val candidate: MatchCandidate,
    val score: Double,
)

/**
 * 匹配决策。
 */
sealed class MatchDecision {
    /** 自动匹配（高置信度）。 */
    data class Auto(val best: ScoredCandidate) : MatchDecision()
    /** 需手动确认（低置信度），附带排序候选。 */
    data class NeedsConfirm(val candidates: List<ScoredCandidate>) : MatchDecision()
    /** 无候选。 */
    data object NoMatch : MatchDecision()
}

/**
 * 置信度评分器（计划 §5.4-3）：
 * 标题相似度（token 重合度 + 编辑距离）× 权重 + 年份一致加成 + 流行度微调。
 * 纯 Kotlin 无 Android 依赖。
 */
class ConfidenceScorer {

    fun score(parsed: ParsedFilename, candidate: MatchCandidate): Double {
        val title = parsed.title ?: return 0.0
        val tokenSim = tokenOverlap(title, candidate.name)
        val editSim = editDistanceRatio(title, candidate.name)
        // 取别名最大相似度兜底
        val aliasSim = candidate.aliases.maxOfOrNull { maxOf(tokenOverlap(title, it), editDistanceRatio(title, it)) } ?: 0.0
        val titleScore = maxOf(tokenSim, editSim, aliasSim)

        var score = titleScore * TITLE_WEIGHT
        // 年份一致加成
        if (parsed.year != null && candidate.year != null && parsed.year == candidate.year) {
            score += YEAR_BONUS
        }
        // 流行度微调（封顶）
        score += candidate.popularity.coerceAtMost(MAX_POP).let { it / MAX_POP * POP_WEIGHT }
        return score.coerceIn(0.0, 1.0)
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotEmpty() }.toSet()

    /** Jaccard token 重合度。 */
    private fun tokenOverlap(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size
        val union = ta.union(tb).size
        return inter.toDouble() / union.toDouble()
    }

    /** 归一化编辑距离相似度（1 - dist/maxLen）。 */
    private fun editDistanceRatio(a: String, b: String): Double {
        val na = normalize(a); val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        val d = levenshtein(na, nb)
        val maxLen = maxOf(na.length, nb.length)
        return 1.0 - d.toDouble() / maxLen.toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }

    companion object {
        private const val TITLE_WEIGHT = 0.9
        private const val YEAR_BONUS = 0.06
        private const val POP_WEIGHT = 0.04
        private const val MAX_POP = 100.0
    }
}

/**
 * 匹配引擎（计划 §5.4）。
 * 自动匹配：得分 ≥ [autoThreshold] 且与次名分差 ≥ [margin] → 直接采用；
 * 否则进入待确认。
 */
class MatchEngine(
    private val scorer: ConfidenceScorer = ConfidenceScorer(),
    private val autoThreshold: Double = 0.85,
    private val margin: Double = 0.1,
) {
    fun match(parsed: ParsedFilename, candidates: List<MatchCandidate>): MatchDecision {
        if (candidates.isEmpty()) return MatchDecision.NoMatch
        val scored = candidates.map { ScoredCandidate(it, scorer.score(parsed, it)) }
            .sortedByDescending { it.score }
        val best = scored.first()
        val second = scored.getOrNull(1)
        val secondGap = best.score - (second?.score ?: 0.0)
        return if (best.score >= autoThreshold && secondGap >= margin) {
            MatchDecision.Auto(best)
        } else {
            MatchDecision.NeedsConfirm(scored)
        }
    }
}
