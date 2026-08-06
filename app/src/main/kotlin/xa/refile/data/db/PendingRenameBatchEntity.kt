package xa.refile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待执行重命名批次实体。
 *
 * 用于绕过 WorkData 10KB 序列化上限：[RenameWorkScheduler] 把操作列表 JSON 存入此表，
 * 仅将 [id] 传入 WorkData；[xa.refile.worker.RenameWorker] 通过 id 从数据库读取操作列表，
 * 执行完成后删除本条记录。
 *
 * @property id              自增主键，同时作为 WorkData 传递的 batchId。
 * @property operationsJson  [xa.refile.core.rename.RenameOperation] 列表的 JSON 序列化。
 * @property createdAt       创建时间戳（毫秒），用于清理可能遗留的孤儿记录。
 */
@Entity(tableName = "pending_rename_batches")
data class PendingRenameBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
