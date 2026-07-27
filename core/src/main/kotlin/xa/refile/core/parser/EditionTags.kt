package xa.refile.core.parser

/**
 * Edition / 版本标签（计划 §5.3 P1.1，参考 TMM `MovieEdition`）。
 *
 * 命中后填入 [ParsedFilename.edition]，并在 [FilenameParser.cleanTitle] 中剥离，
 * 避免污染标题相似度。模板引擎可消费 `${edition}` token。
 *
 * 列表覆盖 TMM 13 项 + Open Matte。匹配在归一化文本（`.`/`_` 已转空格）上做大小写不敏感匹配，
 * 故分隔符模式用 `[.\\s]?` 兼容点号与空格；命中后 [displayName] 统一为展示名（如 `Director's Cut`）。
 */
enum class Edition(
    val displayName: String,
    val pattern: Regex,
) {
    DIRECTORS_CUT("Director's Cut", Regex("(?i)director'?s?[.\\s]?cut")),
    EXTENDED("Extended", Regex("(?i)extended([.\\s]?(?:cut|edition|version))?")),
    THEATRICAL("Theatrical", Regex("(?i)theatrical([.\\s]?(?:cut|edition|version))?")),
    UNRATED("Unrated", Regex("(?i)unrated")),
    UNCUT("Uncut", Regex("(?i)uncut")),
    IMAX("IMAX", Regex("(?i)\\bimax\\b")),
    REMASTERED("Remastered", Regex("(?i)remastered")),
    COLLECTORS("Collector's Edition", Regex("(?i)collector'?s?([.\\s]?edition)?")),
    ULTIMATE("Ultimate", Regex("(?i)ultimate([.\\s]?(?:cut|edition|version))?")),
    FINAL_CUT("Final Cut", Regex("(?i)final[.\\s]?cut")),
    SPECIAL("Special Edition", Regex("(?i)special([.\\s]?edition)?")),
    CRITERION("Criterion", Regex("(?i)criterion([.\\s]?(?:collection|edition))?")),
    OPEN_MATTE("Open Matte", Regex("(?i)open[.\\s]?matte")),
    ;

    companion object {
        /** 在原文中找首个命中的 Edition；未命中返回 null。 */
        fun find(input: String): Edition? = entries.firstOrNull { it.pattern.containsMatchIn(input) }
    }
}
