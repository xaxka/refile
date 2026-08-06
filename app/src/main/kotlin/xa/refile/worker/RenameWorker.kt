package xa.refile.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import xa.refile.core.rename.RenameExecutor
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameOperationJson
import xa.refile.core.rename.RenameReport
import xa.refile.core.webdav.FileClient
import xa.refile.data.db.PendingRenameBatchDao
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.HistoryRepository
import xa.refile.data.repository.ServerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * 批量重命名后台 Worker（计划 §M4 Task 4.2.1）。
 *
 * 经 WorkManager 调度，[RenameWorkScheduler] 入队时把 [List]<[RenameOperation]> 序列化为
 * JSON 存入数据库 [PendingRenameBatchDao]（绕过 WorkData 10KB 上限），仅将数据库 id 传入 WorkData。
 * App 被杀后 WorkManager 可恢复继续执行，Worker 从数据库重新读取操作列表。
 *
 * 流程：
 * 1. 取 serverId + pendingBatchId，从数据库读取操作 JSON，按 [ServerConfigEntity] 经 [ServerRepository.clientFor] 构造 [FileClient]
 *    （WebDAV 或 OpenList，按 type 字段分发）。
 * 2. [RenameExecutor.execute] 执行，进度回调里经 [setProgressAsync] 上报进度供 UI 观察（无前台服务通知）。
 * 3. 结果：
 *    - 执行完成（全成功或部分失败） → [Result.success]：完整报告 JSON 写入 cacheDir 文件，
 *      WorkData 只回传文件名（[KEY_RESULT_REPORT_PATH]）+ serverId/batchName，**避免 report 超 WorkData
 *      10KB 上限抛 IllegalStateException 导致任务 FAILED**。结果页用文件名读取报告展示/重试。
 *    - 网络可重试错误（IOException） → [Result.retry]；重试次数达到 [MAX_RUN_ATTEMPTS] 后转为
 *      [Result.failure] 携带 [KEY_ERROR]，避免 WorkManager 耗尽重试后产生无 [KEY_ERROR] 的 FAILED
 *    - 不可恢复（配置缺失/输入非法/其它异常） → [Result.failure] 携带 [KEY_ERROR]
 *
 * 用 [HiltWorker] + [AssistedInject] 注入 [ServerRepository]/[HistoryRepository]；
 * [RefileApp] 已实现 Configuration.Provider 绑定 HiltWorkerFactory。
 */
@HiltWorker
class RenameWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val serverRepo: ServerRepository,
    private val historyRepo: HistoryRepository,
    private val settings: SettingsRepository,
    private val pendingDao: PendingRenameBatchDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getLong(KEY_SERVER_ID, INVALID_SERVER_ID)
        val pendingBatchId = inputData.getLong(KEY_PENDING_BATCH_ID, INVALID_PENDING_ID)
        if (serverId == INVALID_SERVER_ID || pendingBatchId == INVALID_PENDING_ID) {
            return Result.failure(workDataOf(KEY_ERROR to "缺少 serverId 或 pendingBatchId"))
        }

        // 从数据库读取操作列表 JSON（绕过 WorkData 10KB 限制）。
        val pendingBatch = pendingDao.getById(pendingBatchId)
        if (pendingBatch == null) {
            return Result.failure(workDataOf(KEY_ERROR to "待执行操作记录不存在 (id=$pendingBatchId)"))
        }
        val operationsJson = pendingBatch.operationsJson

        val entity = serverRepo.getServer(serverId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "找不到服务器配置 id=$serverId"))

        val ops: List<RenameOperation> = try {
            RenameOperationJson.decode(operationsJson)
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to "操作列表解析失败: ${e.message}"))
        }
        if (ops.isEmpty()) {
            pendingDao.deleteById(pendingBatchId)
            // 空操作也回传空报告（写文件），避免结果页因 report 为 null 误判为"任务执行出错"。
            return Result.success(
                workDataOf(
                    KEY_RESULT_REPORT_PATH to writeReportFile(
                        pendingBatchId,
                        RenameReport(results = emptyList(), total = 0, succeeded = 0, failed = 0),
                    ),
                ),
            )
        }

        val batchName = inputData.getString(KEY_BATCH_NAME)

        // Task 4.1 增强：从设置读取执行阶段冲突策略与回收站配置，注入 [RenameExecutor]。
        // 回收站总开关关闭时把生效目录置空，[RenameExecutor.safeDelete] 将直接返回 false（不执行回收备份）。
        // buildClient/配置读取/执行统一纳入 try，任何异常都携带 [KEY_ERROR] 返回失败，
        // 避免未捕获异常导致 WorkManager 产生无 KEY_ERROR 的 FAILED（结果页只显示默认"任务执行失败"）。
        val report = try {
            val conflictStrategy = settings.conflictStrategy.first()
            val trashEnabled = settings.trashEnabled.first()
            val trashDir = if (trashEnabled) settings.trashDir.first() else ""
            val concurrency = settings.concurrencyLimit.first()
            val client = buildClient(entity)
            val executor = RenameExecutor(client, trashDir = trashDir)
            executor.execute(ops, conflictStrategy = conflictStrategy, concurrency = concurrency) { current, total, op ->
                // onProgress 是非 suspend 回调，用 setProgressAsync（ListenableWorker 继承，
                // 返回 ListenableFuture，fire-and-forget）写入进度供 UI 观察，避免在非 suspend
                // lambda 中调用 suspend 的 setProgress。
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS_CURRENT to current,
                        KEY_PROGRESS_TOTAL to total,
                        KEY_PROGRESS_FILENAME to op.sourcePath.substringAfterLast('/'),
                    ),
                )
            }
        } catch (e: IOException) {
            // 网络可重试错误：保留 pending 记录供 WorkManager 重试时重新读取。
            // 重试次数达到上限后主动返回失败并携带原因，避免 WorkManager 耗尽重试转为无 KEY_ERROR 的 FAILED。
            if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                pendingDao.deleteById(pendingBatchId)
                return Result.failure(workDataOf(KEY_ERROR to "网络错误，已重试 $runAttemptCount 次仍失败: ${e.message}"))
            }
            return Result.retry()
        } catch (e: Exception) {
            // 不可恢复异常：清理 pending 记录后返回失败。
            pendingDao.deleteById(pendingBatchId)
            return Result.failure(workDataOf(KEY_ERROR to "执行异常: ${e.message}"))
        }

        setProgressAsync(
            workDataOf(
                KEY_PROGRESS_CURRENT to report.total,
                KEY_PROGRESS_TOTAL to report.total,
                KEY_PROGRESS_FILENAME to "",
            ),
        )

        // Task 5.1.2：执行完成后落库历史记录。历史为辅助功能，记录失败被吞掉，
        // 不影响 Worker 成功返回（用户已看到重命名成功结果）。doWork 是 suspend，可直接调用。
        runCatching {
            historyRepo.recordBatch(
                serverId = serverId,
                serverName = entity.name,
                batchName = batchName,
                report = report,
                operations = ops,
            )
        }

        // 完整 report JSON 可能很大（含每条操作的 source/target/companions），塞进 WorkData
        // 会触发 10KB 上限抛 IllegalStateException（曾导致任务直接 FAILED 且无 KEY_ERROR）。
        // 改为写入 cacheDir 文件，WorkData 只回传文件名，结果页用文件名读取。
        val reportFileName = writeReportFile(pendingBatchId, report)

        // 执行完成，清理 pending 记录（操作列表已不需要，历史记录已落库）。
        pendingDao.deleteById(pendingBatchId)

        // WorkData 只回传 report 文件名 + serverId/batchName（均为小数据，绝不超 10KB），
        // 供结果页读取报告与"重试失败项"重新入队。
        return Result.success(
            workDataOf(
                KEY_RESULT_REPORT_PATH to reportFileName,
                KEY_SERVER_ID to serverId,
                KEY_BATCH_NAME to batchName,
            ),
        )
    }

    /**
     * 把 [RenameReport] 序列化为 JSON 写入 cacheDir 文件，返回文件名（仅文件名，不含路径）。
     *
     * 结果页用 `File(cacheDir, name)` 还原。写文件失败时返回空串，结果页将判 report 为 null
     * 显示"任务执行出错"，但不会让 Worker 本身失败（不阻塞成功路径）。
     */
    private fun writeReportFile(pendingBatchId: Long, report: RenameReport): String {
        val name = "rename_report_$pendingBatchId.json"
        return runCatching {
            File(applicationContext.cacheDir, name).writeText(RenameOperationJson.encodeReport(report))
            name
        }.getOrDefault("")
    }

    /**
     * 构造已带认证拦截的 [FileClient]，复用 [ServerRepository.clientFor]：
     * 按 [ServerConfigEntity.type] 分发到 WebDAV 或 OpenList 后端，与连接测试/浏览器走同一套构造逻辑。
     */
    private suspend fun buildClient(entity: ServerConfigEntity): FileClient =
        serverRepo.clientFor(entity, maxRequestsPerHost = settings.concurrencyLimit.first())

    companion object {
        // WorkData 键（与 RenameWorkScheduler 共享）。
        const val KEY_SERVER_ID = "server_id"
        const val KEY_PENDING_BATCH_ID = "pending_batch_id"
        const val KEY_OPERATIONS_JSON = "operations_json"
        const val KEY_BATCH_NAME = "batch_name"
        const val KEY_RESULT_REPORT_PATH = "result_report_path"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_FILENAME = "progress_filename"

        private const val INVALID_SERVER_ID = -1L
        private const val INVALID_PENDING_ID = -1L
        // 网络错误重试上限：对应 WorkManager 默认的 WorkSpec.MAX_RETRIES（5）。
        // 达到此值后主动 Result.failure 携带 KEY_ERROR，避免耗尽重试后转为无原因的 FAILED。
        private const val MAX_RUN_ATTEMPTS = 5
    }
}
