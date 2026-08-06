package xa.refile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TMDB 响应缓存实体（Task 2.3.4）。
 *
 * TMDB API 有 40 req/10s 限流，详情类请求（movie/tv detail、season、episode group）
 * 内容稳定且会重复访问（批量匹配多集同剧集、Edit Match 反复切季），缓存可显著减少重复请求。
 *
 * 复合业务键 [cacheKey] 唯一（`"{mediaType}:{tmdbId}:{language}[:{season}]"`），
 * 自增 [id] 仅作 Room 主键。命中时由调用方按 [cachedAt] 判断是否过期（默认 7 天）。
 *
 * @property id            自增主键。
 * @property cacheKey      复合业务键，如 `MOVIE:123:zh-CN` / `SEASON:456:1:zh-CN`。
 * @property mediaType     缓存类别：`MOVIE`/`TV`/`SEASON`/`EPISODE_GROUP`。
 * @property tmdbId        TMDB 资源 id（剧集季缓存用 tvId）。
 * @property language      请求语言（影响翻译/标题，纳入键）。
 * @property seasonNumber  季号（仅 SEASON 类别使用）。
 * @property responseJson  完整 JSON 响应（序列化后的 DTO/MediaMetadata 快照）。
 * @property cachedAt      缓存写入时间戳（epoch millis），用于过期判定。
 */
@Entity(
    tableName = "tmdb_cache",
    indices = [Index(value = ["cacheKey"], unique = true)],
)
data class TmdbCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cacheKey: String,
    val mediaType: String,
    val tmdbId: Int,
    val language: String,
    val seasonNumber: Int? = null,
    val responseJson: String,
    val cachedAt: Long,
)
