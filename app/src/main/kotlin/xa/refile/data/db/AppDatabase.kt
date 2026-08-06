package xa.refile.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用主数据库（计划 §M1 SubTask 1.3.1 / §M5 SubTask 5.1.1）。
 *
 * 当前包含 [ServerConfigEntity]、[RenameBatchEntity]、[RenameEntryEntity] 与 [TmdbCacheEntity]。
 *
 * v2 变更（Task 5.1.1）：新增 rename_batches / rename_entries 两表。
 * v3 变更（Task 2.3.4）：新增 tmdb_cache 表（TMDB 详情响应缓存，减少限流压力）。
 * v4 变更：server_configs 新增 type 列（webdav / openlist），支持 OpenList 后端。
 * v5 变更：新增 pending_rename_batches 表，存储待执行操作 JSON（绕过 WorkData 10KB 上限）。
 *
 * 迁移策略（Task 19）：正式 [androidx.room.migration.Migration]（v1→v2、v2→v3）注册在
 * [xa.refile.data.DatabaseModule]；release 走正式迁移不再清空用户数据，仅 debug 保留
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration] 作为开发期改 schema 兜底。
 *
 * `exportSchema = true`：导出 schema JSON 到 app/schemas/，提交 VCS 便于迁移测试与 CI 校验。
 */
@Database(
    entities = [
        ServerConfigEntity::class,
        RenameBatchEntity::class,
        RenameEntryEntity::class,
        TmdbCacheEntity::class,
        PendingRenameBatchEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun serverConfigDao(): ServerConfigDao

    /** 重命名批次/条目 DAO（Task 5.1.1）。 */
    abstract fun renameBatchDao(): RenameBatchDao

    /** TMDB 响应缓存 DAO（Task 2.3.4）。 */
    abstract fun tmdbCacheDao(): TmdbCacheDao

    /** 待执行重命名批次 DAO（绕过 WorkData 10KB 上限）。 */
    abstract fun pendingRenameBatchDao(): PendingRenameBatchDao
}
