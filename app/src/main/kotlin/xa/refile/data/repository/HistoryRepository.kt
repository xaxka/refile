package xa.refile.data.repository

import xa.refile.core.rename.CompanionRename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameReport
import xa.refile.core.rename.RenameResult
import xa.refile.data.db.RenameBatchDao
import xa.refile.data.db.RenameBatchEntity
import xa.refile.data.db.RenameEntryEntity
import xa.refile.data.prefs.SettingsRepository
import javax.inject.Inject

/**
 * 整批撤销结果（计划 §M5 SubTask 5.1.2 / 5.1.4）。
 *
 * - [Success]：所有可撤销条目均成功反向 MOVE。
 * - [Partial]：部分成功，[failedEntries] 为失败项（含原因）。UI 据此提示「已回滚 N/M 条」。
 * - [Failure]：撤销前置条件不满足（批次不存在 / 服务器已删除 / 已撤销等），未执行任何 MOVE。
 */
sealed class RevertResult {
    /** 全部回滚成功。rolledBack=已回滚条数，total=需回滚条数。 */
    data class Success(val rolledBack: Int, val total: Int) : RevertResult()

    /** 部分回滚成功。 */
    data class Partial(
        val rolledBack: Int,
        val total: Int,
        val failedEntries: List<FailedEntry>,
    ) : RevertResult()

    /** 未执行任何回滚（前置失败）。 */
    data class Failure(val reason: String) : RevertResult()
}

/** 单条撤销失败信息（用于 [RevertResult.Partial] 的失败项列表）。 */
data class FailedEntry(
    /** 失败项的目标路径（撤销时从此路径反向 MOVE）。可能为空（如渲染期就失败、未生成目标）。 */
    val targetPath: String,
    /** 失败项的源路径（B26：targetPath 为空时用于 UI 兜底显示，避免出现空字符串）。 */
    val sourcePath: String = "",
    val reason: String,
)

/**
 * 历史记录仓库（计划 §M5 SubTask 5.1.2 + 5.1.4）。
 *
 * 职责：
 * - 透传 [RenameBatchDao] 的 Flow 观察 / 单条查询。
 * - [recordBatch]：[RenameWorker] 执行完成后把 [RenameReport] 落库为一条批次 + 多条条目，
 *   随后按用户保留策略（[SettingsRepository.historyMaxCount] /
 *   [SettingsRepository.historyAutoClearDays]）自动清理过期/超量历史。
 * - [revertBatch]：取批次条目，**按 id 倒序**反向 MOVE（targetPath → sourcePath，含 companions 反向），
 *   中途失败不中断，最终标记 isReverted=true，返回 [RevertResult]。
 * - [cleanupOnStartup]：应用启动时按保留策略清理一次过期/超量历史。
 *
 * 安全：密码仅经 [ServerRepository.clientFor] 解密用于构造 [WebDavClient]，绝不进入历史/日志。
 * 反向 MOVE 用 `Overwrite: F`，避免覆盖目标已存在文件。
 */
class HistoryRepository @Inject constructor(
    private val dao: RenameBatchDao,
    private val serverRepository: ServerRepository,
    private val settings: SettingsRepository,
) {

    /** 观察所有批次（按 createdAt 倒序）。 */
    fun observeBatches(): Flow<List<RenameBatchEntity>> = dao.observeBatches()

    /** 按 id 取单条批次。 */
    suspend fun getBatch(id: Long): RenameBatchEntity? = withContext(Dispatchers.IO) { dao.getBatch(id) }

    /** 取某批次下所有条目（按 id 升序）。 */
    suspend fun getEntries(batchId: Long): List<RenameEntryEntity> =
        withContext(Dispatchers.IO) { dao.getEntries(batchId) }

    /**
     * 把一次批量执行的结果落库为历史。
     *
     * 从 [report].results 逐条映射为 [RenameEntryEntity]（status 取自 [RenameResult] 子类型），
     * 连同批次统计写入单事务。在 IO dispatcher 执行。
     *
     * 调用方（[xa.refile.worker.RenameWorker]）应在执行成功后调用本方法。
     * 若记录失败被吞掉，不影响 Worker 的成功返回（历史为辅助功能）。
     */
    suspend fun recordBatch(
        serverId: Long,
        serverName: String,
        batchName: String?,
        report: RenameReport,
        @Suppress("UNUSED_PARAMETER") operations: List<RenameOperation>,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val batch = RenameBatchEntity(
            serverId = serverId,
            serverName = serverName,
            batchName = batchName?.takeIf { it.isNotBlank() } ?: defaultBatchName(now),
            createdAt = now,
            totalOperations = report.total,
            succeededCount = report.succeeded,
            failedCount = report.failed,
        )
        val entries = report.results.map { (op, result) ->
            RenameEntryEntity(
                batchId = 0L, // insertBatchWithEntries 内回填
                sourcePath = op.sourcePath,
                targetPath = op.targetPath,
                mediaType = op.mediaType.name,
                companionsJson = CompanionJson.encode(op.companions),
                status = statusOf(result),
                errorMessage = errorMessageOf(result),
            )
        }
        val batchId = dao.insertBatchWithEntries(batch, entries)
        // 落库后按保留策略清理超量/过期历史（设置项 historyMaxCount / historyAutoClearDays）。
        runCatching { enforceRetention() }
        batchId
    }

    /**
     * 应用启动时按保留策略清理一次历史。
     *
     * 由 [xa.refile.RefileApp.onCreate] 调用：清理 [historyAutoClearDays] 天前的批次，
     * 并裁剪到 [historyMaxCount] 上限。异常被吞掉，不影响应用启动。
     */
    suspend fun cleanupOnStartup() = withContext(Dispatchers.IO) {
        runCatching { enforceRetention() }
        Unit
    }

    /**
     * 读取保留策略并执行清理：
     * - [SettingsRepository.historyMaxCount] > 0 → 仅保留最新 N 条，删除更旧的。
     * - [SettingsRepository.historyAutoClearDays] > 0 → 删除创建时间早于该天数的批次。
     *
     * 两个策略独立叠加执行（先按天数删过期，再按条数裁剪超量）。
     * 任一为 0 表示该维度不限制。
     */
    private suspend fun enforceRetention() {
        val maxCount = settings.historyMaxCount.first()
        val autoClearDays = settings.historyAutoClearDays.first()
        if (autoClearDays > 0) {
            val threshold = System.currentTimeMillis() -
                autoClearDays.toLong() * MILLIS_PER_DAY
            dao.deleteOlderThan(threshold)
        }
        if (maxCount > 0) {
            val current = dao.countBatches()
            if (current > maxCount) {
                dao.deleteOldestBeyond(maxCount)
            }
        }
    }

    /**
     * 整批撤销（SubTask 5.1.4）。
     *
     * 流程：
     * 1. 取批次；不存在 → [RevertResult.Failure]。已撤销 → [RevertResult.Failure]（避免重复撤销）。
     * 2. 取服务器配置；已删除 → [RevertResult.Failure]（提示「服务器已删除」）。
     * 3. 取条目，按 id 倒序，仅对 status ∈ {SUCCESS, PARTIAL} 的条目执行反向 MOVE
     *    （FAILED/SKIPPED 主文件未落地，无需撤销）。companions 同样反向 MOVE。
     * 4. 中途失败不中断，记录到 [FailedEntry]。
     * 5. 全部尝试完后标记 batch.isReverted=true, revertedAt=now。
     * 6. 返回 [RevertResult.Success] / [RevertResult.Partial]。
     */
    suspend fun revertBatch(
        batchId: Long,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): RevertResult = withContext(Dispatchers.IO) {
        val batch = dao.getBatch(batchId)
            ?: return@withContext RevertResult.Failure("批次不存在")
        if (batch.isReverted) {
            return@withContext RevertResult.Failure("该批次已撤销，不能重复撤销")
        }

        val server = serverRepository.getServer(batch.serverId)
            ?: return@withContext RevertResult.Failure("服务器已删除，无法撤销")

        val client = serverRepository.clientFor(server)
        val entries = dao.getEntries(batchId).sortedByDescending { it.id }

        // 仅需撤销「主文件已落地」的条目（SUCCESS / PARTIAL）。
        val toRevert = entries.filter { it.status == STATUS_SUCCESS || it.status == STATUS_PARTIAL }
        val total = toRevert.size
        if (total == 0) {
            dao.markReverted(batchId, System.currentTimeMillis())
            return@withContext RevertResult.Success(rolledBack = 0, total = 0)
        }

        val failed = mutableListOf<FailedEntry>()
        var rolledBack = 0
        for ((index, entry) in toRevert.withIndex()) {
            // 进度回调：进入每一条目前上报 current = index+1 / total。
            // ViewModel 把它转成 StateFlow 驱动底部 LinearProgressIndicator 实时更新。
            onProgress?.invoke(index + 1, total)
            // 先反向 companions（恢复伴随文件原名），再反向主文件。
            // overwrite=false：若反向目标已存在（不应发生），保留现状并记失败。
            // 伴随文件失败不计入 failed（避免 rolledBack + failed.size > total），
            // 仅主文件失败才计入 failed，确保每条目只计入一个统计。
            val companions = CompanionJson.decode(entry.companionsJson)
            for (comp in companions) {
                try {
                    client.move(comp.targetPath, comp.sourcePath, overwrite = false)
                } catch (e: Exception) {
                    // B5: 重抛取消信号，避免协程被取消后仍继续反向后续文件。
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // 伴随失败不计入 failed，由主文件结果统一决定本条目归类。
                }
            }
            // 主文件反向 MOVE（即使部分 companion 失败仍尝试恢复主文件，尽量还原）。
            val mainOk = try {
                client.move(entry.targetPath, entry.sourcePath, overwrite = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (mainOk) {
                // 主文件已恢复 → 计入 rolledBack（即使伴随失败也不加 failed）。
                rolledBack++
            } else {
                failed.add(
                    FailedEntry(
                        targetPath = entry.targetPath,
                        sourcePath = entry.sourcePath,
                        reason = "主文件反向 MOVE 失败: ${entry.targetPath} -> ${entry.sourcePath}",
                    ),
                )
            }
        }

        dao.markReverted(batchId, System.currentTimeMillis())
        when {
            failed.isEmpty() -> RevertResult.Success(rolledBack = rolledBack, total = total)
            else -> RevertResult.Partial(
                rolledBack = rolledBack,
                total = total,
                failedEntries = failed,
            )
        }
    }

    // ---- 内部工具 ----

    /** 取 [RenameResult] 子类型对应的 status 字符串。 */
    private fun statusOf(result: RenameResult): String = when (result) {
        is RenameResult.Success -> STATUS_SUCCESS
        is RenameResult.Partial -> STATUS_PARTIAL
        is RenameResult.Failed -> STATUS_FAILED
        is RenameResult.Skipped -> STATUS_SKIPPED
    }

    /** 取失败/部分失败时的可读错误信息。 */
    private fun errorMessageOf(result: RenameResult): String? = when (result) {
        is RenameResult.Failed -> result.reason
        is RenameResult.Partial -> "伴随文件失败: ${result.failedCompanions.joinToString("; ")}"
        else -> null
    }

    /** 默认批次名：按时间戳生成。 */
    private fun defaultBatchName(now: Long): String = "重命名批次 ${formatMillis(now)}"

    private fun formatMillis(ms: Long): String {
        // 简单格式化 yyyy-MM-dd HH:mm，避免引入 java.time 依赖差异。
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SKIPPED = "SKIPPED"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * [xa.refile.core.rename.CompanionRename] 列表 ↔ JSON 的简易编解码器。
 *
 * :app 模块不直接依赖 kotlinx-serialization-json（:core 内为 implementation 不传递），
 * 故用极简的手写 JSON 编解码：仅处理 List<CompanionRename> 这种 [{s,t},{s,t}] 形态。
 * 字符串值按 JSON 规范转义（引号/反斜杠/控制字符）。
 *
 * 仅用于历史落库，非热路径，性能可接受。
 */
private object CompanionJson {

    fun encode(list: List<CompanionRename>): String {
        if (list.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append('[')
        list.forEachIndexed { index, c ->
            if (index > 0) sb.append(',')
            sb.append("{\"sourcePath\":")
            sb.append(quote(c.sourcePath))
            sb.append(",\"targetPath\":")
            sb.append(quote(c.targetPath))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(json: String): List<CompanionRename> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        // 不依赖字段顺序：分别提取所有 sourcePath 和 targetPath 值，按位置配对。
        // 每个 CompanionRename 对象恰好各含一个 sourcePath 与一个 targetPath，
        // 故按出现顺序 zip 即可正确配对，避免硬编码 sourcePath 在 targetPath 之前。
        val srcRegex = Regex(""""sourcePath"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val tgtRegex = Regex(""""targetPath"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val sources = srcRegex.findAll(trimmed).map { unescape(it.groupValues[1]) }.toList()
        val targets = tgtRegex.findAll(trimmed).map { unescape(it.groupValues[1]) }.toList()
        return sources.zip(targets).map { (s, t) ->
            CompanionRename(sourcePath = s, targetPath = t)
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: 0
                            sb.appendCodePoint(code)
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
