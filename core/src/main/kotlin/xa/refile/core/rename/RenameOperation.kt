package xa.refile.core.rename

import xa.refile.core.model.MediaType
import kotlinx.serialization.Serializable

/**
 * 单条重命名操作（主文件 + 伴随文件）。伴随文件为同目录下同名（去扩展名）的字幕/nfo/图片，
 * 由调用方（预览页）解析后填入（可由 [CompanionResolver] 自动发现）。
 *
 * 标注 [Serializable] 以便 WorkManager 将操作列表序列化为 JSON 存入 WorkData（App 杀后恢复）。
 *
 * @property sourcePath  主文件源路径，如 `/Movies/a.mkv`。
 * @property targetPath  主文件目标路径，如 `/Movies/The Movie (2023)/The Movie (2023).mkv`。
 * @property companions   伴随文件（字幕/nfo/图片），跟随主文件一并重命名。
 * @property mediaType    媒体类型，默认电影。
 */
@Serializable
data class RenameOperation(
    val sourcePath: String,
    val targetPath: String,
    val companions: List<CompanionRename> = emptyList(),
    val mediaType: MediaType = MediaType.MOVIE,
)

/** 单个伴随文件的重命名（源路径 → 目标路径）。 */
@Serializable
data class CompanionRename(val sourcePath: String, val targetPath: String)

/**
 * 单条操作结果。
 *
 * - [Success]：主文件及所有伴随文件均成功。
 * - [Partial]：主文件成功但部分（或全部）伴随文件失败。
 * - [Failed]：主文件失败（伴随文件不处理）。
 * - [Skipped]：调用方标记排除。
 */
@Serializable
sealed class RenameResult {
    @Serializable
    data object Success : RenameResult()

    /** 部分成功：主文件成功但部分伴随文件失败。[failedCompanions] 为失败的伴随源路径。 */
    @Serializable
    data class Partial(val failedCompanions: List<String>) : RenameResult()

    /** 整体失败。[httpCode] 为 WebDAV 返回的状态码（可空，网络错误时无）。 */
    @Serializable
    data class Failed(val reason: String, val httpCode: Int? = null) : RenameResult()

    /** 跳过（调用方标记排除）。 */
    @Serializable
    data class Skipped(val reason: String) : RenameResult()
}

/**
 * 批量执行报告。
 *
 * 标注 [Serializable] 以便 Worker 把整份报告（含统计与失败原因）序列化为 JSON 存入 WorkData，
 * 供进度/结果页解码展示与重试入队。
 *
 * @property results   每条操作与其结果的配对（保持执行顺序）。
 * @property total      操作总数。
 * @property succeeded  成功数（[RenameResult.Success] + [RenameResult.Partial] 计为成功，主文件已落地）。
 * @property failed     失败数（仅 [RenameResult.Failed]）。
 */
@Serializable
data class RenameReport(
    val results: List<Pair<RenameOperation, RenameResult>>,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val isAllSucceeded: Boolean get() = failed == 0

    /** 仅失败的操作（主文件未落地）。 */
    val failedOperations: List<Pair<RenameOperation, RenameResult.Failed>>
        get() = results.mapNotNull { (op, res) ->
            if (res is RenameResult.Failed) op to res else null
        }
}
