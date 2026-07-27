package xa.refile.core.parser

/**
 * 附加内容类型（计划 §5.3 P2.5，参考 Jellyfin `VideoExtraRules`）。
 *
 * 命中后填入 [ParsedFilename.extraType]。重命名器可选择跳过或单独命名。
 * 检测模式：文件名中后缀形式 `-trailer` / `-sample` / `-interview` 等（分隔符为 `[-_. ]`）。
 */
enum class ExtraType(
    private val pattern: Regex,
) {
    TRAILER(Regex("(?i)(?:^|[\\s._-])trailer(?:[\\s._-]|$)")),
    SAMPLE(Regex("(?i)(?:^|[\\s._-])sample(?:[\\s._-]|$)")),
    INTERVIEW(Regex("(?i)(?:^|[\\s._-])interview(?:[\\s._-]|$)")),
    BEHIND_THE_SCENES(Regex("(?i)(?:^|[\\s._-])(?:behind(?:[\\s._-])?the[\\s._-]scenes|featurette)(?:[\\s._-]|$)")),
    DELETED_SCENE(Regex("(?i)(?:^|[\\s._-])deleted(?:[\\s._-])?scene(?:[\\s._-]|$)")),
    FEATURETTE(Regex("(?i)(?:^|[\\s._-])featurette(?:[\\s._-]|$)")),
    SHORT(Regex("(?i)(?:^|[\\s._-])short(?:[\\s._-]|$)")),
    CLIP(Regex("(?i)(?:^|[\\s._-])clip(?:[\\s._-]|$)")),
    OTHER(Regex("(?i)(?:^|[\\s._-])(?:other|extra|bonus)(?:[\\s._-]|$)")),
    ;

    companion object {
        /** 找首个命中的 ExtraType；未命中返回 null。 */
        fun find(input: String): ExtraType? = entries.firstOrNull { it.pattern.containsMatchIn(input) }
    }
}
