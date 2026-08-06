package xa.refile.core.naming

/**
 * 内置命名预设（计划 §5.5 内置预设表）。
 * 路径相对于用户选择的库根目录。
 *
 * 已按测试反馈 Item 9 简化：移除媒体服务器预设区分与「另存为预设」功能，
 * 仅保留一套内置默认模板。模板编辑器只支持直接编辑电影/剧集模板。
 */
enum class Preset(val displayName: String, val movieTemplate: String, val episodeTemplate: String) {
    DEFAULT(
        "默认",
        "{n.clean()} ({y})",
        "{n}({y}){s00e00}—{t.clean().space('')}",
    ),
    ;

    companion object {
        /** 按 ID 查找；找不到时容错返回 [DEFAULT]。 */
        fun byId(id: String): Preset = entries.firstOrNull { it.name.equals(id, true) } ?: DEFAULT
    }
}

/**
 * 预设与自定义模板仓库（计划 §5.5 + §3.2）。
 * 内置预设为内存常量；自定义模板由调用方持久化（app 层 DataStore）。
 */
class PresetRepository {
    fun builtinPresets(): List<Preset> = Preset.entries.toList()

    fun templateFor(preset: Preset, isEpisode: Boolean): String =
        if (isEpisode) preset.episodeTemplate else preset.movieTemplate
}
