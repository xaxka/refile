package xa.refile.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameOperationJson
import xa.refile.data.db.PendingRenameBatchDao
import xa.refile.data.db.PendingRenameBatchEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 重命名任务入队辅助（计划 §M4 Task 4.2.2）。
 *
 * 把 [List]<[RenameOperation]> 经 [RenameOperationJson] 序列化为 JSON 后存入
 * [PendingRenameBatchDao]（Room 表），仅将数据库 id 传入 WorkData。
 * 这样绕过 WorkData 10KB 序列化上限，支持任意大小的操作列表。
 *
 * 构造 [Constraints]（需联网，与 WebDAV MOVE/MKCOL 一致），
 * 通过 [WorkManager] 入队一次性 [RenameWorker]，返回 work.id 供 UI 观察进度。
 *
 * WorkData 键常量复用 [RenameWorker] 的 companion，保证入参与读取一致。
 */
class RenameWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingDao: PendingRenameBatchDao,
) {

    /**
     * 入队一批重命名任务。
     *
     * @param serverId   目标服务器配置 id。
     * @param operations 待执行的重命名操作列表（将序列化为 JSON 存入数据库）。
     * @param batchName  批次名（可选，用于通知标题展示）。
     * @return WorkRequest 的 [UUID]，供 [observeWork] 观察进度/状态。
     */
    suspend fun enqueue(
        serverId: Long,
        operations: List<RenameOperation>,
        batchName: String? = null,
    ): UUID {
        val wm = WorkManager.getInstance(context)
        // P1-7③（审查报告 2026-09-25）：unique work 兜底防并发重放。
        // 入队挂起期间（逐目录 PROPFIND 解析伴随文件可达数秒）重复点击会入队多个
        // 并发任务（每次新 UUID 无去重），后续任务在源文件被移走后执行，产生大批
        // 失败记录污染历史。同服务器已有未完成（排队中/进行中）的重命名任务时
        // 不再重复入队，直接返回既有任务 id 供 UI 观察进度（KEEP 语义）。
        // get() 阻塞查询挂到 IO（调用方可能在主线程协程）。
        val existingUnfinished = withContext(Dispatchers.IO) {
            wm.getWorkInfosForUniqueWork(uniqueName(serverId)).get()
                .firstOrNull { !it.state.isFinished }
        }
        if (existingUnfinished != null) return existingUnfinished.id

        // 把操作列表 JSON 存入数据库，仅传 id 给 WorkData（绕过 10KB 限制）。
        val json = RenameOperationJson.encode(operations)
        val dbId = pendingDao.insert(PendingRenameBatchEntity(operationsJson = json))

        val data = workDataOf(
            RenameWorker.KEY_SERVER_ID to serverId,
            RenameWorker.KEY_PENDING_BATCH_ID to dbId,
            RenameWorker.KEY_BATCH_NAME to batchName,
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<RenameWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        // P1-7③：unique work（按服务器隔离）+ KEEP：即使查询与入队之间出现并发竞争，
        // 后到者也会被 KEEP 策略丢弃，同一服务器始终只有一个活跃重命名任务。
        wm.enqueueUniqueWork(uniqueName(serverId), ExistingWorkPolicy.KEEP, work)
        return work.id
    }

    /** P1-7③：unique work 名（按服务器隔离，同服务器串行防并发重放）。 */
    private fun uniqueName(serverId: Long): String = "rename_$serverId"

    /**
     * 观察某 work id 的状态/进度（[WorkInfo] 含 state 与 progress WorkData）。
     *
     * WorkManager 2.9.x 的 [WorkManager.getWorkInfoByIdFlow] 返回 [Flow]<[WorkInfo]?>（可空）：
     * WorkInfo 在 work 不存在/被清理时为 null，故元素类型可空。
     */
    fun observeWork(workId: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId)
}
