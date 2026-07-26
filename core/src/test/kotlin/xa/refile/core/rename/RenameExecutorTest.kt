package xa.refile.core.rename

import com.google.common.truth.Truth.assertThat
import xa.refile.core.webdav.WebDavClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [RenameExecutor] 单元测试（计划 §M4 SubTask 4.1.1–4.1.4）。
 *
 * 使用 MockWebServer 验证排序、MKCOL 幂等、MOVE、伴随文件跟随、失败记录、重试、进度与汇总。
 */
class RenameExecutorTest {

    private lateinit var server: MockWebServer
    private lateinit var executor: RenameExecutor

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = WebDavClient(
            baseUrl = server.url("/").toString(),
            username = "user",
            password = "pass",
            client = OkHttpClient(),
        )
        executor = RenameExecutor(client)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** 收集已发生的所有请求（按发生顺序）。 */
    private fun takeAllRequests(): List<RecordedRequest> =
        (0 until server.requestCount).map { server.takeRequest() }

    @Test fun `single move success returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))

        val report = executor.execute(
            listOf(RenameOperation(sourcePath = "/a.mkv", targetPath = "/b.mkv")),
        )

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].second).isInstanceOf(RenameResult.Success::class.java)
        assertThat(report.total).isEqualTo(1)
        assertThat(report.succeeded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(report.isAllSucceeded).isTrue()
    }

    @Test fun `move failure returns Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val report = executor.execute(
            listOf(RenameOperation(sourcePath = "/a.mkv", targetPath = "/b.mkv")),
        )

        val result = report.results[0].second
        assertThat(result).isInstanceOf(RenameResult.Failed::class.java)
        val failed = result as RenameResult.Failed
        assertThat(failed.reason).contains("/a.mkv")
        assertThat(failed.reason).contains("/b.mkv")
        assertThat(report.succeeded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(report.failedOperations).hasSize(1)
    }

    @Test fun `multiple ops sorted by target depth, mkcol before deep move`() = runTest {
        // op1 目标深度 2，op2 目标深度 3；排序后 op1 先、op2 后。
        val op1 = RenameOperation(sourcePath = "/a.mkv", targetPath = "/dir1/a.mkv")
        val op2 = RenameOperation(sourcePath = "/b.mkv", targetPath = "/dir1/dir2/b.mkv")

        // MKCOL /dir1（深度1）、MKCOL /dir1/dir2（深度2）、MOVE op1、MOVE op2
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL /dir1
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL /dir1/dir2
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE op1（浅）
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE op2（深）

        val report = executor.execute(listOf(op2, op1)) // 故意逆序传入

        val requests = takeAllRequests()
        assertThat(requests).hasSize(4)
        // 前两个为 MKCOL，按深度升序：先 /dir1 再 /dir1/dir2
        assertThat(requests[0].method).isEqualTo("MKCOL")
        assertThat(requests[0].path).isEqualTo("/dir1")
        assertThat(requests[1].method).isEqualTo("MKCOL")
        assertThat(requests[1].path).isEqualTo("/dir1/dir2")
        // 后两个为 MOVE，按目标深度升序：op1（浅）先于 op2（深）
        assertThat(requests[2].method).isEqualTo("MOVE")
        assertThat(requests[2].path).isEqualTo("/a.mkv")
        assertThat(requests[3].method).isEqualTo("MOVE")
        assertThat(requests[3].path).isEqualTo("/b.mkv")
        // 深目录的 MKCOL 必须先于该深目录目标的 MOVE
        val deepMkcolIndex = requests.indexOfFirst { it.method == "MKCOL" && it.path == "/dir1/dir2" }
        val deepMoveIndex = requests.indexOfFirst { it.method == "MOVE" && it.path == "/b.mkv" }
        assertThat(deepMkcolIndex).isLessThan(deepMoveIndex)
        assertThat(report.succeeded).isEqualTo(2)
    }

    @Test fun `mkcol 405 does not error and continues to move`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405)) // MKCOL /existing 已存在
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE

        val report = executor.execute(
            listOf(RenameOperation(sourcePath = "/a.mkv", targetPath = "/existing/a.mkv")),
        )

        assertThat(report.results[0].second).isInstanceOf(RenameResult.Success::class.java)
        val requests = takeAllRequests()
        assertThat(requests).hasSize(2)
        assertThat(requests[0].method).isEqualTo("MKCOL")
        assertThat(requests[1].method).isEqualTo("MOVE")
    }

    @Test fun `companion success returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE 主文件
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE 伴随 srt

        val report = executor.execute(
            listOf(
                RenameOperation(
                    sourcePath = "/a.mkv",
                    targetPath = "/b.mkv",
                    companions = listOf(CompanionRename("/a.srt", "/b.srt")),
                ),
            ),
        )

        assertThat(report.results[0].second).isInstanceOf(RenameResult.Success::class.java)
        assertThat(takeAllRequests()).hasSize(2)
    }

    @Test fun `companion partial failure returns Partial`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE 主文件 成功
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE srt 成功
        server.enqueue(MockResponse().setResponseCode(412)) // MOVE nfo 失败

        val report = executor.execute(
            listOf(
                RenameOperation(
                    sourcePath = "/a.mkv",
                    targetPath = "/b.mkv",
                    companions = listOf(
                        CompanionRename("/a.srt", "/b.srt"),
                        CompanionRename("/a.nfo", "/b.nfo"),
                    ),
                ),
            ),
        )

        val result = report.results[0].second
        assertThat(result).isInstanceOf(RenameResult.Partial::class.java)
        val partial = result as RenameResult.Partial
        assertThat(partial.failedCompanions).containsExactly("/a.nfo")
        // 主文件已成功，计入 succeeded
        assertThat(report.succeeded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
    }

    @Test fun `main failure skips companions returns Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403)) // MOVE 主文件失败

        val report = executor.execute(
            listOf(
                RenameOperation(
                    sourcePath = "/a.mkv",
                    targetPath = "/b.mkv",
                    companions = listOf(CompanionRename("/a.srt", "/b.srt")),
                ),
            ),
        )

        val result = report.results[0].second
        assertThat(result).isInstanceOf(RenameResult.Failed::class.java)
        // 主文件失败后不处理伴随文件：只有 1 个请求
        assertThat(takeAllRequests()).hasSize(1)
        assertThat(report.failed).isEqualTo(1)
    }

    @Test fun `retry failed ops succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403)) // 首次 MOVE 失败

        val firstReport = executor.execute(
            listOf(RenameOperation(sourcePath = "/a.mkv", targetPath = "/b.mkv")),
        )
        assertThat(firstReport.failed).isEqualTo(1)

        server.enqueue(MockResponse().setResponseCode(201)) // 重试 MOVE 成功

        val retryReport = executor.retry(firstReport)
        assertThat(retryReport.results).hasSize(1)
        assertThat(retryReport.results[0].second).isInstanceOf(RenameResult.Success::class.java)
        assertThat(retryReport.succeeded).isEqualTo(1)
        assertThat(retryReport.failed).isEqualTo(0)
    }

    @Test fun `progress callback invoked current 1 to total`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        val currents = mutableListOf<Int>()
        val totals = mutableListOf<Int>()
        executor.execute(
            listOf(
                RenameOperation(sourcePath = "/a.mkv", targetPath = "/x.mkv"),
                RenameOperation(sourcePath = "/b.mkv", targetPath = "/y.mkv"),
                RenameOperation(sourcePath = "/c.mkv", targetPath = "/z.mkv"),
            ),
            onProgress = { current, total, _ ->
                currents.add(current)
                totals.add(total)
            },
        )

        assertThat(currents).containsExactly(1, 2, 3).inOrder()
        assertThat(totals).containsExactly(3, 3, 3).inOrder()
    }

    @Test fun `report counts succeeded and failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // op1 成功
        server.enqueue(MockResponse().setResponseCode(409)) // op2 失败

        val report = executor.execute(
            listOf(
                RenameOperation(sourcePath = "/a.mkv", targetPath = "/b.mkv"),
                RenameOperation(sourcePath = "/c.mkv", targetPath = "/d.mkv"),
            ),
        )

        assertThat(report.total).isEqualTo(2)
        assertThat(report.succeeded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(report.isAllSucceeded).isFalse()
        assertThat(report.failedOperations).hasSize(1)
        assertThat(report.failedOperations[0].first.sourcePath).isEqualTo("/c.mkv")
    }

    /**
     * 源路径与目标路径相同（文件名未变化）→ 应跳过而非失败：
     * - 不发送 MOVE 请求（无网络调用）
     * - 结果为 [RenameResult.Skipped]
     * - 不计入 succeeded 也不计入 failed
     */
    @Test fun `source equals target returns Skipped not Failed`() = runTest {
        val report = executor.execute(
            listOf(RenameOperation(sourcePath = "/a.mkv", targetPath = "/a.mkv")),
        )

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].second).isInstanceOf(RenameResult.Skipped::class.java)
        assertThat(report.total).isEqualTo(1)
        assertThat(report.succeeded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        // 关键：不发送任何 MOVE 请求
        assertThat(takeAllRequests()).isEmpty()
    }

    /**
     * 主文件源==目标跳过，但伴随文件需要改名 → 仅 MOVE 伴随文件，返回 Success（伴随已落地）。
     */
    @Test fun `main skipped but companion renamed moves companion only`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // MOVE 伴随 srt

        val report = executor.execute(
            listOf(
                RenameOperation(
                    sourcePath = "/a.mkv",
                    targetPath = "/a.mkv", // 主文件源==目标，跳过
                    companions = listOf(CompanionRename("/a.srt", "/The Movie.srt")),
                ),
            ),
        )

        assertThat(report.results[0].second).isInstanceOf(RenameResult.Success::class.java)
        val requests = takeAllRequests()
        assertThat(requests).hasSize(1)
        assertThat(requests[0].method).isEqualTo("MOVE")
        assertThat(requests[0].path).isEqualTo("/a.srt")
    }

    /**
     * 主文件与所有伴随文件源==目标 → 整体 Skipped，不发送任何请求。
     */
    @Test fun `main and all companions same path returns Skipped`() = runTest {
        val report = executor.execute(
            listOf(
                RenameOperation(
                    sourcePath = "/a.mkv",
                    targetPath = "/a.mkv",
                    companions = listOf(CompanionRename("/a.srt", "/a.srt")),
                ),
            ),
        )

        assertThat(report.results[0].second).isInstanceOf(RenameResult.Skipped::class.java)
        assertThat(takeAllRequests()).isEmpty()
    }
}
