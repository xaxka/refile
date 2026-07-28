package xa.refile.core.matcher

import xa.refile.core.parser.FilenameParser

/**
 * 多文件公共剧集名检测（Feature #23）。
 *
 * 不依赖 TMDB 搜索，直接从一批文件名中找公共子串作为剧集名，使批量匹配时
 * 只发 1 次搜索请求复用给全部文件，避免逐文件 `searchTv("Show")` 发 N 次网络请求；
 * 且能避免"某个文件名多带了点噪音导致搜索偏离"的问题（用全集 LCS 抵消单文件噪音）。
 *
 * 参考 FileBot `SeriesNameMatcher`：
 * - 每个文件名先剥掉扩展名 + 季集尾段（`Show.S01E01.mkv` → 头段 `Show`），
 *   这一步借用 [FilenameParser] 把 `parsed.title` 提取出来（已剥离 tech/SxE/年份等）。
 * - 头段按空白切 token，再经罗马数字 / 数字归一化（`II` → `2`、`Part 1` → `part 1`）。
 * - 用"逐对最长公共连续 token 串"（参考 FileBot `CommonSequenceMatcher.firstCommonSequence`，
 *   带起始位置上限 [MAX_START_INDEX]）对所有头段求交集，得到跨所有文件的公共 token 串。
 * - 公共 token 数 ≥ [MIN_COMMON_TOKENS] 才视为有效剧名，避免单字"the"误判。
 *
 * 与 FileBot 的差异：
 * - 不按 parent folder 分桶（refile 选中的文件可能跨目录，按目录分桶反而打散同剧文件）；
 *   直接对全量文件求公共串。多剧混合时返回多个互不交集的剧名（每个由若干文件贡献）。
 * - 不使用 Collator（PRIMARY strength），改用 [TextNormalizer.normalize] 完成跨脚本归一，
 *   与 [ConfidenceScorer] / [MatchEngine] 的硬信号判定保持一致归一源。
 *
 * 纯 Kotlin 无 Android 依赖（[FilenameParser] / [TextNormalizer] 同样纯 JVM）。
 *
 * @param parser 注入 [FilenameParser] 以复用季集 / tech 剥离逻辑；测试可传 mock。
 */
class SeriesNameMatcher(
    private val parser: FilenameParser = FilenameParser(),
    /** 公共 token 串起始位置上限（FileBot 默认 3）：仅在每条头段前 N 个 token 内寻找公共串起点。 */
    private val maxStartIndex: Int = DEFAULT_MAX_START_INDEX,
    /** 公共 token 数下限：低于此数视为无公共剧名（避免单字"the"被误判为剧名）。 */
    private val minCommonTokens: Int = DEFAULT_MIN_COMMON_TOKENS,
) {

    /**
     * 匹配结果。
     *
     * @param seriesNames 去重后的剧名列表（按首次出现顺序）。
     * @param fileIndices 每个剧名（key）对应贡献该剧名的文件索引列表（输入 [fileNames] 的下标）。
     *                    索引按输入顺序保留，调用方可据此把同剧文件分到一组共享候选。
     */
    data class Result(
        val seriesNames: List<String>,
        val fileIndices: Map<String, List<Int>>,
    )

    /**
     * 从一批文件名中找公共剧集名。
     *
     * 算法：
     * 1. 逐文件 [FilenameParser.parse] → [xa.refile.core.parser.ParsedFilename.title]（已剥离 SxE/tech/年份）；
     *    若 parser 返回空标题则回退到去扩展名的原文件名（保证有内容可比）。
     * 2. 每个头段切 token + 罗马数字归一 + [TextNormalizer.normalize]。
     * 3. 全文件两两求最长公共连续 token 串，最后得到跨所有文件的交集 token 列表。
     * 4. 若公共 token 数 ≥ [minCommonTokens]，所有文件归到同一剧名（用公共 token join 还原）。
     *    否则逐对尝试找出可分组的子集：对每个文件与其它文件求最长公共串，
     *    公共串 ≥ [minCommonTokens] 的文件归一组，最后把未归组的文件标记为 unmatched（-1）。
     *
     * 输入空列表 / 单文件 → 返回空 result（单文件无"公共"可言，调用方走原 per-file 路径）。
     */
    fun match(fileNames: List<String>): Result {
        if (fileNames.size < 2) {
            return Result(emptyList(), emptyMap())
        }
        val heads = fileNames.mapIndexed { i, name -> headTokens(name) to i }

        // 1. 先尝试"全文件公共串"（最常见：同剧多集）
        val allCommon = longestCommonRun(heads.map { it.first })
        if (allCommon.size >= minCommonTokens) {
            val seriesName = joinName(allCommon)
            return Result(
                seriesNames = listOf(seriesName),
                fileIndices = mapOf(seriesName to fileNames.indices.toList()),
            )
        }

        // 2. 全文件无公共串 → 多剧混合。按"两两公共串 ≥ minCommonTokens"聚类。
        //    用 Union-Find 把可连通的文件合并；每个连通分量取所有文件头的两两公共串作为剧名。
        val uf = UnionFind(fileNames.size)
        for (i in heads.indices) {
            for (j in (i + 1) until heads.size) {
                val pair = longestCommonRun(listOf(heads[i].first, heads[j].first))
                if (pair.size >= minCommonTokens) uf.union(i, j)
            }
        }
        // 每个连通分量求内部最长公共串作为剧名；单元素分量（无伴）跳过。
        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in fileNames.indices) {
            groups.getOrPut(uf.find(i)) { mutableListOf() }.add(i)
        }
        val seriesNames = mutableListOf<String>()
        val fileIndices = mutableMapOf<String, List<Int>>()
        for ((_, idxList) in groups) {
            if (idxList.size < 2) continue  // 单文件不构成"公共剧名"
            val tokens = idxList.map { heads[it].first }
            val run = longestCommonRun(tokens)
            if (run.size < minCommonTokens) continue
            val name = joinName(run)
            seriesNames.add(name)
            fileIndices[name] = idxList.toList()
        }
        return Result(seriesNames = seriesNames, fileIndices = fileIndices)
    }

    /**
     * 提取文件名的"头段 token"：
     * - [FilenameParser.parse] 取 [xa.refile.core.parser.ParsedFilename.title]（已剥离扩展名/SxE/tech/年份/版本）。
     * - 标题为空 → 回退去扩展名的原文件名（兜底，保证有 token 可比）。
     * - 罗马数字 → 阿拉伯数字（`II` → `2`），数字归一便于 `Star Wars II` 与 `Star Wars 2` 视为同串。
     * - [TextNormalizer.normalize] 跨脚本归一后按空白切 token。
     */
    private fun headTokens(fileName: String): List<String> {
        val parsed = parser.parse(fileName)
        val rawTitle = parsed.title?.takeIf { it.isNotBlank() }
            ?: parser.splitExtension(fileName).first
        val normalized = TextNormalizer.normalize(rawTitle)
        return normalized.split(' ').filter { it.isNotBlank() }.map { normalizeRoman(it) }
    }

    /** 罗马数字 → 阿拉伯数字（仅匹配 I/II/III/IV/V/VI...XX，且不与字母串冲突）。 */
    private fun normalizeRoman(token: String): String {
        val value = ROMAN[token.uppercase()] ?: return token
        return value.toString()
    }

    /**
     * 求多条 token 序列的"最长公共连续 token 串"。
     *
     * 参考 FileBot `CommonSequenceMatcher.firstCommonSequence`：
     * - 折叠式：从第一条开始，与下一条求两两最长公共连续子串，逐条累加。
     * - 任一对返回空 → 整体返回空。
     * - 起始位置上限 [maxStartIndex]：每条 token 序列仅在 `[0, maxStartIndex)` 范围内寻找公共串起点，
     *   避免从序列深处取到无关公共片段。
     */
    private fun longestCommonRun(sequences: List<List<String>>): List<String> {
        if (sequences.isEmpty()) return emptyList()
        var acc = sequences.first()
        for (i in 1 until sequences.size) {
            acc = firstCommonSequence(acc, sequences[i])
            if (acc.isEmpty()) return emptyList()
        }
        return acc
    }

    /**
     * 两两最长公共连续 token 子串（FileBot 算法）：
     * 嵌套循环 `i ∈ [0, min(len1, maxStartIndex)]`、`j ∈ [0, min(len2, maxStartIndex)]`，
     * 从每个 `(i, j)` 起扩展 `len` 直到 `seq1[i+len] != seq2[j+len]`，
     * 追踪最长公共串。`maxStartIndex` 默认 3，只在每条序列前部找起点。
     */
    private fun firstCommonSequence(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val iMax = minOf(a.size, maxStartIndex + 1)
        val jMax = minOf(b.size, maxStartIndex + 1)
        var bestLen = 0
        var bestI = 0
        for (i in 0 until iMax) {
            for (j in 0 until jMax) {
                var len = 0
                while (i + len < a.size && j + len < b.size && a[i + len] == b[j + len]) len++
                if (len > bestLen) {
                    bestLen = len
                    bestI = i
                }
            }
        }
        if (bestLen == 0) return emptyList()
        return a.subList(bestI, bestI + bestLen)
    }

    /** 用单空格把公共 token 串还原为剧名（已归一，不再二次 transform）。 */
    private fun joinName(tokens: List<String>): String = tokens.joinToString(" ")

    /** 简易 Union-Find：聚类用。 */
    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }

    companion object {
        private const val DEFAULT_MAX_START_INDEX = 3
        private const val DEFAULT_MIN_COMMON_TOKENS = 1

        /**
         * 罗马数字 → 阿拉伯数字映射（覆盖常见集号 / 剧集序号：I-XX）。
         * 仅对完全匹配的 token 替换，避免误伤 `Indiana`、`Victor` 等含罗马字符的正常词。
         */
        private val ROMAN = mapOf(
            "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
            "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
            "XI" to 11, "XII" to 12, "XIII" to 13, "XIV" to 14, "XV" to 15,
            "XVI" to 16, "XVII" to 17, "XVIII" to 18, "XIX" to 19, "XX" to 20,
        )
    }
}
