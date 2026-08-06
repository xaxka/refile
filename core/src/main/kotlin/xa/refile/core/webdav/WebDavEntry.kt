package xa.refile.core.webdav

/**
 * WebDAV PROPFIND 单条资源信息（计划 §M1 SubTask 1.2.2）。
 *
 * 字段来源于 PROPFIND multistatus 响应，由 [WebDavClient] 解析得到。
 * 字段可空——服务器可能未返回某属性，缺失即容错为 null。
 *
 * @property href          请求路径（D:href），如 `/dav/Movies/`。重命名目标拼装依据。
 * @property displayName   展示名（D:displayname），对应 FileContext.displayName。
 * @property isCollection  是否目录（D:resourcetype 内存在 D:collection）。
 * @property contentLength 字节长度（D:getcontentlength），对应 FileContext.contentLength。
 * @property lastModified  最后修改时间 RFC1123 字符串（D:getlastmodified），对应 FileContext.lastModified。
 * @property creationDate  创建时间（D:creationdate）。
 * @property contentType   MIME 类型（D:getcontenttype）。
 */
data class WebDavEntry(
    val href: String,
    val displayName: String? = null,
    val isCollection: Boolean = false,
    val contentLength: Long? = null,
    val lastModified: String? = null,
    val creationDate: String? = null,
    val contentType: String? = null,
) {
    /** 从 displayName 提取扩展名（最后一部分 `.` 后，小写）。无扩展名或目录返回 null。 */
    fun extractExtension(): String? {
        if (isCollection) return null
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
