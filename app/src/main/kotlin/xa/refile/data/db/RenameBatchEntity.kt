package xa.refile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 重命名批次实体（计划 §M5 SubTask 5.1.1）。
 *
 * 一次批量重命名（[xa.refile.worker.RenameWorker] 执行完成）对应一条批次记录，
 * 其下挂多条 [RenameEntryEntity]（每条对应一个 [xa.refile.core.rename.RenameOperation]）。
 *
 * 字段冗余 [serverName] / [serverBaseUrl] 快照：避免服务器配置被删除后历史列表丢失
 * 可读名称；baseUrl 快照供撤销前做服务器指纹二次校验（P0-3，审查报告 2026-09-25：
 * 防止备份恢复/服务器改动后把批次反向 MOVE 到错误服务器）。
 *
 * @property id               自增主键。
 * @property serverId         关联服务器配置 id（服务器可能已被删除）。
 * @property serverName        服务器名快照（创建批次时的名称）。
 * @property serverBaseUrl    服务器 baseUrl 快照（创建批次时的值）。v5 及更早历史行为
 *                            空串，撤销时空串跳过指纹校验（仅新批次受保护）。
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
    @ColumnInfo(defaultValue = "") val serverBaseUrl: String = "",
    val batchName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val totalOperations: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val isReverted: Boolean = false,
    val revertedAt: Long? = null,
)
