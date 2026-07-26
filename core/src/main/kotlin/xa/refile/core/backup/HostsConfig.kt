package xa.refile.core.backup

import kotlinx.serialization.Serializable

/**
 * 自定义 Hosts 数据模型（spec §5.3 自定义 Hosts）。
 *
 * 用于让 TMDB 域名在中国大陆可直连（DNS 污染规避）：把域名静态映射到候选 IP 列表，
 * 由 [HostsDns] 在 OkHttp 解析阶段返回，TLS SNI/证书校验仍由 OkHttp 基于请求 URL 的
 * hostname 自动处理。
 */

/**
 * 一个域名对应多个候选 IP。
 *
 * @param hostname 域名（如 `api.themoviedb.org`），匹配时忽略大小写。
 * @param ips 候选 IP 字面量列表，[HostsDns] 会按顺序轮询、失败自动切换下一个。
 */
@Serializable
data class HostEntry(
    val hostname: String,
    val ips: List<String>,
)

/**
 * hosts 总开关 + 全部条目。
 *
 * @param enabled 总开关，关闭后 [HostsDns] 全部走系统 DNS。
 * @param entries 全部域名→IP 条目。
 */
@Serializable
data class HostsConfig(
    val enabled: Boolean = true,
    val entries: List<HostEntry> = emptyList(),
)

/**
 * 预设候选域名常量（spec §5.3.2）。
 *
 * TMDB 在中国大陆被 DNS 污染，这两个域名是预设的直连候选，UI 测速环节会基于此列表
 * 让用户挑选可用 IP。
 */
object HostPresets {
    /** TMDB API 域名。 */
    const val TMDB_API = "api.themoviedb.org"

    /** TMDB 图片 CDN 域名。 */
    const val TMDB_IMAGE = "image.tmdb.org"

    /** 默认候选域名列表。 */
    val DEFAULT_CANDIDATES = listOf(TMDB_API, TMDB_IMAGE)
}
