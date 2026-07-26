package xa.refile.core.model

import kotlinx.serialization.Serializable

/**
 * 媒体类型：电影 或 剧集。
 */
@Serializable
enum class MediaType { MOVIE, EPISODE }
