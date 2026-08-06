package xa.refile.core.openlist

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * OpenList（AList 兼容）Retrofit 服务。
 *
 * - 登录端点对标规范 `/api/auth/login`（security 为空，无需 token）。
 * - fs 端点为 OpenList/AList 约定：`/api/fs/{list,mkdir,rename,move}`。
 *
 * 认证由 [OpenListAuthInterceptor] 统一注入：除 login 外的请求在持有 token 时自动附加
 * `Authorization: <token>` 头（规范 BearerAuth：token 直接放置，不带 `Bearer` 前缀）。
 *
 * 所有方法返回 `Response<OpenListResponse<T>>`，以便客户端对 HTTP 状态码（规范 400）与
 * 业务 `code` 字段（OpenList 实际用 200+code）做双重判定。
 */
internal interface OpenListApi {

    /** 用户登录（规范 §/api/auth/login），返回 JWT token。 */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<OpenListResponse<LoginData>>

    /** 列目录。 */
    @POST("api/fs/list")
    suspend fun list(@Body body: FsListRequest): Response<OpenListResponse<FsListData>>

    /** 建目录。 */
    @POST("api/fs/mkdir")
    suspend fun mkdir(@Body body: FsMkdirRequest): Response<OpenListResponse<FsOpData>>

    /** 同目录内重命名（path=源完整路径，name=新基名）。 */
    @POST("api/fs/rename")
    suspend fun rename(@Body body: FsRenameRequest): Response<OpenListResponse<FsOpData>>

    /** 跨目录移动（保留原基名，批量）。 */
    @POST("api/fs/move")
    suspend fun move(@Body body: FsMoveRequest): Response<OpenListResponse<FsOpData>>
}
