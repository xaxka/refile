package xa.refile.core.rename

import xa.refile.core.webdav.WebDavClient

/**
 * 批量重命名执行引擎（计划 §M4 Task 4.1，对标 spec SubTask 4.1.1–4.1.4）。
 *
 * 执行流程：
 * 1. 按目标路径深度排序（浅目录先），确保 MKCOL 建目录时父目录已存在；同级按路径字典序。
 * 2. MKCOL 建缺失目录：收集所有目标路径（含伴随文件目标）的祖先目录，去重按深度排序，
 *    逐个 [WebDavClient.mkcol]。mkcol 已把 405（已存在）视为幂等成功，故直接调用即可。
 * 3. 逐个 MOVE 主文件（`Overwrite: F`）。
 * 4. 主文件成功后跟随 MOVE 伴随文件（同名字幕/nfo/图片）。
 * 5. 失败条目记录原因（主文件失败则不处理伴随文件）。
 * 6. 汇总报告，并支持 [retry] 重试失败项。
 *
 * 进度回调 [onProgress] 在每个操作执行后调用（伴随文件批量计为 1 个进度单位）。
 *
 * @param client 已配置好 baseUrl/认证的 WebDAV 客户端。
 */
class RenameExecutor(private val client: WebDavClient) {

    /**
     * 执行批量重命名。
     *
     * @param operations 待执行的操作列表。
     * @param onProgress 进度回调，参数为 (当前序号从 1 起, 总数, 当前操作)。
     * @return 执行报告。
     */
    suspend fun execute(
        operations: List<RenameOperation>,
        onProgress: (current: Int, total: Int, op: RenameOperation) -> Unit = { _, _, _ -> },
    ): RenameReport {
        // 4.1.1 按目标路径深度升序，同级按字典序。
        val sorted = operations.sortedWith(
            compareBy({ pathDepth(it.targetPath) }, { it.targetPath }),
        )
        val total = sorted.size
        val results = mutableListOf<Pair<RenameOperation, RenameResult>>()

        // 4.1.1 MKCOL 建缺失目录（幂等，405 忽略；不中断后续流程）。
        createMissingDirs(sorted)

        // 4.1.2 逐个 MOVE 主文件 + 伴随文件跟随。
        var current = 0
        for (op in sorted) {
            val result = executeSingle(op)
            results.add(op to result)
            current++
            onProgress(current, total, op)
        }

        // 4.1.4 汇总报告。
        val succeeded = results.count {
            it.second is RenameResult.Success || it.second is RenameResult.Partial
        }
        val failed = results.count { it.second is RenameResult.Failed }
        return RenameReport(
            results = results,
            total = total,
            succeeded = succeeded,
            failed = failed,
        )
    }

    /**
     * 对 [report] 中 [RenameResult.Failed] 与 [RenameResult.Partial] 的操作重试（重新 MKCOL+MOVE）。
     *
     * - [RenameResult.Failed]：主文件未落地，整体重试（主文件 + 伴随）。
     * - [RenameResult.Partial]：主文件已成功，仅把失败的伴随文件拆为独立操作重试。
     *
     * @return 仅含重试条目的新报告（原成功条目不包含，由调用方按需合并）。
     */
    suspend fun retry(
        report: RenameReport,
        onProgress: (current: Int, total: Int, op: RenameOperation) -> Unit = { _, _, _ -> },
    ): RenameReport {
        val retryOps = mutableListOf<RenameOperation>()
        for ((op, result) in report.results) {
            when (result) {
                is RenameResult.Failed -> {
                    // 主文件失败，整体重试（主文件 + 伴随）。
                    retryOps.add(op)
                }
                is RenameResult.Partial -> {
                    // 主文件已成功，只重试失败的伴随文件，拆为独立操作。
                    for (comp in op.companions) {
                        if (comp.sourcePath in result.failedCompanions) {
                            retryOps.add(
                                RenameOperation(
                                    sourcePath = comp.sourcePath,
                                    targetPath = comp.targetPath,
                                    companions = emptyList(),
                                    mediaType = op.mediaType,
                                ),
                            )
                        }
                    }
                }
                else -> {
                    // Success / Skipped 不重试。
                }
            }
        }
        return execute(retryOps, onProgress)
    }

    /** 执行单条操作：先 MOVE 主文件，成功后跟随 MOVE 伴随文件。 */
    private suspend fun executeSingle(op: RenameOperation): RenameResult {
        // 源路径与目标路径相同 → 跳过该文件（不调用 MOVE）。
        // WebDAV MOVE 到自身多数服务器返回 403/409，原逻辑会误判为失败；
        // 实际文件名未变化，应视为无需操作（Skipped），不计入成功也不计入失败。
        if (op.sourcePath == op.targetPath) {
            // 主文件已跳过，伴随文件若同样源==目标也一并跳过；
            // 伴随文件源!=目标但主文件未落地（因为本来就没动），按原「主失败不处理伴随」语义跳过全部。
            val allSkipped = op.companions.all { it.sourcePath == it.targetPath }
            return if (allSkipped) {
                RenameResult.Skipped("源路径与目标路径相同: ${op.sourcePath}")
            } else {
                // 主文件无需移动，但存在需要重命名的伴随文件 → 仍执行伴随文件 MOVE。
                moveCompanionsOnly(op)
            }
        }

        // 4.1.2 MOVE 主文件（Overwrite: F）。
        val mainOk = try {
            client.move(op.sourcePath, op.targetPath, overwrite = false)
        } catch (e: Exception) {
            // 4.1.3 网络异常记为整体失败（无 httpCode）。
            return RenameResult.Failed("MOVE 异常: ${op.sourcePath} -> ${op.targetPath}: ${e.message}")
        }
        if (!mainOk) {
            // 4.1.3 主文件失败 → 整体失败，伴随文件不处理。
            return RenameResult.Failed("MOVE 失败: ${op.sourcePath} -> ${op.targetPath}")
        }

        // 4.1.2 伴随文件跟随。
        return moveCompanions(op)
    }

    /**
     * 仅 MOVE 伴随文件（主文件因源==目标已跳过，未实际落地新位置）。
     * 复用 [moveCompanions] 的失败统计逻辑：全部成功 → Success；部分失败 → Partial。
     * 注意：此处返回 Success/Partial 而非 Skipped，因为伴随文件确实执行了重命名。
     */
    private suspend fun moveCompanionsOnly(op: RenameOperation): RenameResult {
        // 主文件已跳过（未移动），伴随文件中源==目标的也跳过，只 MOVE 需要改名的伴随文件。
        val toMove = op.companions.filter { it.sourcePath != it.targetPath }
        if (toMove.isEmpty()) {
            return RenameResult.Skipped("源路径与目标路径相同: ${op.sourcePath}")
        }
        val failedCompanions = mutableListOf<String>()
        for (comp in toMove) {
            val compOk = try {
                client.move(comp.sourcePath, comp.targetPath, overwrite = false)
            } catch (e: Exception) {
                false
            }
            if (!compOk) {
                failedCompanions.add(comp.sourcePath)
            }
        }
        return if (failedCompanions.isEmpty()) {
            RenameResult.Success
        } else {
            RenameResult.Partial(failedCompanions)
        }
    }

    /** 主文件已成功落地后，跟随 MOVE 伴随文件。 */
    private suspend fun moveCompanions(op: RenameOperation): RenameResult {
        if (op.companions.isEmpty()) {
            return RenameResult.Success
        }
        val failedCompanions = mutableListOf<String>()
        for (comp in op.companions) {
            // 伴随文件源==目标 → 跳过（同主文件逻辑），不计入失败。
            if (comp.sourcePath == comp.targetPath) continue
            val compOk = try {
                client.move(comp.sourcePath, comp.targetPath, overwrite = false)
            } catch (e: Exception) {
                false
            }
            if (!compOk) {
                failedCompanions.add(comp.sourcePath)
            }
        }
        // 全部成功 → Success；部分或全部失败 → Partial（主文件已成功）。
        return if (failedCompanions.isEmpty()) {
            RenameResult.Success
        } else {
            RenameResult.Partial(failedCompanions)
        }
    }

    /**
     * 4.1.1 收集所有目标路径（主文件 + 伴随文件）的祖先目录，去重按深度排序后逐个 MKCOL。
     * mkcol 失败仅记录不中断（目标目录可能已存在，后续 MOVE 失败会单独记录）。
     */
    private suspend fun createMissingDirs(operations: List<RenameOperation>) {
        val dirs = linkedSetOf<String>()
        for (op in operations) {
            dirs.addAll(ancestorDirs(op.targetPath))
            for (comp in op.companions) {
                dirs.addAll(ancestorDirs(comp.targetPath))
            }
        }
        val sorted = dirs.sortedWith(compareBy({ pathDepth(it) }, { it }))
        for (dir in sorted) {
            try {
                client.mkcol(dir)
            } catch (e: Exception) {
                // 忽略：目标目录可能已存在，后续 MOVE 失败会单独记录。
            }
        }
    }

    /** 路径深度：以 `/` 分隔的非空段数。如 `/a/b.mkv` → 2，`/` → 0。 */
    private fun pathDepth(path: String): Int =
        path.split('/').count { it.isNotEmpty() }

    /** 取目标路径的所有祖先目录（不含根 `/`，不含文件本身）。如 `/a/b/c.mkv` → [`/a`, `/a/b`]。 */
    private fun ancestorDirs(path: String): List<String> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return emptyList()
        val dirs = mutableListOf<String>()
        val sb = StringBuilder()
        for (i in 0 until segments.size - 1) {
            sb.append('/').append(segments[i])
            dirs.add(sb.toString())
        }
        return dirs
    }
}
