package xa.refile.data.backup

import com.google.common.truth.Truth.assertThat
import xa.refile.data.db.ServerConfigEntity
import org.junit.Test

/**
 * 备份恢复服务器合并计划单元测试（P0-3，审查报告 2026-09-25）。
 *
 * 覆盖 [planServerMerge] 指纹匹配规则：
 * - 指纹命中 → 就地更新（保留原 id，历史 rename_batches.serverId 引用不失效）；
 * - 指纹未命中 → 新增插入；
 * - 指纹规范化：type/baseUrl 大小写与末尾斜杠不敏感、username 空串与 null 等价；
 * - 端口/https/username/type 差异视为不同服务器；name 差异不影响匹配（展示名非身份）。
 */
class BackupMergeTest {

    private fun entity(
        id: Long,
        name: String,
        type: String = "webdav",
        baseUrl: String,
        port: Int? = null,
        username: String? = null,
        https: Boolean = true,
    ) = ServerConfigEntity(
        id = id,
        name = name,
        type = type,
        baseUrl = baseUrl,
        port = port,
        username = username,
        https = https,
    )

    private fun snapshot(
        name: String,
        type: String = "webdav",
        baseUrl: String,
        port: Int? = null,
        username: String? = null,
        https: Boolean = true,
    ) = ServerSnapshot(
        name = name,
        type = type,
        baseUrl = baseUrl,
        port = port,
        username = username,
        https = https,
    )

    @Test
    fun `unmatched fingerprint inserts new server`() {
        val existing = listOf(entity(id = 7L, name = "本地名", baseUrl = "https://dav.example.com"))

        val plan = planServerMerge(existing, listOf(snapshot(name = "备份名", baseUrl = "https://other.example.com")))

        assertThat(plan).hasSize(1)
        assertThat(plan[0].matchedEntity).isNull()
    }

    @Test
    fun `fingerprint match ignores name case and trailing slash`() {
        val existing = listOf(entity(id = 7L, name = "NAS", baseUrl = "https://dav.example.com"))

        val plan = planServerMerge(
            existing,
            listOf(snapshot(name = "改名后的 NAS", baseUrl = "https://DAV.example.com/")),
        )

        assertThat(plan).hasSize(1)
        assertThat(plan[0].matchedEntity).isNotNull()
        assertThat(plan[0].matchedEntity!!.id).isEqualTo(7L)
    }

    @Test
    fun `username blank string matches null and is case insensitive`() {
        val existing = listOf(entity(id = 3L, name = "A", baseUrl = "https://a.example.com", username = "User"))

        val plan = planServerMerge(
            existing,
            listOf(
                snapshot(name = "A2", baseUrl = "https://a.example.com", username = " user "),
                snapshot(name = "B", baseUrl = "https://b.example.com", username = ""),
            ),
        )

        // " user " 与 "User" 规范化后相等 → 命中 id=3；username="" 归一为 null，
        // 与 User 不同 → B 为新增
        assertThat(plan[0].matchedEntity).isNotNull()
        assertThat(plan[0].matchedEntity!!.id).isEqualTo(3L)
        assertThat(plan[1].matchedEntity).isNull()
    }

    @Test
    fun `null username in snapshot matches anonymous local server`() {
        val existing = listOf(entity(id = 5L, name = "Anon", baseUrl = "https://c.example.com", username = null))

        val plan = planServerMerge(
            existing,
            listOf(snapshot(name = "Anon 恢复", baseUrl = "https://c.example.com", username = " ")),
        )

        assertThat(plan[0].matchedEntity).isNotNull()
        assertThat(plan[0].matchedEntity!!.id).isEqualTo(5L)
    }

    @Test
    fun `port or https difference counts as different server`() {
        val existing = listOf(
            entity(id = 1L, name = "P1", baseUrl = "https://d.example.com", port = 5005),
            entity(id = 2L, name = "HTTP", baseUrl = "https://d.example.com", https = false),
        )

        val plan = planServerMerge(
            existing,
            listOf(
                snapshot(name = "默认端口", baseUrl = "https://d.example.com"), // port null ≠ 5005
                snapshot(name = "HTTPS", baseUrl = "https://d.example.com", https = true), // https true ≠ false
            ),
        )

        assertThat(plan[0].matchedEntity).isNull()
        assertThat(plan[1].matchedEntity).isNull()
    }

    @Test
    fun `type difference counts as different server`() {
        val existing = listOf(entity(id = 9L, name = "DAV", type = "webdav", baseUrl = "https://e.example.com"))

        val plan = planServerMerge(
            existing,
            listOf(snapshot(name = "OpenList", type = "openlist", baseUrl = "https://e.example.com")),
        )

        assertThat(plan[0].matchedEntity).isNull()
    }
}
