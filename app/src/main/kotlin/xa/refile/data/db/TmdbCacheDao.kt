package xa.refile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * TMDB 响应缓存 DAO（Task 2.3.4）。
 *
 * 仅做单表 KV 式 CRUD：按业务键 [getByKey] 命中、[insert] 覆盖写入（同键 REPLACE）、
 * [deleteOlderThan] 清理过期、[clearAll] 全清。命中后的过期判定由仓库层按 `cachedAt` 计算。
 */
@Dao
interface TmdbCacheDao {

    /** 按业务键取单条缓存（最多一条，唯一索引保证）。 */
    @Query("SELECT * FROM tmdb_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getByKey(key: String): TmdbCacheEntity?

    /** 写入/覆盖缓存（同 cacheKey 由唯一索引 + REPLACE 覆盖）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TmdbCacheEntity)

    /** 删除早于 [before]（epoch millis）的缓存，用于过期清理。 */
    @Query("DELETE FROM tmdb_cache WHERE cachedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 清空全部缓存（设置页"清除 TMDB 缓存"调用）。 */
    @Query("DELETE FROM tmdb_cache")
    suspend fun clearAll()
}
