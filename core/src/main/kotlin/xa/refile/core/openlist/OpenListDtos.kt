package xa.refile.core.openlist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenList（AList 兼容）API DTO（对标 https://fox.oplist.org/364155678e0.md 登录规范 + fs 端点）。
 *
 * 字段可空并附默认值，配合 `Json { ignoreUnknownKeys = true; coerceInputValues = true }`
 * 容忍服务器缺字段或新增字段。snake_case 通过 [@SerialName] 映射。
 */

/**
 * 登录请求（规范 §/api/auth/login requestBody）。
 *
 * @property username  用户名（必填）。
 * @property password  密码（必填）。
 * @property otpCode   两步验证码（启用 2FA 时必填，对应规范 `otp_code`）。
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("otp_code") val otpCode: String? = null,
)

/**
 * 统一响应外壳（规范 components/ApiResponse）。
 *
 * OpenList 实际行为：HTTP 始终 200，由 [code] 字段标识成功/失败；规范将登录失败标为 HTTP 400，
 * 本客户端对两种情形均兼容（见 [OpenListClient] 的 HTTP 状态 + [code] 双重判定）。
 *
 * @property code    业务状态码（200 成功；401 未授权；其它为错误）。
 * @property message 响应消息。
 * @property data    负载，结构随端点而异；无负载时为 null。
 */
@Serializable
data class OpenListResponse<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null,
)

/** 登录成功负载（规范 LoginResponse.data）。 */
@Serializable
data class LoginData(
    val token: String? = null,
)

/**
 * `/api/fs/list` 请求。
 *
 * @property path     要列出的目录绝对路径。
 * @property password 目录访问密码（受保护目录用），可空。
 * @property page     页码，从 1 起。
 * @property perPage  每页条数；0 表示返回全部（OpenList 约定）。
 * @property refresh  是否强制刷新缓存。
 */
@Serializable
data class FsListRequest(
    val path: String,
    val password: String? = null,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    val refresh: Boolean = false,
)

/** `/api/fs/list` 成功负载。 */
@Serializable
data class FsListData(
    val content: List<OpenListFile>? = null,
    val total: Int? = null,
    val readme: String? = null,
    val write: Boolean? = null,
    val provider: String? = null,
)

/**
 * `/api/fs/list` 单条文件/目录信息。
 *
 * @property name     名称。
 * @property size     字节长度。
 * @property isDir    是否目录（`is_dir`）。
 * @property modified 最后修改时间（服务器原始字符串）。
 * @property created  创建时间。
 * @property sign     签名（受保护/直链用）。
 * @property thumb    缩略图 URL。
 * @property type     类型枚举（OpenList 内部用）。
 */
@Serializable
data class OpenListFile(
    val name: String = "",
    val size: Long? = null,
    @SerialName("is_dir") val isDir: Boolean? = null,
    val modified: String? = null,
    val created: String? = null,
    val sign: String? = null,
    val thumb: String? = null,
    val type: Int? = null,
)

/** `/api/fs/mkdir` 请求：`{path}`。 */
@Serializable
data class FsMkdirRequest(
    val path: String,
)

/** `/api/fs/rename` 请求：在同目录内重命名，`path` 为源完整路径，`name` 为新基名。 */
@Serializable
data class FsRenameRequest(
    val path: String,
    val name: String,
)

/** `/api/fs/move` 请求：跨目录移动（保留原基名），`names` 批量。 */
@Serializable
data class FsMoveRequest(
    @SerialName("src_dir") val srcDir: String,
    @SerialName("dst_dir") val dstDir: String,
    val names: List<String>,
)

/**
 * 无具体负载的操作响应（mkdir/rename/move）。
 *
 * 这些端点成功时 `data` 通常为 null 或空对象，用 [JsonElement] 容纳任意 JSON 值，
 * 调用方仅依据 [OpenListResponse.code] 判定成败，不解析 [OpenListResponse.data]。
 */
internal typealias FsOpData = JsonElement
