package xa.refile.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xa.refile.core.webdav.ConnectionResult
import xa.refile.core.webdav.FileClient
import xa.refile.core.webdav.WebDavEntry
import xa.refile.core.webdav.WebDavException
import xa.refile.data.db.RenameBatchDao
import xa.refile.data.db.RenameBatchEntity
import xa.refile.data.db.RenameEntryEntity
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import org.junit.Test

/**
 * [HistoryRepository.revertBatch] 撤销可重入性单元测试（P0-4，审查报告 2026-09-25）。
 *
 * 用内存版 [RenameBatchDao] 假实现 + 可注入失败行为的 [FileClient] 假实现覆盖：
 * - 部分失败 → 批次保持可撤销（isReverted 不落 true），成功条目落条目级 REVERTED；
 * - 重试 → 仅对未回滚条目发 MOVE（已回滚条目按状态跳过，幂等），
 *   全部回滚后批次才标记 isReverted；
 * - 伴随文件反向 MOVE 失败计入条目失败原因（P0-4②），条目不标记 REVERTED；
 * - move 失败但 move 源已不存在（此前重试已回滚）→ PROPFIND 404 幂等视为已回滚（P0-4③）；
 * - 已整批撤销的批次拒绝再次撤销（既有行为保持）。
 */
class HistoryRepositoryTest {

    private val server = ServerConfigEntity(
        id = 1L,
        name = "NAS",
        type = "webdav",
        baseUrl = "https://dav.example.com",
    )

    private fun newRepo(dao: FakeDao, client: FakeFileClient): HistoryRepository {
        val serverRepository = mockk<ServerRepository>()
        coEvery { serverRepository.getServer(any()) } returns server
        coEvery { serverRepository.clientFor(any(), any()) } returns client
        val settings = mockk<SettingsRepository>()
        every { settings.concurrencyLimit } returns flowOf(2)
        return HistoryRepository(dao, serverRepository, settings)
    }

    private fun batch(id: Long = 1L) = RenameBatchEntity(
        id = id,
        serverId = 1L,
        serverName = "NAS",
        batchName = "批次",
        createdAt = 1_000L,
        totalOperations = 2,
        succeededCount = 2,
        failedCount = 0,
    )

    private fun entry(
        id: Long,
        batchId: Long = 1L,
        source: String,
        target: String,
        companions: String = "[]",
    ) = RenameEntryEntity(
        id = id,
        batchId = batchId,
        sourcePath = source,
        targetPath = target,
        mediaType = "MOVIE",
        companionsJson = companions,
        status = HistoryRepository.STATUS_SUCCESS,
    )

    @Test
    fun `partial revert failure keeps batch revertible and marks rolled back entries`() = runTest {
        val dao = FakeDao().apply {
            batches += batch()
            entries += listOf(
                entry(1, source = "/d/a.mkv", target = "/d/A (2024).mkv"),
                entry(2, source = "/d/b.mkv", target = "/d/B (2024).mkv"),
            )
        }
        val client = FakeFileClient().apply {
            existing += setOf("/d/A (2024).mkv", "/d/B (2024).mkv")
            failMoveFrom += "/d/B (2024).mkv" // 模拟断网：B 的反向 MOVE 失败且仍存在
        }
        val repo = newRepo(dao, client)

        val result = repo.revertBatch(1L)

        val partial = result as RevertResult.Partial
        assertThat(partial.rolledBack).isEqualTo(1)
        assertThat(partial.total).isEqualTo(2)
        assertThat(partial.failedEntries).hasSize(1)
        assertThat(partial.failedEntries[0].targetPath).isEqualTo("/d/B (2024).mkv")
        // P0-4①：部分失败 → 批次保持可撤销（不整批标记 isReverted）
        assertThat(dao.batches[0].isReverted).isFalse()
        // P0-4③：成功条目落条目级 REVERTED；失败条目保持 SUCCESS（可重试）
        assertThat(dao.entries.first { it.id == 1L }.status).isEqualTo("REVERTED")
        assertThat(dao.entries.first { it.id == 2L }.status).isEqualTo("SUCCESS")
    }

    @Test
    fun `retry after partial failure only reverts remaining entries and completes batch`() = runTest {
        val dao = FakeDao().apply {
            batches += batch()
            entries += listOf(
                entry(1, source = "/d/a.mkv", target = "/d/A (2024).mkv"),
                entry(2, source = "/d/b.mkv", target = "/d/B (2024).mkv"),
            )
        }
        val client = FakeFileClient().apply {
            existing += setOf("/d/A (2024).mkv", "/d/B (2024).mkv")
            failMoveFrom += "/d/B (2024).mkv"
        }
        val repo = newRepo(dao, client)
        repo.revertBatch(1L) // 首次：部分失败

        // 网络恢复（撤销失败因素消失）
        client.failMoveFrom.clear()
        val moveCallsBeforeRetry = client.moveCalls.size

        val retry = repo.revertBatch(1L)

        val success = retry as RevertResult.Success
        assertThat(success.rolledBack).isEqualTo(1) // 仅剩 1 条需要回滚
        assertThat(success.total).isEqualTo(1)
        // 重试只对失败条目发 MOVE：新增恰好一次（B 的反向），不含已回滚的 A
        assertThat(client.moveCalls.size).isEqualTo(moveCallsBeforeRetry + 1)
        assertThat(client.moveCalls.drop(moveCallsBeforeRetry))
            .containsExactly("/d/B (2024).mkv" to "/d/b.mkv")
        // 全部条目回滚后批次才标记 isReverted
        assertThat(dao.batches[0].isReverted).isTrue()
        assertThat(dao.entries.first { it.id == 2L }.status).isEqualTo("REVERTED")
    }

    @Test
    fun `companion revert failure counts as entry failure`() = runTest {
        val dao = FakeDao().apply {
            batches += batch()
            entries += entry(
                1,
                source = "/d/a.mkv",
                target = "/d/A (2024).mkv",
                companions = """[{"sourcePath":"/d/a.srt","targetPath":"/d/A (2024).srt"}]""",
            )
        }
        val client = FakeFileClient().apply {
            existing += setOf("/d/A (2024).mkv", "/d/A (2024).srt")
            failMoveFrom += "/d/A (2024).srt" // 伴随反向失败且仍存在
        }
        val repo = newRepo(dao, client)

        val result = repo.revertBatch(1L)

        val partial = result as RevertResult.Partial
        assertThat(partial.failedEntries).hasSize(1)
        // P0-4②：伴随反向 MOVE 失败进入条目失败原因（不再静默吞掉）
        assertThat(partial.failedEntries[0].reason).contains("伴随文件反向 MOVE 失败")
        // 主文件已成功反向，但伴随失败 → 条目不标记 REVERTED（保持可重试）
        assertThat(client.existing).doesNotContain("/d/A (2024).mkv")
        assertThat(client.existing).contains("/d/A (2024).srt")
        assertThat(dao.entries[0].status).isEqualTo("SUCCESS")
        assertThat(dao.batches[0].isReverted).isFalse()
    }

    @Test
    fun `vanished move source is treated as already reverted`() = runTest {
        val dao = FakeDao().apply {
            batches += batch()
            entries += entry(1, source = "/d/a.mkv", target = "/d/A (2024).mkv")
        }
        val client = FakeFileClient().apply {
            // 目标不存在（此前重试已回滚）→ move false → PROPFIND 404 → 幂等视为已回滚
        }
        val repo = newRepo(dao, client)

        val result = repo.revertBatch(1L)

        // P0-4③：幂等判定——已回滚条目重试时视为成功而非失败
        assertThat(result).isInstanceOf(RevertResult.Success::class.java)
        assertThat(dao.entries[0].status).isEqualTo("REVERTED")
        assertThat(dao.batches[0].isReverted).isTrue()
    }

    @Test
    fun `fully reverted batch rejects re-revert`() = runTest {
        val dao = FakeDao().apply {
            batches += batch().copy(isReverted = true, revertedAt = 1L)
            entries += entry(1, source = "/d/a.mkv", target = "/d/A (2024).mkv")
        }
        val repo = newRepo(dao, FakeFileClient())

        val result = repo.revertBatch(1L)

        assertThat(result).isInstanceOf(RevertResult.Failure::class.java)
    }

    // ---- 假实现 ----

    /** 内存版 [RenameBatchDao]：模拟 Room 读写语义（仅覆盖 revertBatch 用到的方法）。 */
    private class FakeDao : RenameBatchDao {
        val batches = mutableListOf<RenameBatchEntity>()
        val entries = mutableListOf<RenameEntryEntity>()

        override fun observeBatches(): Flow<List<RenameBatchEntity>> = flowOf(batches.toList())

        override suspend fun getBatch(id: Long): RenameBatchEntity? = batches.firstOrNull { it.id == id }

        override suspend fun getEntries(batchId: Long): List<RenameEntryEntity> =
            entries.filter { it.batchId == batchId }.sortedBy { it.id }

        override suspend fun insertBatch(batch: RenameBatchEntity): Long {
            val id = (batches.maxOfOrNull { it.id } ?: 0L) + 1L
            batches += batch.copy(id = id)
            return id
        }

        override suspend fun insertEntries(newEntries: List<RenameEntryEntity>) {
            var nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1L
            newEntries.forEach { entries += it.copy(id = nextId++) }
        }

        override suspend fun markReverted(id: Long, revertedAt: Long) {
            val idx = batches.indexOfFirst { it.id == id }
            if (idx >= 0) batches[idx] = batches[idx].copy(isReverted = true, revertedAt = revertedAt)
        }

        override suspend fun markEntryReverted(id: Long) {
            val idx = entries.indexOfFirst { it.id == id }
            if (idx >= 0) entries[idx] = entries[idx].copy(status = "REVERTED")
        }

        override suspend fun deleteBatch(id: Long) {
            batches.removeAll { it.id == id }
        }
    }

    /**
     * 可注入失败行为的 [FileClient] 假实现：
     * - [existing]：仍存在的路径（propfind 成功；move 源须存在）；
     * - [failMoveFrom]：move 指定失败的源路径（模拟断网/服务器错误，文件仍在原地）。
     */
    private class FakeFileClient : FileClient {
        val existing = mutableSetOf<String>()
        val failMoveFrom = mutableSetOf<String>()
        val moveCalls = mutableListOf<Pair<String, String>>()

        override suspend fun propfind(path: String, depth: Int): List<WebDavEntry> {
            if (path !in existing) throw WebDavException(404, "HTTP 404")
            return listOf(WebDavEntry(href = path))
        }

        override suspend fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean {
            moveCalls += fromPath to toPath
            if (fromPath !in existing) return false // 源不存在（对应真实 MOVE 404 → false）
            if (fromPath in failMoveFrom) return false
            existing -= fromPath
            existing += toPath
            return true
        }

        override suspend fun mkcol(path: String): Boolean = true

        override suspend fun testConnection(path: String): ConnectionResult = ConnectionResult.Success()
    }
}
