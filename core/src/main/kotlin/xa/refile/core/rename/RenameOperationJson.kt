package xa.refile.core.rename

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [RenameOperation] 列表与 JSON 字符串互转工具（计划 §M4 Task 4.2）。
 *
 * 用途：WorkManager 的 WorkData 仅支持基本类型/字符串，需将 [List]<[RenameOperation]>
 * 序列化为 JSON 存入 WorkData 的 KEY_OPERATIONS_JSON，以便 App 被杀后恢复队列。
 *
 * :app 模块无 kotlinx-serialization-json 直接依赖（:core 为 implementation 不传递），
 * 故把编解码逻辑放在 :core，由 worker/scheduler 调用，避免 :app 引入额外依赖。
 *
 * [Json] 配置：[Json.ignoreUnknownKeys] 容忍字段增减（向前兼容），
 * [Json.encodeDefaults] 输出默认值（如 [RenameOperation.mediaType]），保证反序列化稳定。
 */
object RenameOperationJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(RenameOperation.serializer())
    private val reportSerializer = RenameReport.serializer()

    fun encode(ops: List<RenameOperation>): String =
        json.encodeToString(listSerializer, ops)

    fun decode(s: String): List<RenameOperation> =
        json.decodeFromString(listSerializer, s)

    /** 编码完整执行报告（统计 + 失败原因），供 Worker 经 WorkData 传递给进度/结果页。 */
    fun encodeReport(report: RenameReport): String =
        json.encodeToString(reportSerializer, report)

    /** 解码 [encodeReport] 产出的报告 JSON。 */
    fun decodeReport(s: String): RenameReport =
        json.decodeFromString(reportSerializer, s)
}
