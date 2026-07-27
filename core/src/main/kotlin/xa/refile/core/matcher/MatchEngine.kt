package xa.refile.core.matcher

import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename
import java.text.Normalizer
import kotlin.math.abs

/**
 * TMDB 搜索候选（计划 §5.4）。
 *
 * P2.2：新增 [season]/[episodes] 字段，用于 SxE 互校加分。
 */
data class MatchCandidate(
    val tmdbId: Int,
    val name: String,
    val originalName: String? = null,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val popularity: Double = 0.0,
    val mediaType: MediaType = MediaType.MOVIE,
    val season: Int? = null,
    val episodes: List<Int> = emptyList(),
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
 * 置信度评分器（计划 §5.4-3，P0.5-P0.8 + P2.1-P2.3 重构）：
 * `score = titleScore * TITLE_WEIGHT - yearPenalty + popBonus + sxeBonus`
 *
 * - P0.5：年份惩罚（线性，替代二值 YEAR_BONUS）。diff=0 仅 0.01 常量惩罚；diff=30 → 0.04；diff>100 → 0.11 封顶。
 * - P0.6：标题相似度取 max(Jaccard, Levenshtein, Dice bigram, Jaro-Winkler, yearStripped, alias)。
 * - P0.7：归一化阶段 NFD 分解 + 去变音符号（Amélie → Amelie）。
 * - P0.8：TITLE_WEIGHT=0.85, autoThreshold=0.82, margin=0.08。
 * - P2.1：可选分数量化 `quantize(score) = floor(score * 4) / 4`，构造参数 [quantize] 控制。
 * - P2.2：SxE 互校 — parsed 与 candidate 的 season/episodes 命中时加 [SXE_BONUS]。
 * - P2.3：归一化去除前导冠词 the/a/an，让 `The Matrix` 与 `Matrix, The` 高分。
 *
 * 纯 Kotlin 无 Android 依赖（`java.text.Normalizer` 在 JDK 与 Android API 1+ 均可用）。
 */
class ConfidenceScorer(
    /** P2.1：是否对最终得分做四档量化（0/0.25/0.5/0.75/1.0）。默认关闭。 */
    private val quantize: Boolean = false,
) {

    fun score(parsed: ParsedFilename, candidate: MatchCandidate): Double {
        val title = parsed.title ?: return 0.0
        // P2.6：中英混合标题拆分后，用主标题与所有别名分别打分取 max ——
        // `寒战1994` 与 TMDB 候选 `寒战1994` 命中，`Cold War` 与候选 `Cold War` 命中。
        val titleScore = maxOf(
            computeTitleScore(title, candidate),
            *parsed.titleAliases.map { computeTitleScore(it, candidate) }.toDoubleArray(),
        )
        val yPenalty = yearPenalty(parsed.year, candidate.year)
        val popBonus = candidate.popularity.coerceAtMost(MAX_POP).let { it / MAX_POP * POP_WEIGHT }
        val sxeBonus = sxeBonus(parsed, candidate) // P2.2
        val raw = titleScore * TITLE_WEIGHT - yPenalty + popBonus + sxeBonus
        val clamped = raw.coerceIn(0.0, 1.0)
        return if (quantize) quantize(clamped) else clamped
    }

    /** P2.1：分数量化到四档（0/0.25/0.5/0.75/1.0）。 */
    private fun quantize(score: Double): Double = kotlin.math.floor(score * 4.0) / 4.0

    /** P0.6：标题相似度 = max(Jaccard, Levenshtein, Dice, Jaro-Winkler, yearStripped, alias)。 */
    private fun computeTitleScore(title: String, candidate: MatchCandidate): Double {
        val jaccard = tokenOverlap(title, candidate.name)
        val leven = editDistanceRatio(title, candidate.name)
        val dice = diceBigram(title, candidate.name)
        val jw = jaroWinkler(title, candidate.name)
        val yearStripped = yearStrippedSim(title, candidate.name)
        val aliasSim = candidate.aliases.maxOfOrNull {
            maxOf(
                tokenOverlap(title, it),
                editDistanceRatio(title, it),
                diceBigram(title, it),
                jaroWinkler(title, it),
            )
        } ?: 0.0
        return maxOf(jaccard, leven, dice, jw, yearStripped, aliasSim)
    }

    /**
     * P0.5：年份惩罚（参考 TMM `calculateYearPenalty`）。
     * - parsed.year 为 null → 0（无年份不惩罚）
     * - candidate.year 为 null → 0.11（候选缺年份，较大惩罚）
     * - |diff| > 100 → 0.11（封顶）
     * - 否则 → 0.01 + diff/1000（线性，diff=0 时 0.01，diff=100 时 0.11）
     */
    private fun yearPenalty(sy: Int?, cy: Int?): Double {
        if (sy == null) return 0.0
        if (cy == null) return MAX_YEAR_PENALTY
        val diff = abs(sy - cy)
        if (diff > 100) return MAX_YEAR_PENALTY
        return 0.01 + diff / 1000.0
    }

    /**
     * P2.2：SxE 互校加分。
     * - 候选无 season/episodes 信息 → 0（搜索结果轻量元数据缺 SxE，不惩罚）
     * - 双方有 season 且不一致 → 0（命中失败不加）
     * - 双方有 episodes 且有交集 → +SXE_BONUS
     * - season 一致但 episodes 无交集 → 0（不扣分，避免误伤单集元数据缺失场景）
     */
    private fun sxeBonus(parsed: ParsedFilename, candidate: MatchCandidate): Double {
        if (candidate.season == null && candidate.episodes.isEmpty()) return 0.0
        val seasonMatch = parsed.season != null && candidate.season != null && parsed.season == candidate.season
        val epIntersect = parsed.episodes.isNotEmpty() && candidate.episodes.isNotEmpty() &&
            parsed.episodes.toSet().intersect(candidate.episodes.toSet()).isNotEmpty()
        return when {
            seasonMatch && epIntersect -> SXE_BONUS
            seasonMatch && parsed.episodes.isEmpty() -> SXE_BONUS * 0.5 // 仅季命中
            else -> 0.0
        }
    }

    /**
     * P0.7 / P2.3：归一化。
     * 流程：lowercase → 重排尾随冠词（`Matrix, The` → `The Matrix`）→ NFD 分解去变音符号（é→e）
     *      → 去非字母数字 → 折叠空格 → 去前导冠词。
     * 注：完整 Any-Latin 音译（如 中文→拼音）需 ICU4J，此处仅做 Latin-ASCII 级别归一。
     *
     * P2.3 冠词归一化：去除前导 `the/a/an` 后再比对，让 `The Matrix` 与 `Matrix, The` 高分。
     * 仅去除一次前导冠词（不去中段 The Wonderful Wizard of Oz 这类）。
     */
    private fun normalize(s: String): String {
        val reordered = reorderTrailingArticle(s.lowercase())
        val nfd = Normalizer.normalize(reordered, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripLeadingArticle(nfd)
    }

    /** P2.3：将 `Matrix, The` 重排为 `The Matrix`，再走常规归一（避免尾随冠词被当成普通 token）。 */
    private fun reorderTrailingArticle(lowerCased: String): String {
        val m = TRAILING_ARTICLE.find(lowerCased) ?: return lowerCased
        val name = m.groupValues[1].trim()
        val article = m.groupValues[2]
        return "$article $name"
    }

    /** P2.3：去除前导冠词 the/a/an（仅一次，避免循环影响 `The The` 这种乐队名）。 */
    private fun stripLeadingArticle(s: String): String {
        val m = LEADING_ARTICLE.find(s) ?: return s
        return s.substring(m.range.last + 1).trimStart()
    }

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

    /**
     * P0.6：Strike-A-Match — 相邻字母对 Dice 系数。
     * 对换序/局部匹配敏感（如 `Lord of the Rings` vs `Rings Lord`）。
     */
    private fun diceBigram(a: String, b: String): Double {
        val na = normalize(a); val nb = normalize(b)
        if (na.length < 2 || nb.length < 2) return 0.0
        val ba = bigrams(na); val bb = bigrams(nb)
        if (ba.isEmpty() || bb.isEmpty()) return 0.0
        val inter = ba.intersect(bb).size
        return 2.0 * inter / (ba.size + bb.size)
    }

    private fun bigrams(s: String): Set<String> =
        (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()

    /**
     * P0.6：Jaro-Winkler 相似度（scalingFactor=0.1，maxPrefix=4）。
     * 对公共前缀加权，适合拼写近似/简写场景（如 `Spider Man` vs `Spider-Man`）。
     */
    private fun jaroWinkler(a: String, b: String): Double {
        val na = normalize(a); val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val j = jaro(na, nb)
        if (j == 0.0) return 0.0
        val prefix = commonPrefixLength(na, nb).coerceAtMost(MAX_JW_PREFIX)
        return j + prefix * JW_SCALING * (1 - j)
    }

    private fun jaro(a: String, b: String): Double {
        val la = a.length; val lb = b.length
        if (la == 0 && lb == 0) return 1.0
        if (la == 0 || lb == 0) return 0.0
        val matchDist = maxOf(la, lb) / 2 - 1
        val aMatches = BooleanArray(la)
        val bMatches = BooleanArray(lb)
        var matches = 0
        for (i in 0 until la) {
            val start = maxOf(0, i - matchDist)
            val end = minOf(i + matchDist + 1, lb)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in 0 until la) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val t = transpositions / 2.0
        return (matches / la.toDouble() + matches / lb.toDouble() + (matches - t) / matches) / 3.0
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        var i = 0
        while (i < minLen && a[i] == b[i]) i++
        return i
    }

    /**
     * P0.6：搜索串去年份再比一次（取 Jaccard 与 Dice 的 max）。
     * 场景：解析标题残留年份 token（如 `The Matrix 1999`）时，剥离后与候选 `The Matrix` 更接近。
     */
    private fun yearStrippedSim(title: String, candidateName: String): Double {
        val na = normalize(title)
        val stripped = removeYearTokens(na)
        if (stripped == na || stripped.isBlank()) return 0.0
        val jaccard = tokenOverlap(stripped, candidateName)
        val dice = diceBigram(stripped, candidateName)
        return maxOf(jaccard, dice)
    }

    private fun removeYearTokens(normalized: String): String =
        normalized.split(' ').filter { it.length != 4 || it.toIntOrNull() !in 1900..2099 }.joinToString(" ")

    companion object {
        private const val TITLE_WEIGHT = 0.85      // P0.8
        private const val POP_WEIGHT = 0.04
        private const val MAX_POP = 100.0
        private const val MAX_YEAR_PENALTY = 0.11   // P0.5
        private const val JW_SCALING = 0.1          // P0.6: Jaro-Winkler scaling factor
        private const val MAX_JW_PREFIX = 4         // P0.6: Jaro-Winkler max common prefix
        private const val SXE_BONUS = 0.10          // P2.2: SxE 完整命中加分
        // P2.3：前导冠词（仅去除一次，避免循环影响 `The The` 这种乐队名）
        private val LEADING_ARTICLE = Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE)
        // P2.3：尾随冠词排序式命名 `Matrix, The` / `Lord of the Rings, The`（输入已 lowercase）
        private val TRAILING_ARTICLE = Regex("^(.+?),\\s*(the|a|an)\\s*\$")
    }
}

/**
 * 匹配引擎（计划 §5.4）。
 * 自动匹配：得分 ≥ [autoThreshold] 且与次名分差 ≥ [margin] → 直接采用；
 * 否则进入待确认。
 *
 * P0.8：autoThreshold=0.82, margin=0.08。
 * P2.1：[quantize] 开关透传给 [ConfidenceScorer]；启用时建议同步调小 [margin]。
 */
class MatchEngine(
    private val scorer: ConfidenceScorer = ConfidenceScorer(),
    private val autoThreshold: Double = 0.82,   // P0.8
    private val margin: Double = 0.08,          // P0.8
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
