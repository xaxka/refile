package xa.refile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 待执行重命名批次 DAO。
 *
 * - [insert]：调度器入队时插入操作 JSON，返回 id 供 WorkData 传递。
 * - [getById]：Worker 启动时按 id 读取操作 JSON。
 * - [deleteById]：Worker 执行完成后删除本条记录，避免表膨胀。
 */
@Dao
interface PendingRenameBatchDao {

    @Insert
    suspend fun insert(entity: PendingRenameBatchEntity): Long

    @Query("SELECT * FROM pending_rename_batches WHERE id = :id")
    suspend fun getById(id: Long): PendingRenameBatchEntity?

    @Query("DELETE FROM pending_rename_batches WHERE id = :id")
    suspend fun deleteById(id: Long)
}
