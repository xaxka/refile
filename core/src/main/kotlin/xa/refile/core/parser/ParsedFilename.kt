package xa.refile.core.parser

import xa.refile.core.model.MediaType

/**
 * 文件名解析结果（release info 检测，见计划 §5.3）。
 *
 * 字段全部来源于文件名解析，不读取文件二进制内容（红线：不做 MediaInfo）。
 *
 * @property title      清洗后的标题，如 "The Last of Us"
 * @property year       1900-2099 之间的四位年份
 * @property season     季号（剧集）
 * @property episodes   集号列表（支持多集 S01E01E02）
 * @property resolution 480p/720p/1080p/2160p/4K
 * @property source     BluRay/WEB-DL/WEBRip/HDTV/DVDRip/Remux...
 * @property videoCodec x264/x265/h264/h265/HEVC/AV1...
 * @property audioCodec AAC/AC3/DTS/Atmos/TrueHD...
 * @property group      发布组（文件名末尾 -GROUP 或 [GROUP]）
 * @property partIndex  分片序号（CD1/Part1/Disc1 等多分片文件）
 * @property isDailyShow 日期型剧集标记（2024.01.15）
 * @property mediaType  推断的媒体类型
 * @property isAbsoluteEpisode 绝对集号识别命中（如 `Show 12`、`[12] Show`、`Show-12`）；
 *                              无季上下文，需后续按 TMDB episode group / 季集数累加定位 (season, episode)。
 * @property version   版本标签（v2/v3/Repack/Proper/Final/Rerelease），可选，不影响主流程。
 */
data class ParsedFilename(
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episodes: List<Int> = emptyList(),
    val resolution: String? = null,
    val source: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val group: String? = null,
    val partIndex: Int? = null,
    val isDailyShow: Boolean = false,
    val mediaType: MediaType = MediaType.MOVIE,
    val isAbsoluteEpisode: Boolean = false,
    val version: String? = null,
) {
    /** 是否多集文件。 */
    val isMultiEpisode: Boolean get() = episodes.size > 1

    /** 是否剧集（有季或集信息）。 */
    val isEpisode: Boolean
        get() = season != null || episodes.isNotEmpty() || mediaType == MediaType.EPISODE
}
