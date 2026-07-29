package xa.refile.core.parser

import xa.refile.core.model.MediaType

/**
 * 文件名解析引擎（计划 §5.3）。
 *
 * 纯 Kotlin 无 Android 依赖，便于 JVM 单元测试。
 * 规则表驱动：季集模式 → 技术标签 → 清洗。
 *
 * P0/P1/P2 增量：
 * - P0.1：用 [ReleaseInfoDictionary.HARD_STOPWORDS] 词表替代 TECH_TAIL 启发式（左扫描算法）。
 * - P0.2：多集区间上限保护 [MAX_EPISODE_RANGE]。
 * - P0.3：多集尾号字符保护（避免分辨率误判为集号区间）。
 * - P0.4：季集号 sanity 上限（[MAX_SEASON]/[MAX_EPISODE]/[MAX_ABSOLUTE_EPISODE]）。
 * - P1.1：Edition 标签（[Edition]）。
 * - P1.2：HDR / 3D 标签。
 * - P1.3：Anime CRC 方括号命名（[tryAnimePattern]）。
 * - P1.4：Stacking 扩展（PART_LETTER/PART_OF/PART_SUFFIX）。
 * - P1.5：IMDb ID 提取。
 * - P1.6：流媒体来源标记。
 * - P1.7：字幕语言标签（[SubtitleLanguageParser]）。
 * - P2.5：Extras 识别（[ExtraType]）。
 * - P2.6：cleanTitle 拆成 cleanStep 链式迭代。
 */
class FilenameParser {

    /** 视频扩展名（不区分大小写），见 §5.2。iso 默认不参与重命名但可解析。
     *  mk3d/mks/mka 为 Matroska 系列衍生（3D / 字幕流 / 音频流），ogm 为 Ogg Media 容器，
     *  strm/strmlnk 为 Kodi/Jellyfin 流媒体指向文件（文本条目，按视频库管理）。 */
    val videoExtensions: Set<String> = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb", "iso",
        "mk3d", "mks", "mka", "ogm", "strm", "strmlnk",
    )

    /** 字幕扩展名（伴随文件）。sup 为 PGS 图形字幕（Remux 极常见），vtt 为 WebVTT，smi 为 SAMI。 */
    val subtitleExtensions: Set<String> = setOf("srt", "ass", "ssa", "sub", "idx", "sup", "vtt", "smi")

    /** 海报/nfo 等伴随文件扩展名。 */
    val companionExtensions: Set<String> = setOf("nfo", "jpg", "jpeg", "png", "tbn", "bnr")

    fun parse(fileName: String): ParsedFilename {
        val raw = fileName.trim()
        // 1. 去扩展名
        val (baseName, ext) = splitExtension(raw)

        // P1.3：Anime CRC 方括号命名优先识别（如 `[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv`）。
        // 命中后跳过通用季集解析（标题在方括号内，常规 cleanTitle 会丢失）。
        tryAnimePattern(baseName)?.let { anime ->
            val tech = parseTech(baseName)
            val imdbId = parseImdbId(raw)
            val extraType = parseExtraType(baseName)
            return anime.copy(
                resolution = anime.resolution ?: tech.resolution,
                videoCodec = anime.videoCodec ?: tech.videoCodec,
                audioCodec = anime.audioCodec ?: tech.audioCodec,
                source = tech.source,
                group = anime.group ?: tech.group,
                imdbId = imdbId,
                extraType = extraType,
            )
        }

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
        // P3.0：集号字母后缀 a-i（同集分上下部，如 S01E02a → "a"）
        val episodeSubPart = seasonEpisode.episodeSubPart
        // 7. 解析分片序号
        val part = parsePart(spaced)
        // 8. 解析版本标签（v2/Repack/Proper/Final/Rerelease）
        val version = parseVersion(spaced)
        // 8b. P1.1：解析 Edition 标签（Director's Cut / IMAX / Extended / ...）
        val edition = parseEdition(spaced)
        // 8c. P1.2：解析 HDR / 3D 标签
        val hdr = parseHdr(baseName)
        val threeD = parse3D(baseName)
        // 8d. P3.0：解析 Provider ID（IMDb/TMDB/TVDB/TVMaze，含 URL 形态）
        val providerIds = parseProviderIds(raw)
        val imdbId = providerIds.imdbId
        // 8e. P1.6：解析流媒体来源（AMZN/NF/ATVP/HMAX/...）
        val streamingSource = parseStreamingSource(baseName)
        // 8f. P1.7：字幕文件扩展名分支 — 解析语言标签与修饰符
        val subtitleInfo = if (ext in subtitleExtensions) {
            SubtitleLanguageParser.parse(baseName)
        } else null
        // 8g. P2.5：附加内容类型识别（Trailer/Sample/...）
        val extraType = parseExtraType(spaced)
        // 9. 剔除技术标签尾巴得到标题候选（命中绝对集号时一并剔除尾随数字）
        val title = cleanTitle(
            spaced,
            stripAbsoluteEp = (year == null && seasonEpisode.isAbsolute),
            knownGroup = tech.group,
            releaseYear = year,
        )
        // 9b. P2.6：中英混合标题拆分别名（`寒战1994 Cold War` → title="寒战1994", aliases=["Cold War"]）
        val (primaryTitle, titleAliases) = splitTitleAliases(title?.takeIf { it.isNotBlank() })
        // 10. 推断媒体类型
        val mediaType = if (seasonEpisode.hasSeasonOrEpisode()) MediaType.EPISODE else MediaType.MOVIE

        return ParsedFilename(
            title = primaryTitle,
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
            edition = edition,
            hdr = hdr,
            threeD = threeD,
            imdbId = imdbId,
            streamingSource = streamingSource,
            subtitleInfo = subtitleInfo,
            extraType = extraType,
            titleAliases = titleAliases,
            tmdbId = providerIds.tmdbId,
            tvdbId = providerIds.tvdbId,
            tvmazeId = providerIds.tvmazeId,
            episodeSubPart = episodeSubPart,
        )
    }

    /**
     * P2.6：中英混合标题拆分。
     *
     * 当清洗后的标题同时含 CJK 段与 ASCII 段（如 `寒战1994 Cold War`）时，按空白分词后
     * 依"是否含 CJK"分组连续 token：含 CJK 的 token（含其内部紧贴的数字，如 `寒战1994`）
     * 与纯 ASCII token 各自合并成段。第一段（含 CJK 的优先）作为 [ParsedFilename.title]，
     * 其余段作为 [ParsedFilename.titleAliases]。
     *
     * 拆分后匹配器会分别用主标题与别名搜 TMDB，合并候选后由评分器取最高分，避免混合串搜不到。
     * 纯 CJK 或纯 ASCII 标题不受影响（aliases 为空）。
     */
    private fun splitTitleAliases(title: String?): Pair<String?, List<String>> {
        if (title.isNullOrBlank()) return null to emptyList()
        // 仅当标题同时含 CJK 与 ASCII 字母时才拆分
        val hasCjk = title.any { it.isCjk() }
        val hasAscii = title.any { it.isAsciiLetter() }
        if (!hasCjk || !hasAscii) return title to emptyList()
        // 按空白拆 token，连续同性质（含/不含 CJK）的 token 合并为一段。
        // 关键：CJK token 内部紧贴的数字（如 `寒战1994`）不再被 Han↔digit 边界拆开。
        val tokens = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return title to emptyList()
        val segments = mutableListOf<String>()
        var current = StringBuilder(tokens[0])
        var currentHasCjk = tokens[0].any { it.isCjk() }
        for (tok in tokens.drop(1)) {
            val tokHasCjk = tok.any { it.isCjk() }
            if (tokHasCjk != currentHasCjk) {
                segments.add(current.toString().trim())
                current = StringBuilder()
                currentHasCjk = tokHasCjk
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(tok)
        }
        if (current.isNotEmpty()) segments.add(current.toString().trim())
        if (segments.size <= 1) return title to emptyList()
        // 主标题取第一个含 CJK 的段（中文片名优先），其余为别名
        val primary = segments.firstOrNull { it.any { c -> c.isCjk() } } ?: segments.first()
        val aliases = segments.filter { it != primary }
        return primary to aliases
    }

    // ---- 扩展名 ----
    fun splitExtension(name: String): Pair<String, String> {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0) return name to ""
        val ext = name.substring(lastDot + 1).lowercase()
        // 上限放宽到 7 以容纳 strmlnk；仍排除含空格的怪异尾巴。
        if (ext.length in 1..7 && ext.none { it.isWhitespace() }) {
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

    // ---- P1.3 Anime CRC 方括号命名 ----
    /**
     * Anime 风格命名：`[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv`。
     * 识别条件：至少 3 个连续方括号，其中第 2 个为标题、第 3 个为纯数字集号。
     * CRC 8 位 hex 单独识别并丢弃。命中后标记 isAbsoluteEpisode。
     */
    private fun tryAnimePattern(baseName: String): ParsedFilename? {
        val brackets = ANIME_BRACKETS.findAll(baseName).map { it.value }.toList()
        if (brackets.size < 3) return null
        // 解析所有方括号内容
        val contents = baseName.split(Regex("[\\[\\]]"))
            .filter { it.isNotBlank() }
        if (contents.size < 3) return null
        // 第 1 个是 group，第 2 个是 series title，第 3 个需为纯数字集号
        val group = contents[0].trim().takeIf { GROUP_TOKEN.matches(it) }
        val title = contents[1].trim().takeIf { it.isNotBlank() } ?: return null
        val epStr = contents.getOrNull(2)?.trim() ?: return null
        if (!BRACKET_EP.matches(epStr)) return null
        val ep = epStr.toInt()
        // 其余方括号识别分辨率/编码/CRC
        val tech = parseTech(baseName)
        return ParsedFilename(
            title = title,
            episodes = listOf(ep),
            mediaType = MediaType.EPISODE,
            isAbsoluteEpisode = true,
            resolution = tech.resolution,
            videoCodec = tech.videoCodec,
            audioCodec = tech.audioCodec,
            source = tech.source,
            group = group ?: tech.group,
        )
    }

    // ---- 季集解析 ----
    private data class SeasonEpisodeResult(
        val season: Int?,
        val episodes: List<Int>,
        val daily: Boolean,
        val isAbsolute: Boolean = false,
        val episodeSubPart: String? = null,
    ) {
        fun hasSeasonOrEpisode() = season != null || episodes.isNotEmpty() || daily
    }

    private fun parseSeasonEpisode(
        spaced: String,
        brackets: List<String>,
        rawBase: String,
        year: Int?,
    ): SeasonEpisodeResult {
        return sanitizeSeasonEpisode(parseSeasonEpisodeRaw(spaced, brackets, rawBase, year))
    }

    /**
     * P0.4：季集号 sanity 校验。
     * - season：允许 0（特别篇 S00）或 1..MAX_SEASON，超限置 null
     * - episodes：非绝对集号需在 1..MAX_EPISODE；绝对集号放宽至 1..MAX_ABSOLUTE_EPISODE；任一越界则整组置空
     * - isAbsolute：episodes 被清空时同步置 false
     */
    private fun sanitizeSeasonEpisode(result: SeasonEpisodeResult): SeasonEpisodeResult {
        val season = result.season?.let { s ->
            if (s == 0 || s in 1..MAX_SEASON) s else null
        }
        // SxxExx 形式但集号为 3-4 位（如 One Piece S21E1000，季21=整体第1000话）
        // 视为绝对集号，放宽到 MAX_ABSOLUTE_EPISODE 校验。
        // 阈值 100：E51 这类常规越界集号不当作绝对集号（仍按 MAX_EPISODE 丢弃），
        // 仅 E100+ 视为长篇动画绝对集号。
        val hasLargeEp = result.episodes.any { it >= 100 }
        val maxEp = if (result.isAbsolute || hasLargeEp) MAX_ABSOLUTE_EPISODE else MAX_EPISODE
        val episodes = if (result.episodes.isNotEmpty() && result.episodes.all { it in 1..maxEp }) {
            result.episodes
        } else {
            emptyList()
        }
        val isAbsolute = (result.isAbsolute || hasLargeEp) && episodes.isNotEmpty()
        return result.copy(season = season, episodes = episodes, isAbsolute = isAbsolute)
    }

    private fun parseSeasonEpisodeRaw(
        spaced: String,
        brackets: List<String>,
        rawBase: String,
        year: Int?,
    ): SeasonEpisodeResult {
        // P3.0：Anime Special/OVA 前缀识别（→ season=0，特别篇）
        // ANIME_SPECIAL_TOKEN 检查 spaced（清洗后文本中的独立词 OVA/Special）；
        // ANIME_SPECIAL_BRACKET 检查 rawBase（含原始方括号 [Special] 形态）。
        if (ANIME_SPECIAL_TOKEN.containsMatchIn(spaced) || ANIME_SPECIAL_BRACKET.containsMatchIn(rawBase)) {
            return SeasonEpisodeResult(0, emptyList(), false)
        }
        // S01E01E02 / S01E01-E03 / s1e2
        SEASON_EPISODE.find(spaced)?.let { m ->
            val season = m.groupValues[1].toInt()
            val episodes = parseEpisodeList(m.groupValues[2])
            if (episodes.isNotEmpty()) {
                val suffix = if (episodes.size == 1) extractEpisodeLetterSuffix(spaced, m.range.first, m.range.last + 1) else null
                return SeasonEpisodeResult(season, episodes, false, episodeSubPart = suffix)
            }
        }
        // P3.0：Season 1 Episode 2 / Staffel 2 Episode 5（含多语言季关键词）
        SEASON_WORD_EP.find(spaced)?.let { m ->
            val season = m.groupValues[1].toInt()
            val epStart = m.groupValues[2].toInt()
            val epEnd = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            val episodes = if (epEnd != null && epEnd >= epStart && epEnd - epStart <= MAX_EPISODE_RANGE) {
                (epStart..epEnd).toList()
            } else {
                listOf(epStart)
            }
            val suffix = extractEpisodeLetterSuffix(spaced, m.range.first, m.range.last + 1)
            return SeasonEpisodeResult(season, episodes, false, episodeSubPart = suffix)
        }
        // P3.0：Episode 16 / Episode 16-20（独立词模式）
        EPISODE_WORD.find(spaced)?.let { m ->
            val epStart = m.groupValues[1].toInt()
            val epEnd = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            val episodes = if (epEnd != null && epEnd >= epStart && epEnd - epStart <= MAX_EPISODE_RANGE) {
                (epStart..epEnd).toList()
            } else {
                listOf(epStart)
            }
            val suffix = extractEpisodeLetterSuffix(spaced, m.range.first, m.range.last + 1)
            return SeasonEpisodeResult(null, episodes, false, isAbsolute = true, episodeSubPart = suffix)
        }
        // 季节范围 S01-S03 / S01–S03（多季合集，记起始季，标 EPISODE）
        SEASON_RANGE.find(spaced)?.let { m ->
            val start = m.groupValues[1].toIntOrNull() ?: return@let
            return SeasonEpisodeResult(start, emptyList(), false)
        }
        // 1x02
        NX_N.find(spaced)?.let { m ->
            val suffix = extractEpisodeLetterSuffix(spaced, m.range.first, m.range.last + 1)
            return SeasonEpisodeResult(m.groupValues[1].toInt(), listOf(m.groupValues[2].toInt()), false, episodeSubPart = suffix)
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
        // P0.4：无季上下文的中文集号视为绝对集号（适用 MAX_ABSOLUTE_EPISODE=1000 上限，
        //       避免长篇动画 `第101话` 被误判越界）。
        CHINESE_EP_ONLY.find(spaced)?.let { m ->
            return SeasonEpisodeResult(null, listOf(m.groupValues[1].toInt()), false, isAbsolute = true)
        }
        // 第X集/话/章/回/篇（中文数字：第拾贰话）
        CHINESE_NUM_EP.find(spaced)?.let { m ->
            val e = chineseToInt(m.groupValues[1]) ?: return@let
            return SeasonEpisodeResult(null, listOf(e), false, isAbsolute = true)
        }
        // 第X季（中文数字，仅季无集：第二季）
        CHINESE_NUM_SEASON.find(spaced)?.let { m ->
            val s = chineseToInt(m.groupValues[1]) ?: return@let
            return SeasonEpisodeResult(s, emptyList(), false)
        }
        // P3.0：仅季字面词（Staffel 3 / Saison 3，无 Episode 后缀）
        SEASON_WORD_ONLY.find(spaced)?.let { m ->
            return SeasonEpisodeResult(m.groupValues[1].toInt(), emptyList(), false)
        }
        // 独立集号 E02 / EP02（需 E/EP 前缀，避免误判 Apollo 13 / 2012）
        STANDALONE_EP.find(spaced)?.let { m ->
            return SeasonEpisodeResult(null, listOf(m.groupValues[1].toInt()), false)
        }
        // 仅季号 S01 / S1（scene 季打包 `S01.COMPLETE` 常见，无集号）
        // 前导非字母避免误伤 `Series`；放在 STANDALONE_EP 之后、BRACKET_EP 之前。
        SEASON_ONLY.find(spaced)?.let { m ->
            return SeasonEpisodeResult(m.groupValues[1].toInt(), emptyList(), false)
        }
        // [02] 方括号形式：括号内纯 1-3 位数字视为集号（标记为绝对集号）
        if (brackets.any { BRACKET_EP.matches(it) }) {
            val ep = brackets.first { BRACKET_EP.matches(it) }.toInt()
            return SeasonEpisodeResult(null, listOf(ep), false, isAbsolute = true)
        }
        // P3.0：日优先日期格式（欧洲）
        if (DAILY_SHOW_EU.containsMatchIn(rawBase)) {
            return SeasonEpisodeResult(null, emptyList(), daily = true)
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

    /** P3.0：检测 SxxExx / NxEyy 匹配后的字母后缀 a-i（同集分上下部）。 */
    private fun extractEpisodeLetterSuffix(spaced: String, matchStart: Int, matchEnd: Int): String? {
        if (matchEnd >= spaced.length) return null
        val next = spaced[matchEnd]
        if (!(next in 'a'..'i' || next in 'A'..'I')) return null
        // 再后一个字符需为边界（空格、点、横线、末尾），避免误抓多字符 token
        val afterNext = spaced.getOrNull(matchEnd + 1)
        val isBoundary = afterNext == null || afterNext.isWhitespace() || afterNext in "._-"
        if (!isBoundary) return null
        return next.lowercase().toString()
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
        // 集号区间 01-03（P0.2：跨度超限时只保留起始集号）
        val rangeEnd = m.groups[2]?.value?.toIntOrNull()
        val episodes = if (rangeEnd != null && rangeEnd >= first && rangeEnd - first <= MAX_EPISODE_RANGE) {
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
        // P0.2：区间跨度 > MAX_EPISODE_RANGE（=5）时视为误判（如 `E01-E99` 实为分辨率粘连），
        //       只保留起始集号，丢弃 end 与中间展开。
        // P0.3：若 end 后紧跟 [0-9iIpP] 字符（如 `s09e14-1080p`），视为分辨率片段，丢弃 end，
        //       并连同尾随分辨率片段（如 "1080p" 中 "108" 后的 "0p"）一起从 remaining 移除，
        //       避免残留数字被 EPISODE_NUM 误收为集号。
        val result = mutableSetOf<Int>()
        var remaining = raw
        RANGE_EP.findAll(raw).forEach { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            // P0.3：检查 end 后是否紧跟 [0-9iIpP]（分辨率后缀字符；不含 E，E 是合法集号前缀）
            val endPos = m.range.last + 1
            val nextChar = raw.getOrNull(endPos)
            val endHasSuffix = nextChar != null && (nextChar.isDigit() || nextChar in "iIpP")
            if (endHasSuffix) {
                // 分辨率片段，只取 start；同时消费尾随 [0-9iIpP] 片段，避免残留数字污染 remaining
                result.add(start)
                var consumeEnd = endPos
                while (consumeEnd < raw.length && (raw[consumeEnd].isDigit() || raw[consumeEnd] in "iIpP")) {
                    consumeEnd++
                }
                val toRemove = raw.substring(m.range.first, consumeEnd)
                remaining = remaining.replace(toRemove, "")
            } else if (end >= start && end - start <= MAX_EPISODE_RANGE) {
                // P0.2：合法区间展开
                result.addAll(start..end)
                remaining = remaining.replace(m.value, "")
            } else {
                // P0.2：跨度超限，只取 start
                result.add(start)
                remaining = remaining.replace(m.value, "")
            }
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
        // 高度+p 优先（如 1080p）；缺失时尝试 WxH 形态（如 1920x1080 → "1080p"）
        var resolution = RESOLUTION.find(input)?.value?.lowercase()
        if (resolution == null) {
            RESOLUTION_WXH.find(input)?.let { m ->
                val height = m.groupValues[2].toIntOrNull()
                if (height != null && height in 360..8640) {
                    resolution = "${height}p"
                }
            }
        }
        val source = findSource(input)
        val videoCodec = VIDEO_CODEC.find(input)?.value?.lowercase()
        val audioCodec = AUDIO_CODEC.find(input)?.value?.lowercase()
        // 组名提取：末尾 `-GROUP` 形式；过滤掉技术词避免 `x264-Group` 误把 x264 当组名
        val group = GROUP_SUFFIX.find(input)?.groupValues?.get(1)
            ?.takeIf { !ReleaseInfoDictionary.isHardStopword(it) }
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

    // ---- P1.6 流媒体来源 ----
    private fun parseStreamingSource(input: String): String? {
        for (match in STREAMING_SOURCE.findAll(input)) {
            return STREAMING_MAP[match.value.lowercase()]
        }
        return null
    }

    // ---- 年份 ----
    // 文件名含多个 4 位年份时（如 `Cold.War.1994.2026.2160p...`，1994 为原作/片中年份，
    // 2026 为发行版年份）取最后一个有效年份：发行版年份通常紧挨分辨率等技术标签，
    // 用它搜 TMDB 命中率更高。单年份场景不受影响（只有一个匹配）。
    private fun parseYear(input: String): Int? {
        val matches = YEAR.findAll(input).toList()
        for (m in matches.asReversed()) {
            val y = m.value.toInt()
            if (y in 1900..2099) return y
        }
        return null
    }

    // ---- 分片序号 ----
    private fun parsePart(input: String): Int? {
        // 现有：CD1/Disc1/Part1/PT1
        PART.find(input)?.let { return it.groupValues[1].toInt() }
        // P1.4：CDa/PartB（字母 a-d → 1-4）
        PART_LETTER.find(input)?.let {
            val c = it.groupValues[1].lowercase()[0]
            return c - 'a' + 1
        }
        // P1.4：1of2 / 1 of 2（取被除数）
        PART_OF.find(input)?.let { return it.groupValues[1].toInt() }
        // P1.4：-a 后缀（仅末尾连字符+单字母）
        PART_SUFFIX.find(input)?.let {
            val c = it.groupValues[1].lowercase()[0]
            return c - 'a' + 1
        }
        // P3.0：Part II / Pt IV（罗马数字）
        PART_ROMAN.find(input)?.let {
            val roman = it.groupValues[1].uppercase()
            val n = romanToInt(roman)
            if (n != null && n in 1..100) return n
        }
        return null
    }

    // ---- 标题清洗（P2.6 链式迭代）----
    /**
     * P2.6：cleanTitle 拆成多个独立 cleanStep，按顺序迭代应用直到结果稳定（每轮输出作下轮输入）。
     * 与 P0.1 配套：词表替换是其中一步（[stripTechByStopwords]）。
     */
    private fun cleanTitle(spaced: String, stripAbsoluteEp: Boolean, knownGroup: String?, releaseYear: Int?): String? {
        var current = spaced
        val steps: List<(String, Boolean) -> String> = listOf(
            ::stripEpisodeTitleSep,
            ::stripSeasonEpisode,
            ::stripAbsoluteEpIfEnabled,
            ::stripVersionTag,
            ::stripEditionTags,
            ::stripPartTags,
            { s, _ -> stripKnownGroupSuffix(s, knownGroup) },
            ::stripTechByStopwords,
            { s, _ -> stripYearTokens(s, releaseYear) },
            ::normalizeWhitespace,
        )
        // 迭代至稳定（最多 3 轮，避免无限循环）
        repeat(3) {
            val prev = current
            for (step in steps) {
                current = step(current, stripAbsoluteEp)
            }
            if (current == prev) return@repeat
        }
        return current.ifBlank { null }
    }

    private fun stripEpisodeTitleSep(s: String, ignored: Boolean): String {
        val m = EPISODE_TITLE_SEP.find(s) ?: return s
        return s.substring(0, m.range.first)
    }

    private fun stripSeasonEpisode(s: String, ignored: Boolean): String {
        // 截断到首个季集/日期模式之前：SxxExx 后的内容（剧集标题词 + 技术尾巴）
        // 不应进入标题候选。如 `Game of Thrones S01E01 Winter Is Coming 1080p...` → `Game of Thrones`，
        // `Stranger Things S01 COMPLETE 1080p...` → `Stranger Things`，`One Piece Episode 1000 1080p...` → `One Piece`。
        // S/E 提取在 [parseSeasonEpisode]（用原 spaced）中完成，不受本截断影响。
        val patterns = listOf(
            SEASON_EPISODE, SEASON_RANGE, NX_N, SEASON_ONLY,
            CHINESE_SEASON_EP, CHINESE_NUM_SEASON_EP, CHINESE_EP_ONLY,
            CHINESE_NUM_EP, CHINESE_NUM_SEASON, STANDALONE_EP, EPISODE_WORD, DAILY_SHOW,
        )
        val firstCut = patterns.mapNotNull { it.find(s)?.range?.first }.minOrNull()
        return if (firstCut != null && firstCut >= 0) s.substring(0, firstCut) else s
    }

    private fun stripAbsoluteEpIfEnabled(s: String, stripAbsoluteEp: Boolean): String =
        if (stripAbsoluteEp) s.replace(ABSOLUTE_EP, " ") else s

    private fun stripVersionTag(s: String, ignored: Boolean): String = s.replace(VERSION_TAG, " ")

    /** P1.1：剥离已识别的 edition 标签（避免污染标题相似度）。 */
    private fun stripEditionTags(s: String, ignored: Boolean): String {
        var r = s
        Edition.entries.forEach { ed -> r = r.replace(ed.pattern, " ") }
        return r
    }

    private fun stripPartTags(s: String, ignored: Boolean): String =
        s.replace(PART, " ").replace(PART_LETTER, " ").replace(PART_OF, " ").replace(PART_SUFFIX, " ")

    /**
     * 仅剥离已知组名后缀（如 `-GROUP`），避免误伤 `X-Men` 这类连字符标题。
     * knownGroup 来自 [parseTech] 的 GROUP_SUFFIX 提取。
     */
    private fun stripKnownGroupSuffix(s: String, knownGroup: String?): String {
        if (knownGroup.isNullOrBlank()) return s
        val pattern = Regex("(?i)[\\-\\.]" + Regex.escape(knownGroup) + "$")
        return pattern.replace(s, " ")
    }

    /**
     * P0.1：用 [ReleaseInfoDictionary] 词表分词判定标题边界。
     * 从尾部向前找第一个非停用词 token，标题 = `[0, firstStopIndex)`。
     * 软停用词仅当其后还有更多内容时才剥离。
     *
     * 额外处理两类归一化后产生的技术片段（`H.265`→`H 265`、`DTS5.1`→`DTS5 1`）：
     * - 纯数字 token（如 `265`、`1`）：视为技术参数片段剥离，但仅当保留区仍有非数字 token
     *   时生效，避免误剥「1917」「2046」这类纯数字标题。
     * - 字母+数字 token（如 `DTS5`、`DDP5`、`x265`）：匹配 `[a-z]{2,}\d+` 视为编解码器片段剥离。
     * - 单个 ASCII 字母 token（如 `H.265` 拆出的 `H`）：仅当其后已剥过技术 token 且前面还有
     *   至少一个 token 时视为片段，避免误剥 `H.2002.mkv` 这类单字母标题。
     */
    private fun stripTechByStopwords(s: String, ignored: Boolean): String {
        // 用空白/点/下划线作为分隔符切分（保留中文连续字符段）。
        // 不把 `-` 当分隔符：连字符常出现在标题中（Spider-Man / X-Men / Anti-Life），
        // 若拆分会把 `Spider-Man` 误拆为 [Spider, Man] 再 join 成 `Spider Man` 丢失连字符。
        // 组名（`-GROUP`）已由 [stripKnownGroupSuffix] 在本步之前剥离，不影响。
        // 连字符复合技术词（如 `DTS-HD`）由 [isTechStopword] 整体识别剥离。
        val tokens = s.split(Regex("(?<=\\S)[\\s._](?=\\S)"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return s
        // 从尾部向前找第一个非停用词 token
        var firstStopFromTail = tokens.size
        for (i in tokens.indices.reversed()) {
            val tok = tokens[i].trim()
            if (tok.isEmpty()) continue
            if (isTechStopword(tok)) {
                firstStopFromTail = i
                continue
            }
            // 软停用词：仅当 token 后还有更多内容时才剥离
            if (ReleaseInfoDictionary.isSoftStopword(tok) && i < tokens.size - 1) {
                firstStopFromTail = i
                continue
            }
            // 编解码器片段：字母(≥2)+数字（如 DTS5、DDP5、x265、H265 已在词表但此处兜底）。
            // 仅当 i > 0 时剥离，避免误剥 `Area51` 这类字母+数字构成的完整标题。
            if (i > 0 && CODEC_DIGIT.matches(tok)) {
                firstStopFromTail = i
                continue
            }
            // 纯数字 token（如 DTS5.1 拆出的 1、H.265 拆出的 265）：
            // 仅当位数 ≤3 且 [0, i) 范围内存在技术指示词（停用词/编解码器片段/单字母）时才剥离。
            // 避免误剥标题尾部序号（如 `Toy Story 4` 的 4、`Spider-Man 2` 的 2）。
            // 4 位数字（2019/2049/2012）交给 [stripYearTokens] / 视为标题一部分（Blade Runner 2049）。
            if (tok.all { it.isDigit() } && tok.length <= 3 && i > 0 &&
                (0 until i).any { j -> isTechIndicator(tokens[j].trim()) }
            ) {
                firstStopFromTail = i
                continue
            }
            // 单个 ASCII 字母 token（如 H.265 拆出的 H）：
            // 仅当其后已剥过技术 token 且前面还有 token 时视为片段，避免误剥单字母标题 `H`。
            if (tok.length == 1 && tok[0].isAsciiLetter() &&
                firstStopFromTail < tokens.size && i >= 1
            ) {
                firstStopFromTail = i
                continue
            }
            // 遇到第一个非停用词 token，停止
            break
        }
        // 清空保护：仅当标题只剩单个 token 且该 token 命中停用词（如 `It` / `Ma` 这类单字标题
        // 恰好命中语言/流媒体代码）时保留原文，避免把 `It.2017...` 的标题在迭代中剥成 null。
        // 多 token 全技术串（如 `1080p BluRay x264`）不保护——剥离为空表示无真实标题。
        if (firstStopFromTail == 0 && tokens.size == 1) return s
        if (firstStopFromTail == tokens.size) return s
        return tokens.subList(0, firstStopFromTail).joinToString(" ")
    }

    /**
     * 判断 token 是否为硬停用词或连字符复合停用词。
     * 连字符复合：如 `DTS-HD`，按 `-` 拆分后各分量均为硬停用词（dts、hd）则整体视为停用词。
     * 避免误伤 `Spider-Man`（spider/man 均非停用词）、`X-Men`（x/men 均非停用词）。
     */
    private fun isTechStopword(tok: String): Boolean {
        if (ReleaseInfoDictionary.isHardStopword(tok)) return true
        if (tok.contains('-')) {
            val parts = tok.split('-')
            if (parts.size >= 2 && parts.all { it.isNotBlank() && ReleaseInfoDictionary.isHardStopword(it) }) {
                return true
            }
        }
        return false
    }

    /** 判断 token 是否为技术指示词（用于纯数字剥离的上下文判定）。 */
    private fun isTechIndicator(tok: String): Boolean =
        isTechStopword(tok) || CODEC_DIGIT.matches(tok) || (tok.length == 1 && tok[0].isAsciiLetter())

    /**
     * 剥离标题中的年份 token，规则：
     * - releaseYear（发行年）一律剥离。
     * - 首个 token 为年份时保留（标题本身是年份，如 `2012` / `1917`）。
     * - 超出 releaseYear+10 的未来年份保留（标题里的年份式数字，如 `Blade Runner 2049`）。
     * - 其余 1900..2099 范围内的年份 token（原作年/片中年）剥离，如 `Cold.War.1994.2026` → `Cold War`。
     *
     * 用 `replace(YEAR, " ")` 会误剥标题里的年份式数字；只移除 releaseYear 又会留下原作年
     * （如 `Cold War 1994`）。改为按 token 位置 + 未来阈值综合判定。
     */
    private fun stripYearTokens(s: String, releaseYear: Int?): String {
        val tokens = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return s
        val futureMargin = 10
        val result = tokens.mapIndexed { i, raw ->
            val tok = raw.trim()
            val y = tok.toIntOrNull()
            if (y != null && y in 1900..2099) {
                when {
                    y == releaseYear -> " "
                    i == 0 -> tok
                    releaseYear != null && y > releaseYear + futureMargin -> tok
                    else -> " "
                }
            } else tok
        }
        return result.joinToString(" ")
    }

    private fun normalizeWhitespace(s: String, ignored: Boolean): String =
        s.replace(Regex("\\s+"), " ").trim()

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isCjk(): Boolean = Character.UnicodeScript.of(this.code) == Character.UnicodeScript.HAN

    // ---- 版本标签 ----
    private fun parseVersion(input: String): String? = VERSION_TAG.find(input)?.value

    // ---- P1.1 Edition ----
    private fun parseEdition(input: String): String? = Edition.find(input)?.displayName

    // ---- P1.2 HDR / 3D ----
    private fun parseHdr(input: String): String? {
        HDR10_PLUS.find(input)?.let { return "HDR10+" }
        HDR10.find(input)?.let { return "HDR10" }
        DOLBY_VISION.find(input)?.let { return "Dolby Vision" }
        HLG.find(input)?.let { return "HLG" }
        return null
    }

    private fun parse3D(input: String): String? {
        // 3D 格式按 Jellyfin Format3DRules 双重匹配：precedingToken + token
        THREE_D_SBS.find(input)?.let { return "3D SBS" }
        THREE_D_TAB.find(input)?.let { return "3D TAB" }
        THREE_D_MVC.find(input)?.let { return "3D MVC" }
        if (THREE_D_ONLY.containsMatchIn(input)) return "3D"
        return null
    }

    // ---- P1.5 IMDb ID ----
    private fun parseImdbId(input: String): String? =
        IMDB_ID.find(input)?.value?.lowercase()

    // ---- P3.0 Provider ID 提取 ----
    /**
     * 从文件名（含扩展名）提取 TMDB/TVDB/TVMaze/IMDb ID。
     * 优先级：方括号属性 > URL 形态 > 裸 IMDb。
     * 任一 ID 命中即返回，未命中字段为 null。
     */
    private fun parseProviderIds(raw: String): ProviderIds {
        var tmdbId: Int? = null
        var tvdbId: Int? = null
        var tvmazeId: Int? = null
        var imdbId: String? = null

        // TMDB：方括号属性优先，其次 URL
        TMDB_ID.find(raw)?.let { m ->
            val grp1 = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            val grp2 = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            tmdbId = (grp1 ?: grp2)?.toIntOrNull()
        }
        // TVDB：方括号属性优先，其次 URL
        TVDB_ID.find(raw)?.let { m ->
            val grp1 = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            val grp2 = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            tvdbId = (grp1 ?: grp2)?.toIntOrNull()
        }
        // TVMaze：仅方括号属性
        TVMAZE_ID.find(raw)?.let { m ->
            tvmazeId = m.groupValues[1].toIntOrNull()
        }
        // IMDb：URL 优先，其次裸 tt 形态
        IMDB_URL.find(raw)?.let { m ->
            val ttForm = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            val numForm = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            imdbId = when {
                ttForm != null -> ttForm.lowercase()
                numForm != null -> "tt$numForm"
                else -> null
            }
        }
        if (imdbId == null) {
            imdbId = IMDB_ID.find(raw)?.value?.lowercase()
        }
        return ProviderIds(tmdbId, tvdbId, tvmazeId, imdbId)
    }

    private data class ProviderIds(
        val tmdbId: Int?,
        val tvdbId: Int?,
        val tvmazeId: Int?,
        val imdbId: String?,
    )

    // ---- P2.5 Extras ----
    private fun parseExtraType(input: String): ExtraType? = ExtraType.find(input)

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

    /**
     * P3.0：罗马数字转 Int（支持 I/V/X/L/C/D/M，最大 3999）。
     * 解析算法：从左到右扫描，当前字符值 < 下一字符值时减去，否则加上。
     * 无法识别返回 null。
     */
    private fun romanToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val values = mapOf(
            'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50,
            'C' to 100, 'D' to 500, 'M' to 1000,
        )
        if (s.any { it !in values }) return null
        var total = 0
        var prev = 0
        for (c in s) {
            val v = values[c]!!
            if (prev != 0 && prev < v) {
                // 减法规则修正：之前加上了 prev，现在应改为减去 prev 并加上 v
                total = total - prev + (v - prev)
            } else {
                total += v
            }
            prev = v
        }
        return total
    }

    companion object {
        // P0.2/P0.4：季集号上限
        private const val MAX_EPISODE_RANGE = 5
        private const val MAX_SEASON = 50
        private const val MAX_EPISODE = 50
        private const val MAX_ABSOLUTE_EPISODE = 1000

        // 中文数字字符集（含大写）：零一二三四五六七八九十百 + 壹贰叁肆伍陆柒捌玖拾佰 + 〇 + 两
        private const val CN_NUM = "一二三四五六七八九十百零壹贰叁肆伍陆柒捌玖拾佰〇两"

        // S01E01E02 / S01E01-E03 / s1e2 — group2 捕获集号串（支持 E 与 - 分隔，含混合 01-E03）
        private val SEASON_EPISODE = Regex("(?i)S(\\d{1,2})E(\\d{1,4}(?:[-]?E?\\d{1,3})*)")
        // P3.0 英文字面词季集模式：Season 1 Episode 2 / Staffel 2 Episode 5（含多语言季关键词）
        private val SEASON_WORD_EP = Regex(
            "(?i)(?<![a-z])(?:season|staffel|saison|temporada|series|stagione|сезон|시즌|الموسم)\\s+(\\d{1,2})\\s+episode\\s+(\\d{1,3})(?:-(\\d{1,3}))?"
        )
        // P3.0 仅季字面词：Staffel 3 / Saison 3（不接 Episode）
        private val SEASON_WORD_ONLY = Regex(
            "(?i)(?<![a-z])(?:season|staffel|saison|temporada|series|stagione|сезон|시즌|الموسم)\\s+(\\d{1,2})(?!\\s*episode)"
        )
        // P3.0 Episode 独立词模式：Episode 16 / Episode 16-20 / Episode 1000（集号放宽至 4 位以支持绝对集号）
        private val EPISODE_WORD = Regex(
            "(?i)(?<![a-z])episode\\s+(\\d{1,4})(?:-(\\d{1,3}))?"
        )
        // P3.0 Anime Special/OVA 前缀识别（→ season=0）
        private val ANIME_SPECIAL_TOKEN = Regex(
            "(?i)(?:^|[\\s._-])(special|ova|oav|picture[\\s._]drama|sp\\d+)(?:$|[\\s._-])"
        )
        private val ANIME_SPECIAL_BRACKET = Regex(
            "(?i)\\[(special|ova|oav|sp\\d+)]"
        )
        // P3.0 集号字母后缀 a-i（S01E02a / 1x02b 后的单字母）
        private val EPISODE_LETTER_SUFFIX = Regex("(?i)([a-i])$")
        private val EPISODE_NUM = Regex("\\d{1,4}")
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
        // 仅季号 S01 / S1（无集号；scene 季打包 `S01.COMPLETE` 常见）。前导非字母避免误伤 `Series`，
        // 后跟非字母数字避免 `S1m0ne` / `S4ve` 这类标题误判。用于 stripSeasonEpisode 标题截断与季号提取。
        private val SEASON_ONLY = Regex("(?i)(?<![A-Za-z])S(\\d{1,2})(?![\\dA-Za-z])")
        // 绝对集号：独立 1-3 位数字（可选区间 -XX），需前后为边界分隔符；命中后由 tryAbsoluteEpisode 二次校验
        private val ABSOLUTE_EP = Regex("(?:^|\\s|[-_])(\\d{1,3})(?:[-](\\d{1,3}))?(?:$|\\s|[-_])")
        // 版本标签：v2/v3/Repack/Proper/Final/Rerelease（须前置分隔符，避免误匹配 "Final Destination" 这类标题开头）
        private val VERSION_TAG = Regex("(?i)(?<=\\s|[-_.])(v\\d{1,2}|repack|proper|final|rerelease)(?=\\s|[-_.]|$)")
        // 已知技术词数字（分辨率高度等），用于绝对集号二次过滤
        private val TECH_NUMBERS = setOf(720, 480, 540, 360, 240, 1080, 2160, 4320)
        // 日期型剧集 2024.01.15 / 2024-01-15 / 2024 01 15（分隔符含空格，以便在归一化后的 spaced 上
        // 也能命中用于 [stripSeasonEpisode] 截断标题；年份解析在原文上 [replace] 仍正常工作）。
        private val DAILY_SHOW = Regex("(?<!\\d)(19|20)\\d{2}[\\s._-](0?[1-9]|1[0-2])[\\s._-]([0-2]?[0-9]|3[01])(?!\\d)")
        // P3.0 日优先日期格式：15.01.2024 / 15-01-2024（欧洲日期）
        private val DAILY_SHOW_EU = Regex("(?<!\\d)(0?[1-9]|[12]\\d|3[01])[._-](0?[1-9]|1[0-2])[._-]((?:19|20)\\d{2})(?!\\d)")
        // 年份：前后不能是数字；前面也不能是汉字（否则视为标题的一部分，如 `寒战1994`）。
        // 这样 `寒战1994` 中的 1994 不被识别为独立年份，而 `Cold.War.1994` 中点分隔的 1994 仍被识别。
        private val YEAR = Regex("(?<!\\d)(?<!\\p{script=Han})(19\\d{2}|20\\d{2})(?!\\d)")
        private val YEAR_IN_PARENS = Regex("(?i)(19\\d{2}|20\\d{2})")
        // B9: 不能用 \b——下划线是 \w 字符，\b 不会在 _ 与字母/数字间匹配，
        // 导致下划线分隔文件名（The_Last_of_Us_S01E02_1080p_WEB-DL_x264）的 _1080p/_WEB-DL/_x264
        // 均无法识别。改用自定义边界 (?<![A-Za-z0-9]) / (?![A-Za-z0-9])，下划线视为合法分隔符。
        private val RESOLUTION = Regex("(?i)(?<![A-Za-z0-9])(2160p|1080p|720p|540p|480p|360p|4320p|4K|8K)(?![A-Za-z0-9])")
        // P3.0 WxH 分辨率：1920x1080 / 1280x720（与高度+p 并存，高度+p 优先）
        private val RESOLUTION_WXH = Regex("(?i)(?<![A-Za-z0-9])(\\d{3,4})[xX](\\d{3,4})(?![A-Za-z0-9])")
        private val SOURCE_TOKEN = Regex("(?i)(?<![A-Za-z0-9])(Blu-?Ray|BDRip|BD25|BD50|BRRip|WEB-?DL|WEBDL|WEBRip|WEB|HDTV|DVDRip|DVD|R5|CAM|REMUX|HD-?TS|HD-?TC|PDVD)(?![A-Za-z0-9])")
        private val VIDEO_CODEC = Regex("(?i)(?<![A-Za-z0-9])(x264|x265|h264|h265|hevc|av1|vp9|divx|xvid|mpeg-?2|mpeg-?4|vc1)(?![A-Za-z0-9])")
        private val AUDIO_CODEC = Regex("(?i)(?<![A-Za-z0-9])(AAC|AC3|EAC3|DDP|DDPA|DD|DTS|DTS-?HD|DTS-?MA|TrueHD|Atmos|FLAC|MP3|PCM|Opus)(?![A-Za-z0-9])")
        private val GROUP_TOKEN = Regex("^[A-Za-z0-9]{2,}$")
        // 组名后缀：末尾 `-GROUP`，可选跟随 `.语言代码`（字幕文件 `Movie-GROUP.en.srt`）。
        // 不把 `.` 作为组名前导分隔符，避免字幕文件末尾 `.en` 被误当作组名；
        // 语言代码交给 [ReleaseInfoDictionary.HARD_STOPWORDS] 在标题清洗时剥离。
        private val GROUP_SUFFIX = Regex("(?i)[\\-]([A-Za-z0-9]{2,})(?:\\.[A-Za-z]{2,3})?$")
        private val PART = Regex("(?i)(?:^|\\s)(?:CD|DISC|PART|PT)\\s?(\\d{1,2})(?:$|\\s)")
        // P1.4：Stacking 扩展
        private val PART_LETTER = Regex("(?i)(?:^|\\s)(?:CD|DISC|PART|PT)\\s?([a-d])(?:$|\\s)")
        private val PART_OF = Regex("(?i)(?:^|\\s)(\\d{1,2})\\s?(?:of|⁄|∕|/)\\s?\\d{1,2}(?:$|\\s)")
        private val PART_SUFFIX = Regex("[\\-\\._]([a-d])$")
        // P3.0 罗马数字 Part：Part II / Pt IV（仿 chineseToInt 实现）
        private val PART_ROMAN = Regex("(?i)(?:^|\\s)(?:pt|part)\\s?([ivx]+)(?:$|\\s)")
        // 集名/副标题分隔符：` - `（前后带空格的横线），如 `剧名 S01E01 - 集名` 中的 ` - `。
        private val EPISODE_TITLE_SEP = Regex("\\s-\\s")
        // 编解码器片段：2+ 字母后接数字（如 DTS5、DDP5、x265），用于标题尾部技术词剥离兜底。
        private val CODEC_DIGIT = Regex("(?i)^[a-z]{2,}\\d+$")

        // P1.2：HDR / 3D 正则
        private val HDR10_PLUS = Regex("(?i)(?<![A-Za-z0-9])(HDR10\\+|HDR10Plus)(?![A-Za-z0-9])")
        private val HDR10 = Regex("(?i)(?<![A-Za-z0-9])HDR10(?!\\+|Plus|p|P)(?![A-Za-z0-9])")
        private val DOLBY_VISION = Regex("(?i)(?<![A-Za-z0-9])(DV|Dolby\\.?Vision)(?![A-Za-z0-9])")
        private val HLG = Regex("(?i)(?<![A-Za-z0-9])HLG(?![A-Za-z0-9])")
        // 3D 双重匹配：precedingToken + token（Jellyfin Format3DRules）
        private val THREE_D_SBS = Regex("(?i)(?<![A-Za-z0-9])(?:3D[\\s._-])?SBS(?![A-Za-z0-9])")
        private val THREE_D_TAB = Regex("(?i)(?<![A-Za-z0-9])(?:3D[\\s._-])?TAB(?![A-Za-z0-9])")
        private val THREE_D_MVC = Regex("(?i)(?<![A-Za-z0-9])MVC(?![A-Za-z0-9])")
        private val THREE_D_ONLY = Regex("(?i)(?<![A-Za-z0-9])3D(?![A-Za-z0-9])")

        // P1.5/P3.0：IMDb ID（裸 tt 形态，6 位以上）
        private val IMDB_ID = Regex("(?<![A-Za-z0-9])tt(\\d{6,})(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        // P3.0 IMDb URL 形态：imdb.com/title/tt0123456 或 imdb.com/Title?0123456
        // 文件名中 / 会被替换为 - 或 .，故分隔符允许多种
        private val IMDB_URL = Regex("(?i)imdb\\.com[-/.](?:title[-/.](tt\\d{6,})|Title\\?(\\d{6,}))")
        // P3.0 TMDB ID：[tmdbid-123] 方括号属性 或 themoviedb.org/movie/123 URL
        private val TMDB_ID = Regex("(?i)\\[tmdbid-?(\\d+)]|themoviedb\\.org[-/.](?:movie|tv)[-/.](\\d+)")
        // P3.0 TVDB ID：[tvdbid-123] 方括号属性 或 thetvdb.com URL 含 id= 参数
        private val TVDB_ID = Regex("(?i)\\[tvdbid-?(\\d+)]|thetvdb\\.com[^\\s]*?id=(\\d+)")
        // P3.0 TVMaze ID：[tvmazeid-123] 方括号属性
        private val TVMAZE_ID = Regex("(?i)\\[tvmazeid-?(\\d+)]")

        // P1.6：流媒体来源（边界保护避免 MA 与 multi-audio 混淆）
        private val STREAMING_SOURCE = Regex("(?i)(?<![A-Za-z0-9])(AMZN|NF|ATVP|HMAX|STZ|PCOK|MA|NBC|CR|DSNP|HULU)(?![A-Za-z0-9])")
        private val STREAMING_MAP = mapOf(
            "amzn" to "Amazon",
            "nf" to "Netflix",
            "atvp" to "Apple TV+",
            "hmax" to "HBO Max",
            "stz" to "Starz",
            "pcok" to "Peacock",
            "ma" to "Movies Anywhere",
            "nbc" to "NBC",
            "cr" to "Crunchyroll",
            "dsnp" to "Disney+",
            "hulu" to "Hulu",
        )

        // P1.3：Anime 方括号命名（至少 3 个连续方括号）
        private val ANIME_BRACKETS = Regex("\\[[^\\]]+\\]")
    }
}
