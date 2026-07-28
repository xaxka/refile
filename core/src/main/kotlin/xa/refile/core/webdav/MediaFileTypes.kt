package xa.refile.core.webdav

/**
 * 媒体文件类型识别（计划 §5.2 文件浏览选择规则 + §5.2 伴随文件规则）。
 *
 * - 视频文件可被勾选选中参与重命名（`iso` 仅显示，不参与重命名）。
 * - 字幕/nfo/图片作为视频的伴随文件，重命名时跟随主视频自动改名，无需也不允许手动选中。
 *
 * 扩展名不区分大小写，比较时统一小写。
 */
object MediaFileTypes {
    /** 可被勾选选中参与重命名的视频扩展名（不含 `iso`）。
     *  mk3d/mks/mka（Matroska 衍生）、ogm（Ogg Media）、strm/strmlnk（流媒体指向文件）
     *  与 [FilenameParser.videoExtensions] 保持一致，避免浏览器侧漏识别。 */
    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb",
        "mk3d", "mks", "mka", "ogm", "strm", "strmlnk",
    )

    /** 仅显示不参与重命名的扩展名（`iso` 镜像）。 */
    val DISPLAY_ONLY_EXTENSIONS: Set<String> = setOf("iso")

    /** 字幕扩展名（伴随主视频改名）。sup=PGS（Remux 常见），vtt=WebVTT，smi=SAMI。 */
    val SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "sub", "idx", "sup", "vtt", "smi")

    /** 伴随文件扩展名：字幕 + nfo + 常见海报图。 */
    val COMPANION_EXTENSIONS: Set<String> = SUBTITLE_EXTENSIONS + setOf("nfo", "jpg", "jpeg", "png")

    /** 是否为可勾选的视频文件（参与重命名）。 */
    fun isSelectableVideo(fileName: String): Boolean {
        val ext = extension(fileName) ?: return false
        return ext in VIDEO_EXTENSIONS
    }

    /** 是否为仅显示文件（如 `iso`，显示但不可选中、不参与重命名）。 */
    fun isDisplayOnly(fileName: String): Boolean {
        val ext = extension(fileName) ?: return false
        return ext in DISPLAY_ONLY_EXTENSIONS
    }

    /** 是否为视频文件（含仅显示的 `iso`，用于列表项图标判定）。 */
    fun isVideo(fileName: String): Boolean = isSelectableVideo(fileName) || isDisplayOnly(fileName)

    /** 是否为字幕文件。 */
    fun isSubtitle(fileName: String): Boolean {
        val ext = extension(fileName) ?: return false
        return ext in SUBTITLE_EXTENSIONS
    }

    /** 是否为伴随文件（字幕/nfo/图片）。 */
    fun isCompanion(fileName: String): Boolean {
        val ext = extension(fileName) ?: return false
        return ext in COMPANION_EXTENSIONS
    }

    /** 取扩展名（不含点，已小写）。 */
    fun extension(fileName: String): String? {
        val name = fileName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
