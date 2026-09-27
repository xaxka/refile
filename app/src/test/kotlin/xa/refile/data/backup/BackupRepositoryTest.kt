package xa.refile.data.backup

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xa.refile.core.naming.PresetRepository
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.AppDatabase
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.ServerRepository
import org.junit.Test

/**
 * [BackupRepository.export] 无口令导出脱敏单元测试（P0-2，审查报告 2026-09-25）。
 *
 * 验收：无口令导出的明文 JSON 不包含 TMDB API Key（服务器密码本就不含），
 * 且结构仍是合法的未加密 [BackupFile]。
 */
class BackupRepositoryTest {

    private val secret = "0123456789abcdef0123456789abcdef"

    private fun newRepo(serverRepository: ServerRepository, settings: SettingsRepository): BackupRepository =
        BackupRepository(
            db = mockk<AppDatabase>(relaxed = true), // export 不触库
            serverRepository = serverRepository,
            settings = settings,
            presets = mockk<PresetRepository>(relaxed = true),
            crypto = mockk<KeystoreCrypto>(relaxed = true), // 无口令导出不解密
        )

    private fun stubSettings(settings: SettingsRepository) {
        every { settings.apiKey } returns flowOf(secret)
        every { settings.language } returns flowOf("zh-CN")
        every { settings.presetId } returns flowOf("DEFAULT")
        every { settings.movieTemplateString } returns flowOf("电影模板")
        every { settings.episodeTemplateString } returns flowOf("剧集模板")
    }

    @Test
    fun `unencrypted export strips apiKey from plaintext json`() = runTest {
        val serverRepository = mockk<ServerRepository>()
        every { serverRepository.observeServers() } returns flowOf(
            listOf(
                ServerConfigEntity(
                    id = 1L,
                    name = "NAS",
                    type = "webdav",
                    baseUrl = "https://dav.example.com",
                    encryptedPassword = "cipher",
                ),
            ),
        )
        val settings = mockk<SettingsRepository>()
        stubSettings(settings)

        val result = newRepo(serverRepository, settings).export(passphrase = null, includePasswords = true)

        val success = result as BackupResult.Success
        // P0-2：明文 JSON 不出现 API Key 的值（无论 includePasswords 与否——无口令导出恒剔除）
        assertThat(success.json).doesNotContain(secret)
        // 服务器密码同样不出现（无口令导出不收集，encryptedPassword 密文也不导出）
        assertThat(success.json).doesNotContain("cipher")
        // 结构仍是合法未加密备份，settings.apiKey 序列化为空（decode 后验证）
        assertThat(success.json).isNotEmpty()
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(BackupFile.serializer(), success.json)
        assertThat(decoded.encrypted).isFalse()
        assertThat(decoded.settings).isNotNull()
        assertThat(decoded.settings!!.apiKey).isEmpty()
        assertThat(decoded.servers).hasSize(1)
        assertThat(decoded.servers[0].password).isEmpty()
    }

    @Test
    fun `unencrypted export keeps template strings`() = runTest {
        val serverRepository = mockk<ServerRepository>()
        every { serverRepository.observeServers() } returns flowOf(emptyList())
        val settings = mockk<SettingsRepository>()
        stubSettings(settings)

        val result = newRepo(serverRepository, settings).export(passphrase = "", includePasswords = false)

        val success = result as BackupResult.Success
        assertThat(success.json).contains("电影模板")
        assertThat(success.json).contains("剧集模板")
    }
}
