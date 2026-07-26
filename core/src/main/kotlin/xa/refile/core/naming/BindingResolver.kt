package xa.refile.core.naming

import xa.refile.core.parser.ParsedFilename

/**
 * 变量绑定解析器（计划 §5.5 变量表 A–G 组）。
 *
 * 数据来源四类：TMDB（MediaMetadata）、文件名（ParsedFilename）、WebDAV（FileContext）、上下文（BatchContext）。
 * 排除清单中的变量（媒体流信息类/字幕/音乐/照片/外部数据源/CLI）解析为 null + 警告，不崩溃。
 */
class BindingResolver(
    val media: MediaMetadata,
    val file: FileContext,
    val batch: BatchContext,
    val options: NamingOptions = NamingOptions(),
) {
    /** 解析失败/排除绑定的警告日志（供 UI 展示）。 */
    val warnings: MutableList<String> = mutableListOf()

    private val excluded = setOf(
        // 媒体流信息类（需读取文件二进制，本项目不实现 MediaInfo）
        "vcf", "hpi", "vk", "aco", "acf", "af", "channels", "resolution", "width", "height",
        "bitdepth", "hdr", "dovi", "bitrate", "vbr", "abr", "fps", "khz", "ar", "ws", "hd",
        "dt", "duration", "seconds", "minutes", "hours", "crc32", "media", "video", "audio",
        "text", "chapters", "audioLanguages", "textLanguages", "mediaTitle",
        // 字幕类
        "lang", "subt",
        // 音乐类
        "music", "medium", "album", "artist", "albumArtist",
        // 照片类
        "image", "exif", "camera", "location",
        // 外部数据源（仅 TMDB）
        "omdb", "db", "AnimeList", "XEM",
        // CLI/桌面环境
        "home", "output", "defines", "label",
    )

    /** 解析单个变量名为值（支持 info.X / localize.<lang>.X / order.<NAME>.e / historic.f 等点路径）。 */
    @OptIn(kotlin.ExperimentalStdlibApi::class)
    fun resolve(path: String): Any? {
        val name = path.substringBefore('.')
        if (name in excluded) {
            warnings += "排除绑定 '$path' 渲染为空（需读取文件内容或超出范围）"
            return null
        }
        val value: Any? = when (name) {
            // A 组
            "n" -> media.name
            "y" -> media.year
            "ny" -> media.name?.let { if (media.year != null) "$it (${media.year})" else it }
            "id", "tmdbid" -> media.tmdbId ?: media.id
            "imdbid" -> media.imdbId
            "tvdbid" -> if (media.isEpisode) media.tvdbId else null
            "primaryTitle" -> media.originalName
            "alias" -> media.aliases.takeIf { it.isNotEmpty() }
            "object" -> defaultObjectString()
            "type" -> if (media.isEpisode) "Episode" else "Movie"
            "episode" -> episodeObjectString()
            "series" -> media.name?.let { if (media.year != null) "$it (${media.year})" else it }
            "movie" -> if (media.isMovie) media.name?.let { if (media.year != null) "$it (${media.year})" else it } else null
            // B 组
            "s" -> media.seasonNumber
            "e" -> media.episodeNumbers.firstOrNull()
            "es" -> media.episodeNumbers.takeIf { it.isNotEmpty() }
            "sxe" -> sxe()
            "s00e00" -> s00e00()
            "s00" -> media.seasonNumber?.let { it.toString().padStart(options.padLength, '0') }
            "e00" -> media.episodeNumbers.firstOrNull()?.let { it.toString().padStart(options.padLength, '0') }
            "t" -> media.episodeTitles.joinToString(" & ").ifBlank { null }
            "d", "airdate" -> media.episodeAirDates.firstOrNull()
            "startdate" -> media.firstAirDate ?: media.releaseDate
            "absolute" -> media.absoluteEpisode()
            "sn" -> media.seasonName
            "sy" -> media.seasonYears.takeIf { it.isNotEmpty() }
            "sc" -> media.numberOfSeasons
            "special" -> media.special
            "regular" -> media.isRegular
            "anime" -> media.isAnime
            "episodelist" -> episodelistString()
            // C 组
            "collection" -> media.collectionName
            "ci" -> media.collectionIndex
            "cy" -> media.collectionYears.takeIf { it.isNotEmpty() }
            "decade" -> media.year?.let { (it / 10) * 10 }
            "genre" -> media.genres.firstOrNull()
            "genres" -> media.genres.takeIf { it.isNotEmpty() }
            "language" -> media.originalLanguage
            "languages" -> media.spokenLanguages.takeIf { it.isNotEmpty() }
            "country" -> (media.originCountries + media.productionCountries).firstOrNull()
            "runtime" -> media.runtime
            "certification" -> media.certification
            "rating" -> media.rating
            "votes" -> media.votes
            "director" -> media.director
            "actors" -> media.actors.takeIf { it.isNotEmpty() }
            // D 组
            "pi" -> batch.partIndex
            "pc" -> batch.partCount
            "di" -> batch.duplicateIndex
            "dc" -> batch.duplicateCount
            "az" -> media.name?.let { sortName(it).firstOrNull()?.toString()?.uppercase() }
            // E 组
            "fn" -> file.displayName
            "ext" -> file.ext
            "f" -> file.fullPath
            "folder" -> file.folder
            "drive" -> file.drive
            "files" -> if (batch.filesCount > 0) "[${batch.filesCount} files]" else null
            "relativeFile" -> relativeFile()
            "mediaFile" -> file.fullPath
            "mediaFileName" -> file.displayName
            "original" -> file.displayName
            "ct" -> file.lastModified
            "age" -> ageDays()
            "bytes" -> file.contentLength?.let { humanReadable(it) }
            "megabytes" -> file.contentLength?.let { "${it / 1_000_000} MB" }
            "gigabytes" -> file.contentLength?.let { "%.1f GB".format(it / 1_000_000_000.0) }
            "today" -> batch.today.ifBlank { null }
            // F 组（来源文件名解析，非文件内容）
            "vf" -> file.parsed?.resolution
            "vc" -> file.parsed?.videoCodec
            "ac" -> file.parsed?.audioCodec
            "cf" -> file.ext
            "vs" -> file.parsed?.source
            "source" -> file.parsed?.source
            "edition" -> null
            "tags" -> null
            "s3d" -> null
            "group" -> file.parsed?.group
            // G 组
            "info" -> {
                val key = path.substringAfter('.', "")
                if (key.isEmpty()) media.info.toString() else media.info[key]
            }
            "localize" -> {
                val parts = path.split(".") // localize.<lang>.<var>
                if (parts.size >= 3) media.localize[parts[1]]?.get(parts[2]) else null
            }
            "order" -> {
                val parts = path.split(".") // order.<GROUP>.e
                if (parts.size >= 3) {
                    val grp = parts[1]; val ep = media.episodeNumbers.firstOrNull()?.toString() ?: ""
                    media.order[grp]?.get(ep)
                } else null
            }
            "self" -> selfSummary()
            "model" -> "[${batch.filesCount} items]"
            else -> {
                warnings += "未知变量 '$path'"
                null
            }
        }
        return value
    }

    private fun sxe(): String? {
        val s = media.seasonNumber ?: return null
        val e = media.episodeNumbers.firstOrNull() ?: return null
        return "${s}x${e.toString().padStart(options.padLength, '0')}"
    }

    private fun s00e00(): String? {
        val s = media.seasonNumber ?: return null
        val e = media.episodeNumbers.firstOrNull() ?: return null
        return "S${s.toString().padStart(options.padLength, '0')}E${e.toString().padStart(options.padLength, '0')}"
    }

    private fun defaultObjectString(): String {
        return if (media.isEpisode) episodeObjectString() else media.name?.let { if (media.year != null) "$it (${media.year})" else it } ?: ""
    }

    private fun episodeObjectString(): String {
        val s = media.seasonNumber
        val e = media.episodeNumbers.firstOrNull()
        val t = media.episodeTitles.joinToString(" & ")
        val name = media.name ?: ""
        return buildString {
            if (name.isNotEmpty()) append(name).append(" - ")
            if (s != null && e != null) append("${s}x${e.toString().padStart(options.padLength, '0')}").append(" - ")
            if (t.isNotEmpty()) append(t)
        }.trim(' ', '-').ifBlank { "" }
    }

    private fun episodelistString(): String {
        val s = media.seasonNumber ?: return ""
        return media.episodeNumbers.joinToString(", ") { "${s}x${it.toString().padStart(options.padLength, '0')}" }
            .let { "[$it]" }
    }

    private fun relativeFile(): String? {
        val f = file.fullPath ?: return null
        val drive = file.drive ?: return f.removePrefix("/")
        return f.removePrefix(drive).removePrefix("/")
    }

    private fun ageDays(): Int? {
        val ct = file.lastModified ?: return null
        val today = batch.today.ifBlank { return null }
        return runCatching {
            val d1 = java.time.LocalDate.parse(ct.substring(0, 10))
            val d2 = java.time.LocalDate.parse(today.substring(0, 10))
            (d2.toEpochDay() - d1.toEpochDay()).toInt()
        }.getOrNull()
    }

    private fun humanReadable(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1_024 -> "${bytes / 1_024} KB"
        else -> "$bytes B"
    }

    /** 去冠词排序名：The Walking Dead -> Walking Dead, The。 */
    fun sortName(s: String): String {
        val articles = listOf("The ", "A ", "An ")
        for (a in articles) {
            if (s.startsWith(a, ignoreCase = true)) {
                return s.substring(a.length) + ", " + s.substring(0, a.length - 1)
            }
        }
        return s
    }

    private fun selfSummary(): String =
        "n=${media.name}, y=${media.year}, s=${media.seasonNumber}, e=${media.episodeNumbers}, vf=${file.parsed?.resolution}"
}
