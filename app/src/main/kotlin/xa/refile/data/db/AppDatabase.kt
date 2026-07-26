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
 * 用 [fallbackToDestructiveMigration]（在 [xa.refile.data.DatabaseModule] 内配置）
 * 简化开发期迁移，bump version 后旧库直接重建。
 *
 * `exportSchema = false`：开发期简化，不导出 schema JSON。
 */
@Database(
    entities = [
        ServerConfigEntity::class,
        RenameBatchEntity::class,
        RenameEntryEntity::class,
        TmdbCacheEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun serverConfigDao(): ServerConfigDao

    /** 重命名批次/条目 DAO（Task 5.1.1）。 */
    abstract fun renameBatchDao(): RenameBatchDao

    /** TMDB 响应缓存 DAO（Task 2.3.4）。 */
    abstract fun tmdbCacheDao(): TmdbCacheDao
}
