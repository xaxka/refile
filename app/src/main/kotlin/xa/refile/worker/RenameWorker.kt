package xa.refile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import xa.refile.core.rename.RenameExecutor
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameOperationJson
import xa.refile.core.webdav.WebDavClient
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.HistoryRepository
import xa.refile.data.repository.ServerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * 批量重命名后台 Worker（计划 §M4 Task 4.2.1）。
 *
 * 经 WorkManager 调度，[RenameWorkScheduler] 入队时把 [List]<[RenameOperation]> 序列化为
 * JSON 存入 WorkData（[KEY_OPERATIONS_JSON]），App 被杀后 WorkManager 可恢复继续执行。
 *
 * 流程：
 * 1. 取 serverId + operationsJson，按 [ServerConfigEntity] 解密密码并构造 [WebDavClient]
 *    （构造方式参照 [ServerRepository] 内部 buildFullBaseUrl）。
 * 2. [setForeground] 提升为前台服务（dataSync 类型，manifest 已声明），保证长任务不被回收。
 * 3. [RenameExecutor.execute] 执行，进度回调里更新通知 + [setProgress] 供 UI 观察。
 * 4. 结果：
 *    - 执行完成（全成功或部分失败） → [Result.success] 携带 [KEY_RESULT_REPORT_JSON]（完整报告 JSON，
 *      含统计与失败原因，供 UI 展示/重试），并回传 [KEY_SERVER_ID]/[KEY_BATCH_NAME] 供结果页重新入队
 *    - 网络可重试错误（IOException） → [Result.retry]
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getLong(KEY_SERVER_ID, INVALID_SERVER_ID)
        val operationsJson = inputData.getString(KEY_OPERATIONS_JSON)
        if (serverId == INVALID_SERVER_ID || operationsJson.isNullOrEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "缺少 serverId 或 operations"))
        }

        val entity = serverRepo.getServer(serverId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "找不到服务器配置 id=$serverId"))

        val ops: List<RenameOperation> = try {
            RenameOperationJson.decode(operationsJson)
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to "操作列表解析失败: ${e.message}"))
        }
        if (ops.isEmpty()) {
            return Result.success()
        }

        val client = buildClient(entity)
        val batchName = inputData.getString(KEY_BATCH_NAME)

        ensureChannel()
        setForeground(buildForegroundInfo(batchName, 0, ops.size, ops.first()))

        // Task 4.1 增强：从设置读取执行阶段冲突策略与回收站配置，注入 [RenameExecutor]。
        // 回收站总开关关闭时把生效目录置空，[RenameExecutor.safeDelete] 将直接返回 false（不执行回收备份）。
        val conflictStrategy = settings.conflictStrategy.first()
        val trashEnabled = settings.trashEnabled.first()
        val trashDir = if (trashEnabled) settings.trashDir.first() else ""
        val executor = RenameExecutor(client, trashDir = trashDir)
        val report = try {
            executor.execute(ops, conflictStrategy = conflictStrategy) { current, total, op ->
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
                notifyProgress(batchName, current, total, op)
            }
        } catch (e: IOException) {
            // 网络可重试错误：让 WorkManager 按退避策略重试整个任务。
            return Result.retry()
        } catch (e: Exception) {
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

        // 携带完整报告 JSON（含统计与失败原因）供进度/结果页展示与重试，
        // 并回传 serverId/batchName 供结果页"重试失败项"重新入队。
        return Result.success(
            workDataOf(
                KEY_RESULT_REPORT_JSON to RenameOperationJson.encodeReport(report),
                KEY_SERVER_ID to serverId,
                KEY_BATCH_NAME to batchName,
            ),
        )
    }

    /**
     * 构造已带认证拦截的 [WebDavClient]，复用 [ServerRepository.clientFor]：
     * - baseUrl 经 ServerRepository.buildFullBaseUrl 规范化：当 baseUrl 已含
     *   scheme/host/port/路径（如 `https://dav.example.com:8443/dav`）时直接 trim 末尾
     *   斜杠返回，不再用 https/port 字段二次拼装，避免生成 `host:8443/path:8443` 这种
     *   重复端口的非法 URL。
     * - 与 ServerRepository.clientFor 共用同一套 OkHttpClient 构造逻辑。
     */
    private suspend fun buildClient(entity: ServerConfigEntity): WebDavClient =
        serverRepo.clientFor(entity)

    /** 创建 LOW 优先级通知渠道（仅首次），不发声、状态栏可见。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_RENAME) != null) return
        val channel = NotificationChannel(
            CHANNEL_RENAME,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "批量重命名任务进度"
        }
        manager.createNotificationChannel(channel)
    }

    /** 构造进度通知：标题含 batchName 或 "正在重命名" + (current/total)，内容为当前文件名。 */
    private fun buildNotification(
        batchName: String?,
        current: Int,
        total: Int,
        op: RenameOperation?,
    ): NotificationCompat.Builder {
        val baseTitle = if (batchName.isNullOrBlank()) DEFAULT_TITLE else batchName
        val title = "$baseTitle ($current/$total)"
        val text = op?.sourcePath?.substringAfterLast('/').orEmpty()
        return NotificationCompat.Builder(applicationContext, CHANNEL_RENAME)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, current, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    /** 构造前台信息，API 29+ 标注 dataSync 服务类型。 */
    private fun buildForegroundInfo(
        batchName: String?,
        current: Int,
        total: Int,
        op: RenameOperation?,
    ): ForegroundInfo {
        val notification = buildNotification(batchName, current, total, op).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** 进度回调中刷新已显示的通知（同一 NOTIFICATION_ID 原地更新）。 */
    private fun notifyProgress(
        batchName: String?,
        current: Int,
        total: Int,
        op: RenameOperation,
    ) {
        val notification = buildNotification(batchName, current, total, op).build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        // WorkData 键（与 RenameWorkScheduler 共享）。
        const val KEY_SERVER_ID = "server_id"
        const val KEY_OPERATIONS_JSON = "operations_json"
        const val KEY_BATCH_NAME = "batch_name"
        const val KEY_RESULT_REPORT_JSON = "result_report_json"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_FILENAME = "progress_filename"

        private const val INVALID_SERVER_ID = -1L
        private const val CHANNEL_RENAME = "rename_channel"
        private const val NOTIFICATION_ID = 4242
        private const val DEFAULT_TITLE = "正在重命名"
        private const val NOTIFICATION_CHANNEL_NAME = "重命名任务"
    }
}
