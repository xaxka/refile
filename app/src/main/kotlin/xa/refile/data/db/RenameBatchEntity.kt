package xa.refile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 重命名批次实体（计划 §M5 SubTask 5.1.1）。
 *
 * 一次批量重命名（[xa.refile.worker.RenameWorker] 执行完成）对应一条批次记录，
 * 其下挂多条 [RenameEntryEntity]（每条对应一个 [xa.refile.core.rename.RenameOperation]）。
 *
 * 字段冗余 [serverName] 快照：避免服务器配置被删除后历史列表丢失可读名称。
 *
 * @property id               自增主键。
 * @property serverId         关联服务器配置 id（服务器可能已被删除）。
 * @property serverName        服务器名快照（创建批次时的名称）。
 * @property batchName        用户可读批次名（与通知标题一致）。
 * @property createdAt        创建时间戳（毫秒）。
 * @property totalOperations  操作总数（= entries 条数）。
 * @property succeededCount    成功数（[xa.refile.core.rename.RenameResult.Success] + [xa.refile.core.rename.RenameResult.Partial]）。
 * @property failedCount       失败数（[xa.refile.core.rename.RenameResult.Failed]）。
 * @property isReverted       是否已整批撤销（撤销后置 true，UI 置灰且禁用撤销按钮）。
 * @property revertedAt       撤销时间戳（毫秒，可空）。
 */
@Entity(tableName = "rename_batches")
data class RenameBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val serverName: String,
    val batchName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val totalOperations: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val isReverted: Boolean = false,
    val revertedAt: Long? = null,
)
