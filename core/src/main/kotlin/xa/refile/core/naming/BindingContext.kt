package xa.refile.core.naming

import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename

/**
 * TMDB 元数据绑定上下文（计划 §5.5 变量表 A/C/G 组数据来源：TMDB）。
 *
 * 仅承载 TMDB 接口字段；不读取文件二进制内容（红线：不做 MediaInfo）。
 * 字段可空——缺失即容错渲染为空。
 */
data class MediaMetadata(
    val type: MediaType = MediaType.MOVIE,
    val id: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val name: String? = null,                 // 电影 title / 剧集 name
    val originalName: String? = null,         // original_title / original_name
    val aliases: List<String> = emptyList(),  // alternative_titles
    val year: Int? = null,                     // release_date / first_air_date 年份
    val releaseDate: String? = null,           // ISO 日期
    val firstAirDate: String? = null,
    val collectionName: String? = null,        // belongs_to_collection.name
    val collectionId: Int? = null,
    val collectionIndex: Int? = null,          // ci
    val collectionYears: List<Int> = emptyList(), // cy
    val genres: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val originCountries: List<String> = emptyList(),
    val productionCountries: List<String> = emptyList(),
    val runtime: Int? = null,                  // 分钟
    val certification: String? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val director: String? = null,             // 电影 credits director / 剧集 created_by
    val actors: List<String> = emptyList(),
    val numberOfSeasons: Int? = null,          // sc
    // 剧集相关
    val seasonNumber: Int? = null,
    val episodeNumbers: List<Int> = emptyList(),
    val episodeTitles: List<String> = emptyList(),
    val episodeAirDates: List<String> = emptyList(),
    val seasonName: String? = null,            // sn
    val seasonYears: List<Int> = emptyList(),  // sy
    val seasonAbsoluteStarts: List<Int> = emptyList(), // 各季常规集数累加（用于 absolute）
    val special: Int? = null,                  // Season 0 集号
    // 扩展元数据（G 组 info）
    val info: Map<String, String?> = emptyMap(),
    // 本地化标题（G 组 localize）：lang -> {var -> value}
    val localize: Map<String, Map<String, String>> = emptyMap(),
    // 动态剧集顺序（G 组 order）：groupName -> {e -> episodeNumber}
    val order: Map<String, Map<String, Int>> = emptyMap(),
) {
    val isEpisode: Boolean get() = type == MediaType.EPISODE
    val isMovie: Boolean get() = type == MediaType.MOVIE
    val isRegular: Boolean get() = (seasonNumber ?: 0) > 0
    val isAnime: Boolean
        get() = isEpisode && originCountries.any { it.equals("JP", true) } &&
            genres.any { it.equals("Animation", true) }

    /** 绝对集号：按各季常规集数累加（跳过 Season 0）。 */
    fun absoluteEpisode(): Int? {
        val s = seasonNumber ?: return null
        if (s <= 0) return null
        val e = episodeNumbers.firstOrNull() ?: return null
        val base = seasonAbsoluteStarts.getOrElse(s - 1) { 0 }
        return base + e
    }
}

/**
 * 远程文件属性上下文（计划 §5.5 变量表 E 组来源：WebDAV PROPFIND）。
 */
data class FileContext(
    val displayName: String? = null,   // fn
    val ext: String? = null,
    val fullPath: String? = null,      // f
    val folder: String? = null,
    val drive: String? = null,          // 库根
    val lastModified: String? = null,  // ct (ISO)
    val contentLength: Long? = null,   // bytes (raw)
    val parsed: ParsedFilename? = null,
)

/**
 * 批次与序号上下文（计划 §5.5 变量表 D 组来源：上下文计算）。
 */
data class BatchContext(
    val partIndex: Int? = null,       // pi
    val partCount: Int? = null,       // pc
    val duplicateIndex: Int? = null,  // di
    val duplicateCount: Int? = null,  // dc
    val filesCount: Int = 0,          // files
    val today: String = "",           // ISO yyyy-MM-dd
)

/**
 * 命名可视化选项（计划 §5.5 可视化选项）。
 */
data class NamingOptions(
    val wordSeparator: Char = ' ',                 // 空格 / . / _
    val casing: Casing = Casing.AS_IS,
    val illegalCharHandling: IllegalCharHandling = IllegalCharHandling.REPLACE_DASH,
    val padLength: Int = 2,                         // 2 或 3
) {
    enum class Casing { AS_IS, LOWER, UPPER, TITLE }
    enum class IllegalCharHandling { REPLACE_DASH, REPLACE_UNDERSCORE, REMOVE }
}
