package xa.refile.core.parser

import xa.refile.core.model.MediaType

/**
 * 文件名解析引擎（计划 §5.3）。
 *
 * 纯 Kotlin 无 Android 依赖，便于 JVM 单元测试。
 * 规则表驱动：季集模式 → 技术标签 → 清洗。
 *
 * 实现要点：
 * 1. 去扩展名；将 `.`、`_` 替换为空格（保留年份/小数判断）。
 * 2. 剔除方括号/圆括号内发布信息（分辨率/编码/组名/站点名），保留含年份圆括号。
 * 3. 剔除连续技术标签串尾巴（`720p BluRay x264 AAC-Group` 这种）。
 * 4. 标题首尾去空格、合并连续空格。
 */
class FilenameParser {

    /** 视频扩展名（不区分大小写），见 §5.2。iso 默认不参与重命名但可解析。 */
    val videoExtensions: Set<String> = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb", "iso",
    )

    /** 字幕扩展名（伴随文件）。 */
    val subtitleExtensions: Set<String> = setOf("srt", "ass", "ssa", "sub", "idx")

    /** 海报/nfo 等伴随文件扩展名。 */
    val companionExtensions: Set<String> = setOf("nfo", "jpg", "jpeg", "png", "tbn", "bnr")

    fun parse(fileName: String): ParsedFilename {
        val raw = fileName.trim()
        // 1. 去扩展名
        val (baseName, _) = splitExtension(raw)
        // 2. 取出方括号/圆括号内的发布信息，但保留含年份的圆括号
        val tokens = extractBracketed(baseName)
        // 3. 统一分隔符：. _ -> 空格（方括号移除后可能残留前导空格，trim 以免绕过开头数字检查）
        val spaced = normalizeSeparators(tokens.cleaned).trim()
        // 4. 解析技术标签
        val tech = parseTech(baseName)
        // 5. 解析年份：先在 tokens.cleaned（保留 . _ - 原分隔符）上剔除 DAILY_SHOW 日期串，
        //    再归一化分隔符，避免 `2024.01.15 Show` 中的 2024 被误取为年份。
        val year = parseYear(normalizeSeparators(DAILY_SHOW.replace(tokens.cleaned, " ")))
        // 6. 解析季集（日期型在归一化前的原文上检测，因日期分隔符为 . _ -）
        //    ABSOLUTE_EP 仅在无年份（避免误伤 Apollo 13 1995）且无其他季集标记时尝试。
        val seasonEpisode = parseSeasonEpisode(spaced, tokens.brackets, baseName, year)
        // 7. 解析分片序号
        val part = parsePart(spaced)
        // 8. 解析版本标签（v2/Repack/Proper/Final/Rerelease）
        val version = parseVersion(spaced)
        // 9. 剔除技术标签尾巴得到标题候选（命中绝对集号时一并剔除尾随数字）
        val title = cleanTitle(spaced, stripAbsoluteEp = (year == null && seasonEpisode.isAbsolute))
        // 10. 推断媒体类型
        val mediaType = if (seasonEpisode.hasSeasonOrEpisode()) MediaType.EPISODE else MediaType.MOVIE

        return ParsedFilename(
            title = title?.takeIf { it.isNotBlank() },
            year = year,
            season = seasonEpisode.season,
            episodes = seasonEpisode.episodes,
            resolution = tech.resolution,
            source = tech.source,
            videoCodec = tech.videoCodec,
            audioCodec = tech.audioCodec,
            group = tech.group ?: tokens.group,
            partIndex = part,
            isDailyShow = seasonEpisode.daily,
            mediaType = mediaType,
            isAbsoluteEpisode = seasonEpisode.isAbsolute,
            version = version,
        )
    }

    // ---- 扩展名 ----
    fun splitExtension(name: String): Pair<String, String> {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0) return name to ""
        val ext = name.substring(lastDot + 1).lowercase()
        if (ext.length in 1..5 && ext.none { it.isWhitespace() }) {
            return name.substring(0, lastDot) to ext
        }
        return name to ""
    }

    // ---- 方括号/圆括号 ----
    private data class BracketResult(val cleaned: String, val brackets: List<String>, val group: String?)

    private fun extractBracketed(input: String): BracketResult {
        val brackets = mutableListOf<String>()
        var group: String? = null
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '[' || c == '(') {
                val close = if (c == '[') ']' else ')'
                val end = input.indexOf(close, i + 1)
                if (end != -1) {
                    val inner = input.substring(i + 1, end)
                    brackets.add(inner)
                    val isYearParen = c == '(' && YEAR_IN_PARENS.containsMatchIn(inner)
                    if (isYearParen) {
                        sb.append(' ').append(inner).append(' ')
                    } else {
                        if (c == '[' && GROUP_TOKEN.matches(inner) && group == null) {
                            group = inner
                        }
                    }
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return BracketResult(sb.toString(), brackets, group)
    }

    // ---- 分隔符归一 ----
    private fun normalizeSeparators(input: String): String =
        input.replace('_', ' ').replace('.', ' ')

    // ---- 季集解析 ----
    private data class SeasonEpisodeResult(
        val season: Int?,
        val episodes: List<Int>,
        val daily: Boolean,
        val isAbsolute: Boolean = false,
    ) {
        fun hasSeasonOrEpisode() = season != null || episodes.isNotEmpty() || daily
    }

    private fun parseSeasonEpisode(
        spaced: String,
        brackets: List<String>,
        rawBase: String,
        year: Int?,
    ): SeasonEpisodeResult {
        // S01E01E02 / S01E01-E03 / s1e2
        SEASON_EPISODE.find(spaced)?.let { m ->
            val season = m.groupValues[1].toInt()
            val episodes = parseEpisodeList(m.groupValues[2])
            if (episodes.isNotEmpty()) return SeasonEpisodeResult(season, episodes, false)
        }
        // 季节范围 S01-S03 / S01–S03（多季合集，记起始季，标 EPISODE）
        SEASON_RANGE.find(spaced)?.let { m ->
            val start = m.groupValues[1].toIntOrNull() ?: return@let
            return SeasonEpisodeResult(start, emptyList(), false)
        }
        // 1x02
        NX_N.find(spaced)?.let { m ->
            return SeasonEpisodeResult(m.groupValues[1].toInt(), listOf(m.groupValues[2].toInt()), false)
        }
        // 第X季第X集/话/章/回/篇（阿拉伯数字）
        CHINESE_SEASON_EP.find(spaced)?.let { m ->
            return SeasonEpisodeResult(m.groupValues[1].toIntOrNull(), listOf(m.groupValues[2].toInt()), false)
        }
        // 第X季第X集/话/章/回/篇（中文数字：第一季第二集）
        CHINESE_NUM_SEASON_EP.find(spaced)?.let { m ->
            val s = chineseToInt(m.groupValues[1])
            val e = chineseToInt(m.groupValues[2])
            if (s != null && e != null) return SeasonEpisodeResult(s, listOf(e), false)
        }
        // 第X集/话/章/回/篇（阿拉伯数字）
        CHINESE_EP_ONLY.find(spaced)?.let { m ->
            return SeasonEpisodeResult(null, listOf(m.groupValues[1].toInt()), false)
        }
        // 第X集/话/章/回/篇（中文数字：第拾贰话）
        CHINESE_NUM_EP.find(spaced)?.let { m ->
            val e = chineseToInt(m.groupValues[1]) ?: return@let
            return SeasonEpisodeResult(null, listOf(e), false)
        }
        // 第X季（中文数字，仅季无集：第二季）
        CHINESE_NUM_SEASON.find(spaced)?.let { m ->
            val s = chineseToInt(m.groupValues[1]) ?: return@let
            return SeasonEpisodeResult(s, emptyList(), false)
        }
        // 独立集号 E02 / EP02（需 E/EP 前缀，避免误判 Apollo 13 / 2012）
        STANDALONE_EP.find(spaced)?.let { m ->
            return SeasonEpisodeResult(null, listOf(m.groupValues[1].toInt()), false)
        }
        // [02] 方括号形式：括号内纯 1-3 位数字视为集号（标记为绝对集号）
        if (brackets.any { BRACKET_EP.matches(it) }) {
            val ep = brackets.first { BRACKET_EP.matches(it) }.toInt()
            return SeasonEpisodeResult(null, listOf(ep), false, isAbsolute = true)
        }
        // 日期型剧集 2024.01.15 / 2024-01-15（在归一化前的原文上检测）
        if (DAILY_SHOW.containsMatchIn(rawBase)) {
            return SeasonEpisodeResult(null, emptyList(), daily = true)
        }
        // 绝对集号：尾随 1-3 位数字（如 Show 12 / Show-12 / Show 01-03）；
        // 仅在无年份（避免误伤 Apollo 13 1995 这类电影）且无其他季集标记时尝试。
        if (year == null) {
            tryAbsoluteEpisode(spaced)?.let { return it }
        }
        return SeasonEpisodeResult(null, emptyList(), false)
    }

    /**
     * 绝对集号识别：在无其他季集标记时，取文件名中最后一个独立的 1-3 位数字作为集号；
     * 支持 `01-03` 区间。排除：位于开头的数字（如 "12 Monkeys" 标题）、年份范围、分辨率片段。
     */
    private fun tryAbsoluteEpisode(spaced: String): SeasonEpisodeResult? {
        val matches = ABSOLUTE_EP.findAll(spaced).toList()
        if (matches.isEmpty()) return null
        val m = matches.last()
        val numGroup = m.groups[1] ?: return null
        // 数字位于字符串开头很可能是标题的一部分（如 "12 Monkeys"），跳过
        if (numGroup.range.first <= 0) return null
        val first = numGroup.value.toIntOrNull() ?: return null
        // 排除年份范围（4 位年份本就不会匹配 \d{1,3}，但稳妥起见再过滤）
        if (first in 1900..2099) return null
        // 排除分辨率片段（720/480/540/360 等，正常有 p/i 后缀已被边界拦截，此处双保险）
        if (first in TECH_NUMBERS) return null
        // 集号区间 01-03
        val rangeEnd = m.groups[2]?.value?.toIntOrNull()
        val episodes = if (rangeEnd != null && rangeEnd >= first) {
            (first..rangeEnd).toList()
        } else {
            listOf(first)
        }
        return SeasonEpisodeResult(null, episodes, false, isAbsolute = true)
    }

    private fun parseEpisodeList(raw: String): List<Int> {
        // raw 如 "01E02E03"、"01-E03"、"01-E03E05"（意图 1-3 + 5）。
        // B24: 旧实现把整个 raw 的所有数字取出后 (first..last) 展开，导致 "01-E03E05"
        // 被解析为 [1,2,3,4,5]，多出不存在的第 4 集。
        // B24 补丁：按 `-` 分段的写法会把 "01-E03" 拆成 ["01","E03"] 各自当单集 → [1,3]，
        // 丢失区间展开。改为先用 RANGE_EP 正则识别 "N-M"（两端均可带 E 前缀）并展开，
        // 再把剩余独立集号合并进来，去重排序：
        // - "01-E03" → 区间 [1,2,3]
        // - "01-E03E05" → 区间 [1,2,3] + 单集 [5] = [1,2,3,5]
        // - "01E02E03" → 无区间，各取集号 [1,2,3]
        val result = mutableSetOf<Int>()
        var remaining = raw
        RANGE_EP.findAll(raw).forEach { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            if (end >= start) result.addAll(start..end) else { result.add(start); result.add(end) }
            remaining = remaining.replace(m.value, "")
        }
        EPISODE_NUM.findAll(remaining).forEach { result.add(it.value.toInt()) }
        return result.sorted()
    }

    // ---- 技术标签 ----
    private data class TechResult(
        val resolution: String?,
        val source: String?,
        val videoCodec: String?,
        val audioCodec: String?,
        val group: String?,
    )

    private fun parseTech(input: String): TechResult {
        val resolution = RESOLUTION.find(input)?.value?.lowercase()
        val source = findSource(input)
        val videoCodec = VIDEO_CODEC.find(input)?.value?.lowercase()
        val audioCodec = AUDIO_CODEC.find(input)?.value?.lowercase()
        val group = GROUP_SUFFIX.find(input)?.groupValues?.get(1)
        return TechResult(resolution, source, videoCodec, audioCodec, group)
    }

    private fun findSource(input: String): String? {
        for (match in SOURCE_TOKEN.findAll(input)) {
            val token = match.value.lowercase()
            return when {
                token.startsWith("blu-ray") || token.startsWith("bluray") || token.startsWith("bdrip") ||
                    token.startsWith("bd25") || token.startsWith("bd50") || token.startsWith("brrip") -> "BluRay"
                token.startsWith("web-dl") || token == "webdl" || token == "web" -> "WEB-DL"
                token.startsWith("webrip") -> "WEBRip"
                token.startsWith("hdtv") -> "HDTV"
                token.startsWith("dvdrip") || token == "dvd" -> "DVDRip"
                token.startsWith("remux") -> "Remux"
                else -> token
            }
        }
        return null
    }

    // ---- 年份 ----
    private fun parseYear(input: String): Int? {
        YEAR.find(input)?.let { m ->
            val y = m.value.toInt()
            if (y in 1900..2099) return y
        }
        return null
    }

    // ---- 分片序号 ----
    private fun parsePart(input: String): Int? {
        PART.find(input)?.let { return it.groupValues[1].toInt() }
        return null
    }

    // ---- 标题清洗 ----
    private fun cleanTitle(spaced: String, stripAbsoluteEp: Boolean): String? {
        var t = spaced
        t = SEASON_EPISODE.replace(t, " ")
        t = SEASON_RANGE.replace(t, " ")
        t = NX_N.replace(t, " ")
        t = CHINESE_SEASON_EP.replace(t, " ")
        t = CHINESE_NUM_SEASON_EP.replace(t, " ")
        t = CHINESE_EP_ONLY.replace(t, " ")
        t = CHINESE_NUM_EP.replace(t, " ")
        t = CHINESE_NUM_SEASON.replace(t, " ")
        t = STANDALONE_EP.replace(t, " ")
        t = DAILY_SHOW.replace(t, " ")
        if (stripAbsoluteEp) {
            t = ABSOLUTE_EP.replace(t, " ")
        }
        t = VERSION_TAG.replace(t, " ")
        // 剔除技术标签串尾巴：从首个技术标签到结尾
        TECH_TAIL.find(t)?.let { techMatch ->
            t = t.substring(0, techMatch.range.first).trimEnd()
        }
        t = YEAR.replace(t, " ")
        t = PART.replace(t, " ")
        t = GROUP_SUFFIX.replace(t, " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.ifBlank { null }
    }

    // ---- 版本标签 ----
    private fun parseVersion(input: String): String? = VERSION_TAG.find(input)?.value

    // ---- 中文数字转阿拉伯 ----
    /**
     * 中文数字（含大写）转 Int，支持 零一二三四五六七八九 / 壹贰叁肆伍陆柒捌玖 / 十拾 / 百佰 / 两 / 〇。
     * 组合示例：一=1, 十=10, 十二=12, 二十=20, 二十三=23, 一百零一=101, 拾贰=12。
     * 剧集集号一般不超过百，但此处仍支持百位。无法识别返回 null。
     */
    private fun chineseToInt(str: String): Int? {
        if (str.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '〇' to 0,
            '一' to 1, '壹' to 1,
            '二' to 2, '贰' to 2, '两' to 2,
            '三' to 3, '叁' to 3,
            '四' to 4, '肆' to 4,
            '五' to 5, '伍' to 5,
            '六' to 6, '陆' to 6,
            '七' to 7, '柒' to 7,
            '八' to 8, '捌' to 8,
            '九' to 9, '玖' to 9,
        )
        if (str.any { it !in digits && it != '十' && it != '拾' && it != '百' && it != '佰' }) return null
        var total = 0
        var section = 0
        for (c in str) {
            when (c) {
                in digits -> section = digits[c]!!
                '十', '拾' -> {
                    if (section == 0) section = 1
                    total += section * 10
                    section = 0
                }
                '百', '佰' -> {
                    if (section == 0) section = 1
                    total += section * 100
                    section = 0
                }
            }
        }
        total += section
        return total
    }

    companion object {
        // 中文数字字符集（含大写）：零一二三四五六七八九十百 + 壹贰叁肆伍陆柒捌玖拾佰 + 〇 + 两
        private const val CN_NUM = "一二三四五六七八九十百零壹贰叁肆伍陆柒捌玖拾佰〇两"

        // S01E01E02 / S01E01-E03 / s1e2 — group2 捕获集号串（支持 E 与 - 分隔，含混合 01-E03）
        private val SEASON_EPISODE = Regex("(?i)S(\\d{1,2})E(\\d{1,3}(?:[-]?E?\\d{1,3})*)")
        private val EPISODE_NUM = Regex("\\d{1,3}")
        // B24 补丁：集号区间 N-M（两端均可带 E 前缀，如 01-E03 / E01-E03 / 01-03）。
        private val RANGE_EP = Regex("(?i)E?(\\d{1,3})\\s*-\\s*E?(\\d{1,3})")
        // 季节范围 S01-S03 / S01–S03（unicode en dash 也支持）
        private val SEASON_RANGE = Regex("(?i)S(\\d{1,2})\\s?[-–]\\s?S(\\d{1,2})(?!\\d)")
        private val NX_N = Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)")
        // 第X季第X集/话/章/回/篇（阿拉伯数字）
        private val CHINESE_SEASON_EP = Regex("第(\\d{1,2})季第(\\d{1,3})(?:集|话|章|回|篇)")
        // 第X季第X集/话/章/回/篇（中文数字：第一季第二集）
        private val CHINESE_NUM_SEASON_EP = Regex("第([$CN_NUM]+)季第([$CN_NUM]+)(?:集|话|章|回|篇)")
        // 第X集/话/章/回/篇（阿拉伯数字）
        private val CHINESE_EP_ONLY = Regex("第(\\d{1,3})(?:集|话|章|回|篇)")
        // 第X集/话/章/回/篇（中文数字：第拾贰话）
        private val CHINESE_NUM_EP = Regex("第([$CN_NUM]+)(?:集|话|章|回|篇)")
        // 第X季（中文数字，仅季无集：第二季）
        private val CHINESE_NUM_SEASON = Regex("第([$CN_NUM]+)季")
        // E02 / EP02（必须 E/EP 前缀）
        private val STANDALONE_EP = Regex("(?i)(?<![A-Za-z])EP?(\\d{1,3})(?!\\d)")
        private val BRACKET_EP = Regex("^\\d{1,3}$")
        // 绝对集号：独立 1-3 位数字（可选区间 -XX），需前后为边界分隔符；命中后由 tryAbsoluteEpisode 二次校验
        private val ABSOLUTE_EP = Regex("(?:^|\\s|[-_])(\\d{1,3})(?:[-](\\d{1,3}))?(?:$|\\s|[-_])")
        // 版本标签：v2/v3/Repack/Proper/Final/Rerelease（须前置分隔符，避免误匹配 "Final Destination" 这类标题开头）
        private val VERSION_TAG = Regex("(?i)(?<=\\s|[-_.])(v\\d{1,2}|repack|proper|final|rerelease)(?=\\s|[-_.]|$)")
        // 已知技术词数字（分辨率高度等），用于绝对集号二次过滤
        private val TECH_NUMBERS = setOf(720, 480, 540, 360, 240, 1080, 2160, 4320)
        private val DAILY_SHOW = Regex("(?<!\\d)(19|20)\\d{2}[._-](0?[1-9]|1[0-2])[._-]([0-2]?[0-9]|3[01])(?!\\d)")
        private val YEAR = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
        private val YEAR_IN_PARENS = Regex("(?i)(19\\d{2}|20\\d{2})")
        // B9: 不能用 \b——下划线是 \w 字符，\b 不会在 _ 与字母/数字间匹配，
        // 导致下划线分隔文件名（The_Last_of_Us_S01E02_1080p_WEB-DL_x264）的 _1080p/_WEB-DL/_x264
        // 均无法识别。改用自定义边界 (?<![A-Za-z0-9]) / (?![A-Za-z0-9])，下划线视为合法分隔符。
        private val RESOLUTION = Regex("(?i)(?<![A-Za-z0-9])(2160p|1080p|720p|540p|480p|360p|4320p|4K|8K)(?![A-Za-z0-9])")
        private val SOURCE_TOKEN = Regex("(?i)(?<![A-Za-z0-9])(Blu-?Ray|BDRip|BD25|BD50|BRRip|WEB-?DL|WEBDL|WEBRip|WEB|HDTV|DVDRip|DVD|R5|CAM|REMUX|HD-?TS|HD-?TC|PDVD)(?![A-Za-z0-9])")
        private val VIDEO_CODEC = Regex("(?i)(?<![A-Za-z0-9])(x264|x265|h264|h265|hevc|av1|vp9|divx|xvid|mpeg-?2|mpeg-?4|vc1)(?![A-Za-z0-9])")
        private val AUDIO_CODEC = Regex("(?i)(?<![A-Za-z0-9])(AAC|AC3|EAC3|DDP|DDPA|DD|DTS|DTS-?HD|DTS-?MA|TrueHD|Atmos|FLAC|MP3|PCM|Opus)(?![A-Za-z0-9])")
        private val GROUP_TOKEN = Regex("^[A-Za-z0-9]{2,}$")
        private val GROUP_SUFFIX = Regex("(?i)[\\-\\.]([A-Za-z0-9]{2,})$")
        private val PART = Regex("(?i)(?:^|\\s)(?:CD|DISC|PART|PT)\\s?(\\d{1,2})(?:$|\\s)")
        // 技术标签尾巴：匹配从首个技术标签起的位置
        private val TECH_TAIL = Regex("(?i)\\b(2160p|1080p|720p|480p|540p|Blu-?Ray|WEB-?DL|WEBRip|HDTV|DVDRip|REMUX|x264|x265|h264|h265|hevc|av1|AAC|AC3|DTS|TrueHD|Atmos|FLAC)")
    }
}
