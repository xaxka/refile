package xa.refile.core.parser

import xa.refile.core.model.MediaType

/**
 * 文件名解析结果（release info 检测，见计划 §5.3）。
 *
 * 字段全部来源于文件名解析，不读取文件二进制内容（红线：不做 MediaInfo）。
 *
 * P0/P1/P2 字段增量：
 * - P1.1 [edition]：Director's Cut / IMAX / Extended ...
 * - P1.2 [hdr] / [threeD]：HDR10+ / Dolby Vision / 3D SBS ...
 * - P1.5 [imdbId]：`tt\\d{7,8}`
 * - P1.6 [streamingSource]：AMZN/NF/ATVP/... 映射后的服务商名
 * - P1.7 [subtitleInfo]：字幕文件语言 + 修饰符
 * - P2.5 [extraType]：Trailer/Sample/...
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
    // P1.1 Edition（Director's Cut / IMAX / Extended ...）
    val edition: String? = null,
    // P1.2 HDR / 3D
    val hdr: String? = null,
    val threeD: String? = null,
    // P1.5 IMDb ID
    val imdbId: String? = null,
    // P1.6 流媒体来源（映射后服务商名：Amazon/Netflix/...）
    val streamingSource: String? = null,
    // P1.7 字幕文件信息
    val subtitleInfo: SubtitleInfo? = null,
    // P2.5 附加内容类型
    val extraType: ExtraType? = null,
) {
    /** 是否多集文件。 */
    val isMultiEpisode: Boolean get() = episodes.size > 1

    /** 是否剧集（有季或集信息）。 */
    val isEpisode: Boolean
        get() = season != null || episodes.isNotEmpty() || mediaType == MediaType.EPISODE
}
