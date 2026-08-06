package xa.refile.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 正式迁移集合（Task 19）。
 *
 * 背景：[AppDatabase] 历史版本
 * - v1：仅 server_configs 表。
 * - v2（Task 5.1.1）：新增 rename_batches / rename_entries 两表。
 * - v3（Task 2.3.4）：新增 tmdb_cache 表。
 *
 * 此前 release 缺失迁移路径（仅 debug 用 [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]
 * 兜底），版本升级时 release 会抛 IllegalStateException 或被迫清空用户数据。这里提供 v1→v2、v2→v3
 * 的正式 [Migration]，由 [xa.refile.data.DatabaseModule] 经 `addMigrations` 注册。
 *
 * SQL 须与 Room 按 Entity 编译期生成的表结构完全一致（列名/类型/约束/索引），否则 Room 会在运行期
 * 校验 schema 时抛 `IllegalStateException: Migration didn't properly handle: ...`。要点：
 * - Boolean 列存为 `INTEGER NOT NULL`（Room 不为 Kotlin 默认值生成 SQL DEFAULT）。
 * - `@PrimaryKey(autoGenerate=true)` 对应 `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`。
 * - `@Index` / `@Index(unique=true)` 需在迁移里显式 CREATE INDEX（Room 命名为 `index_<表>_<列>`）。
 * - 外键约束随 CREATE TABLE 内联声明。
 *
 * 新增表（v2/v3）均为「新建表」，无历史数据搬迁；若后续做「改列」类迁移需走 ALTER + 临时表回填。
 */
object Migrations {

    /**
     * v1 → v2：新增 rename_batches、rename_entries 两表（Task 5.1.1）。
     *
     * rename_entries.batchId 外键引用 rename_batches.id，ON DELETE CASCADE（删批次自动删条目）；
     * 并建 batchId 普通索引（对应 [RenameEntryEntity] 的 `@Index("batchId")`）。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rename_batches` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `serverId` INTEGER NOT NULL,
                    `serverName` TEXT NOT NULL,
                    `batchName` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `totalOperations` INTEGER NOT NULL,
                    `succeededCount` INTEGER NOT NULL,
                    `failedCount` INTEGER NOT NULL,
                    `isReverted` INTEGER NOT NULL,
                    `revertedAt` INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rename_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `batchId` INTEGER NOT NULL,
                    `sourcePath` TEXT NOT NULL,
                    `targetPath` TEXT NOT NULL,
                    `mediaType` TEXT NOT NULL,
                    `companionsJson` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `errorMessage` TEXT,
                    FOREIGN KEY(`batchId`) REFERENCES `rename_batches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            // 对应 RenameEntryEntity 的 @Index("batchId")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_rename_entries_batchId` ON `rename_entries` (`batchId`)")
        }
    }

    /**
     * v2 → v3：新增 tmdb_cache 表（Task 2.3.4，TMDB 详情响应缓存）。
     *
     * cacheKey 建唯一索引（对应 [TmdbCacheEntity] 的 `@Index(value = ["cacheKey"], unique = true)`）。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `tmdb_cache` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `cacheKey` TEXT NOT NULL,
                    `mediaType` TEXT NOT NULL,
                    `tmdbId` INTEGER NOT NULL,
                    `language` TEXT NOT NULL,
                    `seasonNumber` INTEGER,
                    `responseJson` TEXT NOT NULL,
                    `cachedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tmdb_cache_cacheKey` ON `tmdb_cache` (`cacheKey`)")
        }
    }

    /**
     * v3 → v4：server_configs 新增 type 列（webdav / openlist）。
     *
     * 为已有行填充默认值 `webdav`（对齐 [ServerConfigEntity.type] 的 Kotlin 默认值与
     * `@ColumnInfo(defaultValue = "'webdav'")`）。Room schema 校验要求迁移后的列定义
     * 与 Entity 编译期生成的 CREATE TABLE 完全一致（含 DEFAULT）。
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `server_configs` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'webdav'",
            )
        }
    }

    /**
     * v4 → v5：新增 pending_rename_batches 表。
     *
     * 用于存储待执行的重命名操作 JSON，绕过 WorkData 10KB 序列化上限。
     * 表结构对应 [PendingRenameBatchEntity]。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_rename_batches` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `operationsJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /** 全部已注册迁移，供 [xa.refile.data.DatabaseModule] 一次性 addMigrations。 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
