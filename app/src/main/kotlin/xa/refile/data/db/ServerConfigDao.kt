package xa.refile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 服务器配置 DAO（计划 §M1 SubTask 1.3.1）。
 *
 * 仅提供本地 CRUD 与 Flow 观察；不在此处处理加解密（由 Repository 层负责）。
 */
@Dao
interface ServerConfigDao {

    @Query("SELECT * FROM server_configs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs WHERE id = :id")
    suspend fun getById(id: Long): ServerConfigEntity?

    @Insert
    suspend fun insert(server: ServerConfigEntity): Long

    @Update
    suspend fun update(server: ServerConfigEntity)

    @Delete
    suspend fun delete(server: ServerConfigEntity)

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
