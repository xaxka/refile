package xa.refile.core.webdav

/**
 * 统一文件操作客户端契约（计划 §M1 增强：WebDAV + OpenList 双后端）。
 *
 * WebDAV 与 OpenList 两种后端均实现此接口，使重命名流程（[xa.refile.core.rename.RenameExecutor]
 * 等）与上层仓库可按服务器类型替换具体实现，而无需感知底层协议差异。
 *
 * 契约对齐 [WebDavClient] 既有方法语义：
 * - [propfind]：列目录。Depth 0 仅返回资源自身；Depth 1 返回自身 + 直接子项。
 *   非 2xx/失败抛异常（OpenList 抛 [OpenListException]），调用方可据异常区分「读取失败」与「空目录」。
 * - [move]：重命名/移动。成功返回 true，失败返回 false（不抛异常，与 [WebDavClient.move] 一致）。
 * - [mkcol]：建目录，幂等（已存在视为成功）。
 * - [testConnection]：连通性 + 认证探测，返回结构化 [ConnectionResult]，不读取文件内容（红线）。
 *
 * 共享模型 [WebDavEntry] / [ConnectionResult] 定义于本包，两种后端共用。
 */
interface FileClient {
    /** 列目录：Depth 0 返回自身；Depth 1 返回自身 + 直接子项。失败抛异常。 */
    suspend fun propfind(path: String, depth: Int): List<WebDavEntry>

    /** 重命名/移动资源。成功 true，失败 false（不抛异常）。 */
    suspend fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean

    /** 建目录（幂等）。成功 true，失败 false。 */
    suspend fun mkcol(path: String): Boolean

    /** 连通性 + 认证探测，返回结构化结果。 */
    suspend fun testConnection(path: String): ConnectionResult
}
