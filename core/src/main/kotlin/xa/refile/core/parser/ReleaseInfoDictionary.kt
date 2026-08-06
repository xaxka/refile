package xa.refile.core.parser

/**
 * 发布信息词典（计划 §5.3 P0.1）。
 *
 * 集中维护标题清洗用的停用词表，替代散落的启发式正则。
 *
 * - [HARD_STOPWORDS]：硬停用词，命中即剥离（分辨率/编码/来源/组名/中文发布词等）
 * - [SOFT_STOPWORDS]：软停用词，仅当 token 后还有更多内容时才剥离（complete/custom/dc/extended/...）
 */
object ReleaseInfoDictionary {

    /**
     * 硬停用词：命中即视为发布信息，从标题 token 流中剥离。
     * 来源：常见发布信息 token + 中文场景必要 token（国配/简繁/内嵌/双语/...）。
     * 全小写存储，匹配时也用小写比较。
     */
    val HARD_STOPWORDS: Set<String> = setOf(
        // Resolution
        "480i", "480p", "576i", "576p", "720", "720i", "720p", "1080", "1080i", "1080p",
        "2160i", "2160p", "4k", "8k", "uhd", "ultrahd", "hd", "sd",
        // Video codecs
        "x264", "x265", "h264", "h265", "hevc", "av1", "avc", "divx", "divx5",
        "xvid", "xvidvd", "mpeg2", "mpeg4", "vc1", "vp9",
        // Audio codecs
        "aac", "ac3", "eac3", "ddp", "ddpa", "dd", "dts", "dtshd", "dtsma",
        "truehd", "atmos", "flac", "mp3", "pcm", "opus",
        // Sources (including sub-tokens split by '-')
        "bluray", "blueray", "blu-ray", "blu", "ray",
        "bdrip", "bd25", "bd50", "brrip", "bd",
        "web-dl", "webdl", "webrip", "web", "dl", "rip",
        "hdtv", "dvdrip", "dvd", "r5", "cam", "remux", "hdts", "hdtc", "pdvd",
        // HDR / 3D
        "hdr10", "hdr10+", "hdr", "dv", "dolbyvision", "dolby-vision", "hlg",
        "sbs", "tab", "hsbs", "htab", "mvc", "3d",
        // Bit depth（位深，常见于 `x265 10bit` / `8bit` 尾巴）
        "10bit", "12bit", "8bit", "6bit",
        // HBO（HBO 标题清洗用；流媒体来源字段识别见 STREAMING_SOURCE 正则）
        "hbo",
        // Edition (cleanTitle 也剥离，避免污染标题边界判定)
        "directorscut", "director'scut", "directors", "extended", "uncut", "unrated",
        "remastered", "imax", "special", "collector", "collectors", "limited",
        "theatrical", "final", "alternate", "criterion",
        // Release group / scene markers
        "repack", "proper", "rerelease", "rerip",
        // Streaming sources (P1.6)
        "amzn", "nf", "atvp", "hmax", "stz", "pcok", "ma", "nbc", "cr", "dsnp", "hulu",
        // Chinese-specific tokens
        "国配", "简体", "繁体", "简繁", "内嵌", "内封", "双语", "中字", "中英双字", "国英双语",
        "国漫", "日漫", "美漫", "国产", "熟肉", "生肉",
        "简繁内封", "简体内封", "繁体内封", "简繁内嵌", "简体内嵌", "繁体内嵌",
        "原盘", "蓝光", "首发", "修正", "国语", "粤语", "台配", "港版", "美版",
        "中法双语", "中日双语", "简体内嵌", "繁体内嵌", "简体外挂", "繁体外挂",
        // Subtitle language tags (P1.7)
        "chinese", "english", "japanese", "korean", "french", "german", "spanish",
        "italian", "portuguese", "russian", "arabic", "hindi", "thai", "vietnamese",
        "forced", "default", "sdh", "cc",
        // ISO 语言代码（字幕文件名 `Movie-GROUP.en.srt` 尾部 `.en` / `.chs` 等；不含 us/uk 等国家代码，
        // 以免误剥 `Shameless US` 这类标题里的国家标识）。
        // 注：2 字母代码（en/it/ma...）可能与单字标题冲突（如《It》《Ma》），由 [stripTechByStopword]
        // 的「清空保护」兜底——当剥离会导致标题清空时保留首个 token，避免误删完整标题。
        "en", "zh", "ja", "ko", "fr", "de", "es", "pt", "ru", "it", "cn", "tw", "hk",
        "chs", "cht", "eng", "jpn", "kor", "chi", "fre", "ger", "spa", "por", "rus", "ita",
        // Common release tags
        "internal", "readnfo", "nfo", "sample", "proof",
        "hq", "yk", "youku",
    )

    /**
     * 软停用词：仅当 token 后还有更多内容时才剥离。
     * 例如 `Movie Extended Cut` 中 `Extended` 后还有 `Cut`，剥离 `Extended`；
     * 但 `Movie Extended` 中 `Extended` 是最后一个 token，可能是标题的一部分（如《Extended》专辑），保留。
     */
    val SOFT_STOPWORDS: Set<String> = setOf(
        "complete", "custom", "dc", "extended", "limited", "se",
        "remastered", "uncut", "unrated", "proper", "repack",
    )

    /** 判断 token 是否为硬停用词（大小写不敏感）。 */
    fun isHardStopword(token: String): Boolean = HARD_STOPWORDS.contains(token.lowercase())

    /** 判断 token 是否为软停用词（大小写不敏感）。 */
    fun isSoftStopword(token: String): Boolean = SOFT_STOPWORDS.contains(token.lowercase())
}
