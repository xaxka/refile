package xa.refile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WebDAV 服务器配置实体（计划 §M1 SubTask 1.3.1）。
 *
 * 密码字段 [encryptedPassword] 存储经 Android Keystore 加密后的 base64 密文，
 * 禁止明文落盘（红线）。加密/解密由 [xa.refile.data.crypto.KeystoreCrypto] 完成。
 *
 * @property id                自增主键。
 * @property name              别名（UI 展示）。
 * @property baseUrl           scheme + host，如 `https://dav.example.com`。
 * @property port              端口（可空，用默认 80/443）。
 * @property rootPath          根路径，默认 `/`。
 * @property username          用户名（可空，匿名访问）。
 * @property encryptedPassword Keystore 加密后的密文（base64），明文密码绝不落盘。
 * @property authType          认证类型：auto/basic/digest（auto=自动协商）。
 * @property https             是否启用 HTTPS。
 * @property createdAt         创建时间戳（毫秒）。
 * @property updatedAt         更新时间戳（毫秒）。
 */
@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val port: Int? = null,
    val rootPath: String = "/",
    val username: String? = null,
    val encryptedPassword: String? = null,
    val authType: String = "auto",
    val https: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
