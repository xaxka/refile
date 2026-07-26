package xa.refile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 重命名批次 DAO（计划 §M5 SubTask 5.1.1）。
 *
 * - [insertBatchWithEntries] 在单事务内插入批次与其下所有条目，保证历史一致性。
 * - [observeBatches] 按 createdAt 倒序供历史页实时刷新。
 * - [markReverted] 撤销完成后更新 isReverted + revertedAt。
 */
@Dao
interface RenameBatchDao {

    /** 观察所有批次（按 createdAt 倒序，最新在前）。 */
    @Query("SELECT * FROM rename_batches ORDER BY createdAt DESC")
    fun observeBatches(): Flow<List<RenameBatchEntity>>

    /** 按 id 取单条批次。 */
    @Query("SELECT * FROM rename_batches WHERE id = :id")
    suspend fun getBatch(id: Long): RenameBatchEntity?

    /** 取某批次下所有条目（按 id 升序，与执行顺序一致；撤销时由调用方倒序处理）。 */
    @Query("SELECT * FROM rename_entries WHERE batchId = :batchId ORDER BY id ASC")
    suspend fun getEntries(batchId: Long): List<RenameEntryEntity>

    /** 插入批次。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: RenameBatchEntity): Long

    /** 插入条目列表。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<RenameEntryEntity>)

    /**
     * 在单事务内插入批次与条目：先插批次拿到 id，再把 batchId 回填到每条 entry 后批量插入。
     */
    @Transaction
    suspend fun insertBatchWithEntries(
        batch: RenameBatchEntity,
        entries: List<RenameEntryEntity>,
    ): Long {
        val batchId = insertBatch(batch)
        if (entries.isNotEmpty()) {
            insertEntries(entries.map { it.copy(batchId = batchId) })
        }
        return batchId
    }

    /** 标记批次已撤销：isReverted=true 并记录撤销时间。 */
    @Query("UPDATE rename_batches SET isReverted = 1, revertedAt = :revertedAt WHERE id = :id")
    suspend fun markReverted(id: Long, revertedAt: Long)

    /** 删除某批次（外键 CASCADE 会连带删除其下条目）。 */
    @Query("DELETE FROM rename_batches WHERE id = :id")
    suspend fun deleteBatch(id: Long)

    /** 统计当前批次总数。 */
    @Query("SELECT COUNT(*) FROM rename_batches")
    suspend fun countBatches(): Int

    /**
     * 仅保留最新 [keep] 条批次（按 createdAt 倒序），删除其余较旧批次。
     *
     * 用子查询取出要保留的 id 集合，删除不在该集合内的批次。外键 CASCADE 连带删除其下条目。
     */
    @Query(
        """
        DELETE FROM rename_batches
        WHERE id NOT IN (
            SELECT id FROM rename_batches ORDER BY createdAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun deleteOldestBeyond(keep: Int)

    /** 删除创建时间早于 [thresholdMillis] 的所有批次。 */
    @Query("DELETE FROM rename_batches WHERE createdAt < :thresholdMillis")
    suspend fun deleteOlderThan(thresholdMillis: Long)
}
