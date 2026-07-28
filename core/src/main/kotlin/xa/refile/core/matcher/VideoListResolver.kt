package xa.refile.core.matcher

import xa.refile.core.parser.ParsedFilename

/**
 * 多版本归组（Feature #24）。
 *
 * 把同一部影片的多个版本（不同分辨率 / 编码 / HDR / 来源）归为一组，只匹配一次 TMDB，
 * 并在组内按画质选出"主版本"（primary）作为 TMDB 搜索关键词来源。
 *
 * 能解决的问题：用户库里常有
 * ```
 * Inception.2010.1080p.BluRay.mkv
 * Inception.2010.2160p.UHD.HDR.mkv
 * Inception.2010.1080p.WEB-DL.mkv
 * ```
 * 当前 refile 会逐个搜索匹配 3 次（虽然 sessionCache 能命中后两次，但仍做了 3 次解析 + 3 次打分）。
 * 归组后只匹配一次，3 个文件共享同一候选列表，且能在命名时统一加版本标记。
 *
 * 参考 FileBot `VideoListResolver`：用标题 + 年份归组，组内按分辨率 / 编码排序选主版本。
 * 此处实现差异：
 * - 不依赖 FileBot 的 `MediaDetection`；直接用 [ParsedFilename] 的 `title` / `year` /
 *   `resolution` / `source` / `videoCodec` / `hdr` / `edition` 字段。
 * - 归一用 [TextNormalizer]（跨脚本音译），与 [MatchEngine] 硬信号一致。
 *
 * 纯 Kotlin 无 Android 依赖。
 */
class VideoListResolver(
    /** 是否要求年份一致才归一组（true：严格；false：缺年份时仅按标题归组）。 */
    private val requireYear: Boolean = false,
) {

    /**
     * 一个归组：含主版本与全部版本。
     *
     * @param title 主标题（取自 primary 解析结果，未归一；用于显示与 TMDB 搜索 query）。
     * @param year 归一组的主年份（取自 primary；可能为 null）。
     * @param primary 主版本（画质最高的代表）。
     * @param primaryIndex 主版本在输入列表中的下标（[resolve] 入参的下标，便于调用方映射回原文件）。
     * @param files 同组全部文件（含 primary，保留输入顺序）。
     * @param fileIndices 同组全部文件在输入列表中的下标（与 [files] 平行；调用方据此把同组文件
     *                    映射回原文件路径，避免依赖 [ParsedFilename] 相等判断受重复文件影响）。
     */
    data class MovieGroup(
        val title: String,
        val year: Int?,
        val primary: ParsedFilename,
        val primaryIndex: Int,
        val files: List<ParsedFilename>,
        val fileIndices: List<Int>,
    )

    /**
     * 把电影文件按 (归一标题, 年份) 归组，组内按画质选 primary。
     *
     * 归组规则：
     * - 标题为空（解析失败）→ 该文件独立成组（避免污染其它文件的归组）。
     * - 标题非空 + 有年份 → key = (normalize(title), year)；同 key 同组。
     * - 标题非空 + 无年份 → key = (normalize(title), null)；
     *   - [requireYear] = true → 该文件不与任何其它文件归组（独立成组）。
     *   - [requireYear] = false → 同标题无年份文件互相归组；不与有年份文件归组（年份缺失不可判定）。
     *
     * 组内 [pickPrimary] 选主版本；MovieGroup.title/year 取自 primary（保留解析原值，用于 TMDB 搜索 query）。
     */
    fun resolve(files: List<ParsedFilename>): List<MovieGroup> {
        if (files.isEmpty()) return emptyList()

        // 第一遍：把每个文件分到 (归一标题, 年份) key，标题缺失的文件单独标记。
        val groups = LinkedHashMap<GroupKey, MutableList<Int>>()
        val singletons = mutableListOf<Int>()
        for ((i, f) in files.withIndex()) {
            val rawTitle = f.title?.takeIf { it.isNotBlank() }
            if (rawTitle == null) {
                singletons.add(i); continue
            }
            val norm = TextNormalizer.normalize(rawTitle)
            // 严格模式 + 年份缺失 → 视为单文件组（不与任何人合并）
            if (requireYear && f.year == null) {
                singletons.add(i); continue
            }
            val key = GroupKey(norm, f.year)
            groups.getOrPut(key) { mutableListOf() }.add(i)
        }

        val result = mutableListOf<MovieGroup>()
        // 多文件组：选 primary
        for (idxList in groups.values) {
            val groupFiles = idxList.map { files[it] }
            val primaryLocalIdx = pickPrimaryIndex(groupFiles)
            val primary = groupFiles[primaryLocalIdx]
            result += MovieGroup(
                title = primary.title.orEmpty(),
                year = primary.year,
                primary = primary,
                primaryIndex = idxList[primaryLocalIdx],
                files = groupFiles,
                fileIndices = idxList.toList(),
            )
        }
        // 单文件组：直接成为 own group（与原 per-file 路径等价）
        for (i in singletons) {
            val f = files[i]
            result += MovieGroup(
                title = f.title.orEmpty(),
                year = f.year,
                primary = f,
                primaryIndex = i,
                files = listOf(f),
                fileIndices = listOf(i),
            )
        }
        return result
    }

    /**
     * 组内画质主版本选择 comparator（降序排，取第一）：
     * 1. 分辨率高度（2160 > 1080 > 720 > ...）
     * 2. HDR 标记（Dolby Vision > HDR10+ > HDR10 > 无）
     * 3. 来源（UHD Blu-ray > Blu-ray > WEB-DL > ...）
     * 4. 视频编码（HEVC > AV1 > H.264 > ...）
     * 5. 版本标记（Remux > 编码版 > Cam）
     * 6. 输入顺序（保持稳定）
     *
     * 返回主版本在组内的下标（调用方据此映射回原输入列表下标）。
     *
     * 实现：用 [sortedWith] + [first] 替代 [maxWithOrNull]。
     * [sortedWith] 升序排序，`compareByDescending` 使高画质排前；`thenBy { it }` 使低索引排前（稳定）。
     * [maxWithOrNull] + `thenBy { it }` 会在等分时取最高索引（反稳定），故不用。
     */
    private fun pickPrimaryIndex(group: List<ParsedFilename>): Int {
        if (group.size == 1) return 0
        return group.indices.sortedWith(
            compareByDescending<Int> { resolutionRank(group[it].resolution) }
                .thenByDescending { hdrRank(group[it].hdr) }
                .thenByDescending { sourceRank(group[it].source) }
                .thenByDescending { videoCodecRank(group[it].videoCodec) }
                .thenByDescending { editionRank(group[it].edition) }
                .thenBy { it }  // 稳定：相同画质时取最早出现的（低索引排前）
        ).first()
    }

    /** 分辨率高度评分：4320/2160/1080/720/480/360 等。未知 → 0。 */
    private fun resolutionRank(res: String?): Int =
        res?.let { RESOLUTION_RANK[it.lowercase()] } ?: 0

    /** HDR 评分：Dolby Vision > HDR10+ > HDR10 > DV + HDR10（双标）等。 */
    private fun hdrRank(hdr: String?): Int = hdr?.let { HDR_RANK[it.lowercase()] } ?: 0

    /** 来源评分：UHD Blu-ray > Blu-ray > BluRay Remux > WEB-DL > HDTV > ... */
    private fun sourceRank(src: String?): Int = src?.let { SOURCE_RANK[it.lowercase()] } ?: 0

    /** 视频编码评分：AV1 > HEVC > H.264 > VC-1 > MPEG-2 > ... */
    private fun videoCodecRank(codec: String?): Int =
        codec?.let { CODEC_RANK[it.lowercase()] } ?: 0

    /** Edition 评分：IMAX > Extended > Director's Cut > Theatrical > ... */
    private fun editionRank(ed: String?): Int = ed?.let { EDITION_RANK[it.lowercase()] } ?: 0

    private data class GroupKey(val normalizedTitle: String, val year: Int?)

    companion object {
        private val RESOLUTION_RANK = mapOf(
            "4320p" to 7, "2160p" to 6, "1440p" to 5, "1080p" to 4,
            "720p" to 3, "576p" to 2, "480p" to 1,
        )
        private val HDR_RANK = mapOf(
            "dolby vision" to 6, "dolbyvision" to 6, "dv" to 6,
            "hdr10+" to 5, "hdr10plus" to 5,
            "hdr10" to 4, "hdr" to 3, "hdr+" to 5, "hlg" to 2,
        )
        private val SOURCE_RANK = mapOf(
            "uhd blu-ray" to 9, "uhd bluray" to 9, "uhdbluray" to 9,
            "blu-ray" to 8, "bluray" to 8, "blu-ray remux" to 8,
            "remux" to 7,
            "web-dl" to 6, "webdl" to 6, "web" to 5,
            "webrip" to 5, "hdtv" to 4, "dvd" to 3, "dvdrip" to 3,
            "hdcam" to 1, "cam" to 1, "ts" to 2, "telesync" to 2,
        )
        private val CODEC_RANK = mapOf(
            "av1" to 7, "hevc" to 6, "h265" to 6, "x265" to 6,
            "avc" to 5, "h264" to 5, "x264" to 5,
            "vp9" to 4, "vp8" to 3, "vc-1" to 3, "wmv" to 2, "mpeg-2" to 1, "mpeg2" to 1,
        )
        private val EDITION_RANK = mapOf(
            "imax" to 8, "extended" to 7, "ultimate" to 7, "extended cut" to 7,
            "director's cut" to 6, "directors cut" to 6, "director" to 6,
            "uncut" to 5, "unrated" to 4, "remastered" to 5,
            "theatrical" to 3, "special edition" to 4, "criterion" to 5,
        )
    }
}
