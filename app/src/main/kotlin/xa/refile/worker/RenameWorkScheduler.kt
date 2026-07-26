package xa.refile.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import xa.refile.core.rename.RenameOperation
import xa.refile.core.rename.RenameOperationJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

/**
 * 重命名任务入队辅助（计划 §M4 Task 4.2.2）。
 *
 * 把 [List]<[RenameOperation]> 经 [RenameOperationJson] 序列化后塞入 WorkData，
 * 构造 [Constraints]（需联网，与 WebDAV MOVE/MKCOL 一致），
 * 通过 [WorkManager] 入队一次性 [RenameWorker]，返回 work.id 供 UI 观察进度。
 *
 * WorkData 键常量复用 [RenameWorker] 的 companion，保证入参与读取一致。
 */
class RenameWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 入队一批重命名任务。
     *
     * @param serverId   目标服务器配置 id。
     * @param operations 待执行的重命名操作列表（将序列化为 JSON 存入 WorkData）。
     * @param batchName  批次名（可选，用于通知标题展示）。
     * @return WorkRequest 的 [UUID]，供 [observeWork] 观察进度/状态。
     */
    fun enqueue(
        serverId: Long,
        operations: List<RenameOperation>,
        batchName: String? = null,
    ): UUID {
        val data = workDataOf(
            RenameWorker.KEY_SERVER_ID to serverId,
            RenameWorker.KEY_OPERATIONS_JSON to RenameOperationJson.encode(operations),
            RenameWorker.KEY_BATCH_NAME to batchName,
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<RenameWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueue(work)
        return work.id
    }

    /**
     * 观察某 work id 的状态/进度（[WorkInfo] 含 state 与 progress WorkData）。
     *
     * WorkManager 2.9.x 的 [WorkManager.getWorkInfoByIdFlow] 返回 [Flow]<[WorkInfo]?>（可空）：
     * WorkInfo 在 work 不存在/被清理时为 null，故元素类型可空。
     */
    fun observeWork(workId: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId)
}
