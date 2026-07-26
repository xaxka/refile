package xa.refile.data.backup

import androidx.room.withTransaction
import xa.refile.core.naming.NamingOptions
import xa.refile.core.naming.PresetRepository
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.db.AppDatabase
import xa.refile.data.db.ServerConfigEntity
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.prefs.VisualOptions
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
 * - [export]：收集服务器/设置/模板/Hosts 构造 [BackupFile]；口令非空时整体 AES-GCM 加密。
 * - [import]：解析 + schema 校验 + 版本兼容性校验 + 解密（若加密），返回 [ImportResult.Preview]。
 * - [applyImport]：全量校验通过后落库（servers 全量替换、settings/templates/hosts 覆盖）。
 *
 * 红线：历史与缓存不纳入备份；服务器密码默认置空，仅口令加密时才包含解密后的明文并整体加密。
 *
 * 注：servers 落库采用「清空再插入」简化策略（Task 5.2.3 允许），不做跨表事务回滚；
 * 解析/解密/版本校验失败均不触碰现有配置。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val db: AppDatabase,
    private val serverRepository: ServerRepository,
    private val settings: SettingsRepository,
    @Suppress("unused") private val presets: PresetRepository,
    private val crypto: KeystoreCrypto,
) {

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
            BackupFile(
                exportedAt = now,
                appVersion = APP_VERSION,
                settings = payload.settings,
                servers = payload.servers,
                hosts = payload.hosts,
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
            // schema 校验：明文备份必须含 settings 与 hosts
            val s = file.settings ?: return ImportResult.Failure("备份缺少 settings 字段")
            val h = file.hosts ?: return ImportResult.Failure("备份缺少 hosts 字段")
            BackupPayload(s, file.servers, h)
        }

        return ImportResult.Preview(payload, buildChanges(payload))
    }

    /**
     * 应用已预览的导入载荷：全量覆盖落库。
     *
     * 简化策略：servers 清空再插入（按 name 全量替换），settings/templates/hosts 直接覆盖。
     * 落库前数据已在 [import] 阶段完成校验；此处的写操作为简单 CRUD，失败返回 [ApplyResult.Failure]。
     */
    suspend fun applyImport(payload: BackupPayload): ApplyResult = try {
        // 1) servers 全量替换：先删全部现有，再逐条插入（事务包裹，中途失败回滚避免丢数据）
        db.withTransaction {
            val existing = serverRepository.observeServers().first()
            existing.forEach { serverRepository.deleteServer(it.id) }
            payload.servers.forEach { snap ->
                serverRepository.addServer(
                    name = snap.name,
                    baseUrl = snap.baseUrl,
                    port = snap.port,
                    rootPath = snap.rootPath,
                    username = snap.username,
                    password = snap.password.takeIf { it.isNotBlank() },
                    authType = snap.authType,
                    https = snap.https,
                )
            }
        }

        // 2) settings 覆盖
        with(payload.settings) {
            settings.setApiKey(apiKey)
            settings.setLanguage(language)
            settings.setPresetId(presetId)
            // 测试反馈 Item 9：电影/剧集模板分离备份恢复（兼容旧版空值）。
            // 旧版单模板字段 templateString 在运行时仍被 PreviewViewModel 读取，
            // 故同步为电影模板以保持不变量（templateString == movieTemplateString）。
            if (movieTemplateString.isNotBlank()) {
                settings.setMovieTemplateString(movieTemplateString)
                settings.setTemplateString(movieTemplateString)
            }
            if (episodeTemplateString.isNotBlank()) settings.setEpisodeTemplateString(episodeTemplateString)
            settings.setVisualOptions(visualOptions.toVisualOptions())
        }

        // 3) hosts 覆盖
        settings.setHostsConfig(payload.hosts)

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
        val visualOptions = settings.visualOptions.first()
        val hostsConfig = settings.hostsConfig.first()

        val settingsSnapshot = SettingsSnapshot(
            apiKey = apiKey,
            language = language,
            presetId = presetId,
            movieTemplateString = movieTemplate,
            episodeTemplateString = episodeTemplate,
            visualOptions = visualOptions.toSnapshot(),
        )
        val serverSnapshots = servers.map { it.toSnapshot(withPasswords) }
        return BackupPayload(settingsSnapshot, serverSnapshots, hostsConfig)
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
            baseUrl = baseUrl,
            port = port,
            rootPath = rootPath,
            username = username,
            password = pwd,
            authType = authType,
            https = https,
        )
    }

    /** 构造变更预览：对比备份载荷与当前本地数据。 */
    private suspend fun buildChanges(payload: BackupPayload): ImportChanges {
        val current = serverRepository.observeServers().first()
        val currentNames = current.map { it.name }.toSet()
        val backupNames = payload.servers.map { it.name }.toSet()
        val newServers = payload.servers.count { it.name !in currentNames }
        val overwrittenServers = payload.servers.count { it.name in currentNames }
        val removedServers = current.count { it.name !in backupNames }

        val curSettings = SettingsSnapshot(
            apiKey = settings.apiKey.first(),
            language = settings.language.first(),
            presetId = settings.presetId.first(),
            movieTemplateString = settings.movieTemplateString.first(),
            episodeTemplateString = settings.episodeTemplateString.first(),
            visualOptions = settings.visualOptions.first().toSnapshot(),
        )
        val settingsChanged = curSettings != payload.settings

        val curHosts = settings.hostsConfig.first()
        val hostsChanged = curHosts != payload.hosts

        return ImportChanges(
            newServers = newServers,
            overwrittenServers = overwrittenServers,
            removedServers = removedServers,
            settingsChanged = settingsChanged,
            hostsChanged = hostsChanged,
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

    private fun VisualOptions.toSnapshot(): VisualOptionsSnapshot = VisualOptionsSnapshot(
        separator = separator.toString(),
        caseMode = caseMode.name,
        illegalCharHandling = illegalCharHandling.name,
        padDigits = padDigits,
    )

    private fun VisualOptionsSnapshot.toVisualOptions(): VisualOptions = VisualOptions(
        separator = separator.firstOrNull() ?: ' ',
        caseMode = runCatching { NamingOptions.Casing.valueOf(caseMode) }
            .getOrDefault(NamingOptions.Casing.AS_IS),
        illegalCharHandling = runCatching {
            NamingOptions.IllegalCharHandling.valueOf(illegalCharHandling)
        }.getOrDefault(NamingOptions.IllegalCharHandling.REPLACE_DASH),
        padDigits = padDigits,
    )

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
