package xa.refile.data.backup

import androidx.room.withTransaction
import xa.refile.core.naming.PresetRepository
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.AppDatabase
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份与恢复仓库（计划 §M5 SubTask 5.2.1–5.2.3）。
 *
 * 职责：
 * - [export]：收集服务器/设置/模板构造 [BackupFile]；口令非空时整体 AES-GCM 加密。
 *   P0-2（审查报告 2026-09-25）：未加密导出明文剔除 TMDB API Key，UI 明示警告。
 * - [import]：解析 + schema 校验 + 版本兼容性校验 + 解密（若加密），返回 [ImportResult.Preview]。
 * - [applyImport]：校验通过后落库（P0-3：servers 按指纹合并保留 ID、本地多余服务器保留；
 *   settings/templates 覆盖）。
 *
 * 红线：历史与缓存不纳入备份；服务器密码默认置空，仅口令加密时才包含解密后的明文并整体加密。
 *
 * 注：servers 落库为合并式策略（P0-3 修复：原「清空再插入」会让自增 id 变化，历史批次
 * serverId 悬空/错指，撤销时可能反向 MOVE 到错误服务器）；解析/解密/版本校验失败均不
 * 触碰现有配置。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val db: AppDatabase,
    private val serverRepository: ServerRepository,
    private val settings: SettingsRepository,
    @Suppress("unused") private val presets: PresetRepository,
    private val crypto: KeystoreCrypto,
) {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * 导出当前全部配置为 JSON 文本。
     *
     * @param passphrase 口令；非空白则用 PBKDF2 派生密钥并整体 AES-GCM 加密。
     * @param includePasswords 是否在口令加密时包含服务器明文密码（需 [passphrase] 非空才有意义）。
     * @return [BackupResult.Success] 含 JSON 文本，或 [BackupResult.Failure]。
     */
    suspend fun export(passphrase: String?, includePasswords: Boolean): BackupResult = try {
        val payload = collectPayload(includePasswords && !passphrase.isNullOrBlank())
        val now = System.currentTimeMillis()

        val file = if (!passphrase.isNullOrBlank()) {
            // 口令加密：序列化整个 payload 后整体加密
            val salt = ByteArray(SALT_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(passphrase, salt)
            val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
            val (iv, cipherText) = encryptGcm(key, plain)
            BackupFile(
                exportedAt = now,
                appVersion = APP_VERSION,
                encrypted = true,
                salt = b64(salt),
                iv = b64(iv),
                cipherText = b64(cipherText),
            )
        } else {
            // P0-2 修复（审查报告 2026-09-25）：未加密导出的明文 JSON 剔除 TMDB API Key。
            // SAF 导出的文件常落入下载目录或被网盘自动同步，明文凭据存在泄露风险；
            // 服务器密码本就不含（仅口令加密时可选包含），此处仅设置中的 apiKey 需要剔除。
            // UI 侧在导出面板明示警告（backup_no_passphrase_warning）。
            BackupFile(
                exportedAt = now,
                appVersion = APP_VERSION,
                settings = payload.settings.copy(apiKey = ""),
                servers = payload.servers,
            )
        }
        BackupResult.Success(json.encodeToString(BackupFile.serializer(), file))
    } catch (e: Exception) {
        BackupResult.Failure("导出失败：${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 解析并校验备份 JSON。
     *
     * 流程：schema 校验 → 版本兼容性校验 → 解密（若加密）→ 变更预览。
     * 任何失败均返回 [ImportResult.Failure]，不触碰现有配置。
     */
    suspend fun import(jsonText: String, passphrase: String?): ImportResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), jsonText)
        } catch (e: SerializationException) {
            return ImportResult.Failure("备份文件格式损坏或字段缺失：${e.message ?: "无法解析"}")
        } catch (e: Exception) {
            return ImportResult.Failure("导入失败：${e.message ?: e.javaClass.simpleName}")
        }

        // 版本兼容性校验
        if (file.formatVersion > BackupFile.CURRENT_FORMAT_VERSION) {
            return ImportResult.Failure(
                "备份文件版本过高（v${file.formatVersion}），当前支持 v${BackupFile.CURRENT_FORMAT_VERSION}",
            )
        }

        val payload = if (file.encrypted) {
            // schema 校验：加密备份必须含 salt/iv/cipherText
            val salt = file.salt ?: return ImportResult.Failure("加密备份缺少 salt 字段")
            val iv = file.iv ?: return ImportResult.Failure("加密备份缺少 iv 字段")
            val cipherText = file.cipherText ?: return ImportResult.Failure("加密备份缺少 cipherText 字段")
            val pass = passphrase?.takeIf { it.isNotBlank() }
                ?: return ImportResult.Failure("该备份已加密，请输入口令")
            val key = deriveKey(pass, b64Decode(salt))
            val plain = try {
                decryptGcm(key, b64Decode(iv), b64Decode(cipherText))
            } catch (e: AEADBadTagException) {
                return ImportResult.Failure("口令错误或备份已损坏")
            }
            try {
                json.decodeFromString(BackupPayload.serializer(), String(plain, Charsets.UTF_8))
            } catch (e: SerializationException) {
                return ImportResult.Failure("备份内容损坏：${e.message ?: "无法解析"}")
            }
        } else {
            // schema 校验：明文备份必须含 settings
            val s = file.settings ?: return ImportResult.Failure("备份缺少 settings 字段")
            BackupPayload(s, file.servers)
        }

        return ImportResult.Preview(payload, buildChanges(payload))
    }

    /**
     * 应用已预览的导入载荷：合并式落库。
     *
     * P0-3 修复（审查报告 2026-09-25）：servers 由「清空再插入（按 name 全量替换）」改为
     * 按服务器指纹合并——命中的就地更新（保留原 id，历史 rename_batches.serverId 引用
     * 不失效）；未命中的新增插入；本地多余的服务器保留不删除。原「清空再重插」会让自增
     * id 变化（rowid 复用还可能错指到另一台服务器），恢复前产生的历史批次撤销时要么
     * 「服务器已删除」、要么反向 MOVE 到错误服务器。
     * settings/templates 覆盖（apiKey 为空串时保留本地值，见 P0-2 副作用防护）。
     */
    suspend fun applyImport(payload: BackupPayload): ApplyResult = try {
        // 1) servers 按指纹合并（事务包裹，中途失败回滚避免丢数据）
        db.withTransaction {
            // 事务内必须用一次性 suspend 查询而非 Flow 收集（observeServers().first()）：
            // Room 的 withTransaction 持写锁，Flow 查询等待读锁，可能死锁/ANR。
            val existing = serverRepository.getAllServers()
            val mergePlan = planServerMerge(existing, payload.servers)
            mergePlan.forEach { action ->
                val matched = action.matchedEntity
                if (matched != null) {
                    // 指纹命中：就地更新（保留 id）——name/rootPath 等非指纹字段以备份为准，
                    // 备份含明文密码（口令加密导出）时更新密码，否则保留本地已存密码。
                    serverRepository.updateServer(
                        matched.copy(
                            name = action.snapshot.name,
                            port = action.snapshot.port,
                            rootPath = action.snapshot.rootPath,
                            https = action.snapshot.https,
                        ),
                        newPassword = action.snapshot.password.takeIf { it.isNotBlank() },
                    )
                } else {
                    serverRepository.addServer(
                        name = action.snapshot.name,
                        baseUrl = action.snapshot.baseUrl,
                        port = action.snapshot.port,
                        rootPath = action.snapshot.rootPath,
                        username = action.snapshot.username,
                        password = action.snapshot.password.takeIf { it.isNotBlank() },
                        type = action.snapshot.type,
                        https = action.snapshot.https,
                    )
                }
            }
        }

        // 2) settings 覆盖
        with(payload.settings) {
            // P0-2 副作用防护（审查报告 2026-09-25）：无口令导出的备份不含 apiKey
            // （导出时已剔除为空串）；apiKey 为空串视为「备份中无此信息」，保留本地值，
            // 避免「导出→恢复」一轮把本地 API Key 意外清空。加密备份不受影响。
            if (apiKey.isNotBlank()) settings.setApiKey(apiKey)
            settings.setLanguage(language)
            settings.setPresetId(presetId)
            // 测试反馈 Item 9：电影/剧集模板分离备份恢复（兼容旧版空值）。
            // 旧版单模板字段 templateString 在运行时仍被 PreviewViewModel 读取，
            // 故同步为电影模板以保持不变量（templateString == movieTemplateString）。
            //
            // P2 修复：旧版备份只有 templateString（无 movie/episode 字段，反序列化后
            // 两者为空串）——原实现因两者 isNotBlank 不成立而整体跳过，旧版自定义模板
            // 被静默丢弃。修复：movie/episode 为空但旧版 templateString 非空时，
            // 把旧单模板回填到两者（旧版语义即电影/剧集共用一条模板）。
            val legacyTemplate = templateString.takeIf { it.isNotBlank() }
            if (movieTemplateString.isNotBlank()) {
                settings.setMovieTemplateString(movieTemplateString)
                settings.setTemplateString(movieTemplateString)
            } else if (legacyTemplate != null) {
                settings.setMovieTemplateString(legacyTemplate)
                settings.setTemplateString(legacyTemplate)
            }
            if (episodeTemplateString.isNotBlank()) {
                settings.setEpisodeTemplateString(episodeTemplateString)
            } else if (legacyTemplate != null) {
                settings.setEpisodeTemplateString(legacyTemplate)
            }
        }

        ApplyResult.Success
    } catch (e: Exception) {
        ApplyResult.Failure("落库失败：${e.message ?: e.javaClass.simpleName}")
    }

    /** 收集当前全部配置为 [BackupPayload]。[withPasswords]=true 时填入解密后的明文密码。 */
    private suspend fun collectPayload(withPasswords: Boolean): BackupPayload {
        val servers = serverRepository.observeServers().first()
        val apiKey = settings.apiKey.first()
        val language = settings.language.first()
        val presetId = settings.presetId.first()
        val movieTemplate = settings.movieTemplateString.first()
        val episodeTemplate = settings.episodeTemplateString.first()

        val settingsSnapshot = SettingsSnapshot(
            apiKey = apiKey,
            language = language,
            presetId = presetId,
            movieTemplateString = movieTemplate,
            episodeTemplateString = episodeTemplate,
        )
        val serverSnapshots = servers.map { it.toSnapshot(withPasswords) }
        return BackupPayload(settingsSnapshot, serverSnapshots)
    }

    /** 服务器实体 → 快照。[withPasswords]=true 时填入解密后的明文密码，否则置空串。 */
    private fun ServerConfigEntity.toSnapshot(withPasswords: Boolean): ServerSnapshot {
        val pwd = if (withPasswords) {
            encryptedPassword?.let { runCatching { crypto.decrypt(it) }.getOrNull() } ?: ""
        } else {
            ""
        }
        return ServerSnapshot(
            name = name,
            type = type,
            baseUrl = baseUrl,
            port = port,
            rootPath = rootPath,
            username = username,
            password = pwd,
            https = https,
        )
    }

    /** 构造变更预览：对比备份载荷与当前本地数据（P0-3：按服务器指纹而非 name 计数）。 */
    private suspend fun buildChanges(payload: BackupPayload): ImportChanges {
        val current = serverRepository.getAllServers()
        val plan = planServerMerge(current, payload.servers)
        val newServers = plan.count { it.matchedEntity == null }
        val overwrittenServers = plan.count { it.matchedEntity != null }
        // P0-3：合并式导入不删除本地多余服务器（保护历史 serverId 引用），删除数恒为 0。
        val removedServers = 0

        val curSettings = SettingsSnapshot(
            apiKey = settings.apiKey.first(),
            language = settings.language.first(),
            presetId = settings.presetId.first(),
            movieTemplateString = settings.movieTemplateString.first(),
            episodeTemplateString = settings.episodeTemplateString.first(),
        )
        val settingsChanged = curSettings != payload.settings

        return ImportChanges(
            newServers = newServers,
            overwrittenServers = overwrittenServers,
            removedServers = removedServers,
            settingsChanged = settingsChanged,
        )
    }

    // ---------- 口令加密（PBKDF2 + AES-GCM）----------

    /** PBKDF2 派生 256 位 AES 密钥。 */
    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** AES-GCM 加密，返回 (iv, cipherText)。 */
    private fun encryptGcm(key: SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        val iv = ByteArray(GCM_IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plain)
        return iv to cipherText
    }

    /** AES-GCM 解密。口令错误会抛 [AEADBadTagException]。 */
    private fun decryptGcm(key: SecretKey, iv: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64Decode(s: String): ByteArray = Base64.getDecoder().decode(s)

    private companion object {
        const val APP_VERSION = "1.0.0"
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 100_000
        const val KEY_LENGTH_BITS = 256
        const val SALT_SIZE_BYTES = 16
        const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

/** 落库结果。 */
sealed class ApplyResult {
    /** 落库成功。 */
    object Success : ApplyResult()

    /** 落库失败，[reason] 为可展示原因。 */
    data class Failure(val reason: String) : ApplyResult()
}
