package xa.refile.core.rename

import kotlinx.coroutines.delay
import xa.refile.core.webdav.WebDavClient

/**
 * 执行阶段的冲突处理策略（计划 §M4 Task 4.1 增强）。
 *
 * 预览阶段已有冲突检测（[xa.refile.ui.preview.PreviewViewModel]），但预览后到执行期间
 * 服务器可能新增同名文件，导致 MOVE（`Overwrite: F`）直接失败。该枚举让用户在执行阶段
 * 也能处理预览后新出现的冲突，不会因单文件冲突中断整批操作。
 *
 * - [SKIP]：预检测目标父目录，目标已存在则跳过该文件（记 [RenameResult.Skipped]），继续后续。
 * - [FAIL]：默认策略。不主动预检测；MOVE `Overwrite: F`，目标已存在时 MOVE 返回失败 →
 *   记 [RenameResult.Failed]，继续后续（与未引入策略前的行为一致，向后兼容）。
 * - [INDEX]：预检测；目标已存在则在文件名扩展名前插入 ` (n)`（n 从 1 递增）直到不冲突，
 *   同步更新伴随文件目标名以保持主/伴随同名基。
 * - [OVERWRITE]：不预检测；MOVE 不带 `Overwrite: F`（交由服务器覆盖目标）。
 */
enum class ConflictStrategy {
    SKIP,
    FAIL,
    INDEX,
    OVERWRITE,
}

/**
 * 批量重命名执行引擎（计划 §M4 Task 4.1，对标 spec SubTask 4.1.1–4.1.4）。
 *
 * 执行流程：
 * 1. 按目标路径深度排序（浅目录先），确保 MKCOL 建目录时父目录已存在；同级按路径字典序。
 * 2. MKCOL 建缺失目录：收集所有目标路径（含伴随文件目标）的祖先目录，去重按深度排序，
 *    逐个 [WebDavClient.mkcol]。mkcol 已把 405（已存在）视为幂等成功，故直接调用即可。
 * 3. 冲突预检测（仅 [ConflictStrategy.SKIP]/[ConflictStrategy.INDEX]）：对目标父目录发
 *    PROPFIND Depth 1，收集已存在文件名；用于跳过或自动加序号。
 * 4. 逐个 MOVE 主文件，按 [ConflictStrategy] 决定是否覆盖；MOVE 失败自动重试 + 指数退避
 *    （[moveWithRetry]）。
 * 5. 主文件成功后跟随 MOVE 伴随文件（同名字幕/nfo/图片）。
 * 6. 失败条目记录原因（主文件失败则不处理伴随文件）。
 * 7. 汇总报告，并支持 [retry] 重试失败项。
 *
 * 进度回调 [onProgress] 在每个操作执行后调用（伴随文件批量计为 1 个进度单位）。
 *
 * 重试参数 [maxRetries]/[initialDelayMs] 通过构造函数注入：生产环境默认 3 次重试 + 1s 起步
 * 指数退避（参考 tmm `Utils.java` 5 次重试 + 1s sleep、FB-Mod 指数退避 5s→10s→20s）；
 * 单测可传 `maxRetries = 0` 关闭重试以保持单次请求语义。
 *
 * @param client          已配置好 baseUrl/认证的 WebDAV 客户端。
 * @param maxRetries      单次 MOVE 失败后的最大重试次数（不含首次），0 表示不重试。
 * @param initialDelayMs  首次重试前等待毫秒，每次重试后翻倍（指数退避）。
 * @param trashDir        回收站目录（相对路径，如 `.trash`），供 [safeDelete] 使用；
 *                        移动到此处而非物理删除，参考 tmm `deleteFileWithBackup`。
 */
class RenameExecutor(
    private val client: WebDavClient,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val trashDir: String = DEFAULT_TRASH_DIR,
) {

    /**
     * 执行批量重命名。
     *
     * @param operations       待执行的操作列表。
     * @param conflictStrategy 执行阶段冲突处理策略，默认 [ConflictStrategy.FAIL]。
     * @param onProgress       进度回调，参数为 (当前序号从 1 起, 总数, 当前操作)；
     *                         置于末位以支持尾随 lambda 调用形式。
     * @return 执行报告。
     */
    suspend fun execute(
        operations: List<RenameOperation>,
        conflictStrategy: ConflictStrategy = ConflictStrategy.FAIL,
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

        // 冲突预检测：仅 SKIP/INDEX 需要知晓目标是否已存在；FAIL/OVERWRITE 不预检测
        // （FAIL 让 MOVE 自然失败；OVERWRITE 直接覆盖），从而保持与旧行为一致的请求序列。
        val conflictCtx = if (
            conflictStrategy == ConflictStrategy.SKIP ||
            conflictStrategy == ConflictStrategy.INDEX
        ) {
            buildConflictContext(sorted)
        } else {
            null
        }

        // 4.1.2 逐个 MOVE 主文件 + 伴随文件跟随。
        var current = 0
        for (op in sorted) {
            var effective = op
            val overwrite = conflictStrategy == ConflictStrategy.OVERWRITE

            // 冲突处理（仅当目标与源不同路径时才判定，源==目标不算冲突）。
            if (conflictCtx != null && effective.targetPath != effective.sourcePath) {
                when (conflictStrategy) {
                    ConflictStrategy.SKIP -> {
                        if (conflictCtx.exists(effective.targetPath)) {
                            val skipped = RenameResult.Skipped("目标已存在，跳过: ${effective.targetPath}")
                            results.add(effective to skipped)
                            current++
                            onProgress(current, total, effective)
                            continue
                        }
                    }
                    ConflictStrategy.INDEX -> {
                        if (conflictCtx.exists(effective.targetPath)) {
                            effective = conflictCtx.indexOperation(effective)
                        }
                    }
                    else -> Unit
                }
            }

            val result = executeSingle(effective, overwrite)
            results.add(effective to result)
            current++
            onProgress(current, total, effective)
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
     * @param conflictStrategy 重试时采用的冲突策略，默认 [ConflictStrategy.FAIL]。
     * @param onProgress       进度回调；置于末位以支持尾随 lambda 调用形式。
     * @return 仅含重试条目的新报告（原成功条目不包含，由调用方按需合并）。
     */
    suspend fun retry(
        report: RenameReport,
        conflictStrategy: ConflictStrategy = ConflictStrategy.FAIL,
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
        return execute(retryOps, conflictStrategy, onProgress)
    }

    /**
     * 安全删除：把 [path] 移动到回收站目录 [trashDir]（默认构造时配置）而非物理删除。
     *
     * 参考实现计划 §M4 增强（对标 tmm `Utils.java` `deleteFileWithBackup`：移到 `.backup` 目录）。
     * 保留源路径的目录结构镜像，便于后续恢复或审计。回收站祖先目录先经 MKCOL 幂等创建。
     *
     * 当前 refile 主流程仅 MOVE 不删除，本方法为未来「删除/覆盖前备份」场景预留，可被
     * [ConflictStrategy.OVERWRITE] 增强或独立调用。
     *
     * @param path     待删除（移动到回收站）的源路径。
     * @param trashDir 回收站目录，默认使用构造时配置的 [this.trashDir]。
     * @return true 表示移动成功。
     */
    suspend fun safeDelete(
        path: String,
        trashDir: String = this.trashDir,
    ): Boolean {
        val normalizedTrash = trashDir.trimEnd('/')
        if (normalizedTrash.isEmpty()) return false
        val relativeSource = path.trimStart('/')
        if (relativeSource.isEmpty()) return false
        val trashPath = "$normalizedTrash/$relativeSource"
        // 确保回收站镜像祖先目录存在（mkcol 幂等，405 视为已存在）。
        for (dir in ancestorDirs(trashPath)) {
            try {
                client.mkcol(dir)
            } catch (_: Exception) {
                // 忽略：目录可能已存在，后续 MOVE 失败会返回 false。
            }
        }
        return try {
            client.move(path, trashPath, overwrite = true)
        } catch (_: Exception) {
            false
        }
    }

    /** 执行单条操作：先 MOVE 主文件，成功后跟随 MOVE 伴随文件。 */
    private suspend fun executeSingle(op: RenameOperation, overwrite: Boolean): RenameResult {
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
                moveCompanionsOnly(op, overwrite)
            }
        }

        // 4.1.2 MOVE 主文件（按策略决定 Overwrite），失败自动重试 + 指数退避。
        val mainOk = try {
            moveWithRetry(op.sourcePath, op.targetPath, overwrite = overwrite)
        } catch (e: Exception) {
            // 4.1.3 网络异常记为整体失败（无 httpCode）。
            return RenameResult.Failed("MOVE 异常: ${op.sourcePath} -> ${op.targetPath}: ${e.message}")
        }
        if (!mainOk) {
            // 4.1.3 主文件失败 → 整体失败，伴随文件不处理。
            return RenameResult.Failed("MOVE 失败: ${op.sourcePath} -> ${op.targetPath}")
        }

        // 4.1.2 伴随文件跟随。
        return moveCompanions(op, overwrite)
    }

    /**
     * MOVE 单个资源，失败自动重试 + 指数退避。
     *
     * 首次尝试 + [maxRetries] 次重试；每次重试前等待 [initialDelayMs] 起步并翻倍
     * （1s→2s→4s…）。成功立即返回 true，全部失败返回 false。
     *
     * [WebDavClient.move] 内部已吞掉 IOException/HttpException 并返回 false，故此处仅对
     * false 重试。重试期间使用协程 [delay]，在测试调度器下为虚拟时间（不实际睡眠）。
     */
    private suspend fun moveWithRetry(
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): Boolean {
        var backoff = initialDelayMs
        repeat(maxRetries + 1) { attempt ->
            if (client.move(source, target, overwrite = overwrite)) return true
            if (attempt < maxRetries) {
                delay(backoff)
                backoff *= 2 // 指数退避
            }
        }
        return false
    }

    /**
     * 仅 MOVE 伴随文件（主文件因源==目标已跳过，未实际落地新位置）。
     * 复用 [moveCompanions] 的失败统计逻辑：全部成功 → Success；部分失败 → Partial。
     * 注意：此处返回 Success/Partial 而非 Skipped，因为伴随文件确实执行了重命名。
     */
    private suspend fun moveCompanionsOnly(op: RenameOperation, overwrite: Boolean): RenameResult {
        // 主文件已跳过（未移动），伴随文件中源==目标的也跳过，只 MOVE 需要改名的伴随文件。
        val toMove = op.companions.filter { it.sourcePath != it.targetPath }
        if (toMove.isEmpty()) {
            return RenameResult.Skipped("源路径与目标路径相同: ${op.sourcePath}")
        }
        val failedCompanions = mutableListOf<String>()
        for (comp in toMove) {
            val compOk = try {
                moveWithRetry(comp.sourcePath, comp.targetPath, overwrite = overwrite)
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
    private suspend fun moveCompanions(op: RenameOperation, overwrite: Boolean): RenameResult {
        if (op.companions.isEmpty()) {
            return RenameResult.Success
        }
        val failedCompanions = mutableListOf<String>()
        for (comp in op.companions) {
            // 伴随文件源==目标 → 跳过（同主文件逻辑），不计入失败。
            if (comp.sourcePath == comp.targetPath) continue
            val compOk = try {
                moveWithRetry(comp.sourcePath, comp.targetPath, overwrite = overwrite)
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

    /**
     * 冲突预检测上下文（仅 [ConflictStrategy.SKIP]/[ConflictStrategy.INDEX] 使用）。
     *
     * 对所有目标父目录并发无关地逐个 PROPFIND Depth 1，收集已存在文件名；[usedTargets] 记录
     * 本批次已占用（含 INDEX 生成的新名），避免 INDEX 时与同批次其它目标撞名。
     */
    private inner class ConflictContext(
        val existingByDir: Map<String, Set<String>>,
        val usedTargets: MutableSet<String>,
    ) {
        /** 目标路径在服务器已存在文件名时返回 true。 */
        fun exists(path: String): Boolean {
            val dir = parentDir(path)
            val name = fileNameOf(path)
            return existingByDir[dir]?.contains(name) == true
        }

        /**
         * 对冲突操作做 INDEX：主文件目标加 ` (n)` 后缀直到可用，同步把伴随文件目标基名
         * 替换为主文件新基名，保持主/伴随同名。
         */
        fun indexOperation(op: RenameOperation): RenameOperation {
            val newTarget = findAvailable(op.targetPath)
            usedTargets.add(newTarget)
            val oldBase = baseName(op.targetPath)
            val newBase = baseName(newTarget)
            val newCompanions = if (oldBase == newBase) {
                op.companions
            } else {
                op.companions.map { c ->
                    c.copy(targetPath = replaceBase(c.targetPath, oldBase, newBase))
                }
            }
            return op.copy(targetPath = newTarget, companions = newCompanions)
        }

        /** 从 `path (1)` 起递增寻找既不在服务器也不在 [usedTargets] 的目标名。 */
        private fun findAvailable(path: String): String {
            var n = 1
            var candidate = appendSuffix(path, n)
            while (exists(candidate) || candidate in usedTargets) {
                n++
                candidate = appendSuffix(path, n)
            }
            return candidate
        }
    }

    /**
     * 构建冲突预检测上下文：对每个唯一目标父目录 PROPFIND Depth 1 收集已存在文件名。
     * PROPFIND 失败（目录不存在/网络错误）回退空集，不中断流程。
     * [usedTargets] 预填本批次所有目标路径，供 INDEX 避免同批次撞名。
     */
    private suspend fun buildConflictContext(operations: List<RenameOperation>): ConflictContext {
        val dirs = linkedSetOf<String>()
        val usedTargets = mutableSetOf<String>()
        for (op in operations) {
            if (op.targetPath != op.sourcePath) {
                dirs.add(parentDir(op.targetPath))
                usedTargets.add(op.targetPath)
            }
            for (comp in op.companions) {
                if (comp.targetPath != comp.sourcePath) {
                    dirs.add(parentDir(comp.targetPath))
                    usedTargets.add(comp.targetPath)
                }
            }
        }
        val existingByDir = mutableMapOf<String, Set<String>>()
        for (dir in dirs) {
            val names = try {
                client.propfind(dir, 1)
            } catch (_: Exception) {
                emptyList()
            }.filterNot { it.isCollection }
                .mapNotNull { entry ->
                    entry.displayName?.takeIf { it.isNotEmpty() } ?: nameFromHref(entry.href)
                }
                .toSet()
            existingByDir[dir] = names
        }
        return ConflictContext(existingByDir, usedTargets)
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

    /** 取路径所在目录（末段之前），根目录返回 `/`。 */
    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    /** 取路径末段文件名。 */
    private fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** 从 WebDAV href 取末段并做最小 %20 解码（仅当 displayName 缺失时回退用）。 */
    private fun nameFromHref(href: String): String =
        href.trimEnd('/').substringAfterLast('/').replace("%20", " ")

    /** 拼接目录与文件名为绝对路径。 */
    private fun joinPath(dir: String, name: String): String =
        if (dir == "/") "/$name" else "$dir/$name"

    /** 在文件名扩展名前插入 ` (n)` 后缀：`/d/a.mkv` → `/d/a (1).mkv`。无扩展名则追加到末尾。 */
    private fun appendSuffix(path: String, n: Int): String {
        val dir = parentDir(path)
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return joinPath(dir, "$base ($n)$ext")
    }

    /** 取文件名去扩展名的基名：`/d/a.mkv` → `a`。无扩展名返回整个文件名。 */
    private fun baseName(path: String): String {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 取文件名扩展名（含点）：`/d/a.mkv` → `.mkv`。无扩展名返回空串。 */
    private fun extensionOf(path: String): String {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    /** 把路径文件名的基名从 [oldBase] 替换为 [newBase]（扩展名不变）；基名不匹配则原样返回。 */
    private fun replaceBase(path: String, oldBase: String, newBase: String): String {
        val dir = parentDir(path)
        val ext = extensionOf(path)
        val name = fileNameOf(path)
        val base = if (ext.isNotEmpty()) name.removeSuffix(ext) else name
        return if (base == oldBase) joinPath(dir, newBase + ext) else path
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_TRASH_DIR = ".trash"
    }
}
