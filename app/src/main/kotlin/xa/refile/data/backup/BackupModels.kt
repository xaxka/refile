package xa.refile.data.backup

import kotlinx.serialization.Serializable

/**
 * 备份与恢复数据模型（计划 §M5 SubTask 5.2.1）。
 *
 * 整个备份以单个 JSON 文件落盘，结构见 [BackupFile]。
 * - 未加密：数据明文置于 [BackupFile.settings]/[servers]。
 * - 口令加密：上述数据序列化为 [BackupPayload] 后整体 AES-GCM 加密，
 *   密文置于 [BackupFile.cipherText]，[salt]/[iv] 存 Base64，明文字段置空。
 *
 * 不含重命名历史与缓存（Task 5.2 要求：历史与缓存不纳入备份）。
 *
 * 模板只备份 movie/episode 两条模板串（[SettingsSnapshot.movieTemplateString]/
 * [episodeTemplateString]）；不再单列 `templates` 块或旧版 `templateString` 字段。
 * 旧版备份文件中的这些字段因 `ignoreUnknownKeys=true` 会被安全忽略。
 */

/** 备份文件根结构。 */
@Serializable
data class BackupFile(
    /** 备份格式版本，当前固定为 [CURRENT_FORMAT_VERSION]。 */
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    /** 导出时间（epoch millis）。 */
    val exportedAt: Long,
    /** 应用版本（信息字段，不参与兼容性校验）。 */
    val appVersion: String = "1.0.0",
    /** 设置快照；口令加密时为 null（数据在 [cipherText] 内）。 */
    val settings: SettingsSnapshot? = null,
    /** 服务器快照列表；口令加密时为空。 */
    val servers: List<ServerSnapshot> = emptyList(),
    /** 是否口令加密。 */
    val encrypted: Boolean = false,
    /** PBKDF2 盐（Base64），仅 [encrypted]=true 时有值。 */
    val salt: String? = null,
    /** AES-GCM IV（Base64），仅 [encrypted]=true 时有值。 */
    val iv: String? = null,
    /** 加密后的 [BackupPayload] 密文（Base64），仅 [encrypted]=true 时有值。 */
    val cipherText: String? = null,
) {
    companion object {
        /** 当前支持的备份格式版本。 */
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/** 需要口令加密/解密的内部数据载荷（不含元信息）。 */
@Serializable
data class BackupPayload(
    val settings: SettingsSnapshot,
    val servers: List<ServerSnapshot>,
)

/** 设置快照（Task 5.2.1）。镜像 [xa.refile.data.prefs.SettingsRepository] 持久化字段。 */
@Serializable
data class SettingsSnapshot(
    val apiKey: String = "",
    val language: String = "zh-CN",
    val presetId: String = "DEFAULT",
    val movieTemplateString: String = "",
    val episodeTemplateString: String = "",
    val visualOptions: VisualOptionsSnapshot = VisualOptionsSnapshot(),
)

/** 可视化选项快照（用基本类型字段，规避 [xa.refile.data.prefs.VisualOptions] 不可序列化的问题）。 */
@Serializable
data class VisualOptionsSnapshot(
    val padDigits: Int = 2,
)

/**
 * 服务器快照（Task 5.2.1）。
 *
 * @param password 明文密码：默认空串（不含密文）。
 * 仅当用户勾选「包含密码」并提供了口令时，导出才在此字段填入解密后的明文密码，
 * 随整个 [BackupPayload] 一并口令加密。未加密导出始终为空串。
 */
@Serializable
data class ServerSnapshot(
    val name: String,
    val baseUrl: String,
    val port: Int? = null,
    val rootPath: String = "/",
    val username: String? = null,
    val password: String = "",
    val authType: String = "auto",
    val https: Boolean = true,
)

/** 导出结果。 */
sealed class BackupResult {
    /** 导出成功，[json] 为最终 JSON 文本（已含可能的密文）。 */
    data class Success(val json: String) : BackupResult()

    /** 导出失败，[reason] 为可展示原因。 */
    data class Failure(val reason: String) : BackupResult()
}

/** 导入结果。 */
sealed class ImportResult {
    /**
     * 解析/解密成功，等待用户确认落库。
     * @param payload 已解密/解析出的数据载荷，落库时直接使用。
     * @param changes 相对当前本地数据的变更预览。
     */
    data class Preview(
        val payload: BackupPayload,
        val changes: ImportChanges,
    ) : ImportResult()

    /** 导入失败，[reason] 为可展示原因，现有配置保持不变。 */
    data class Failure(val reason: String) : ImportResult()
}

/** 变更预览（Task 5.2.3）。描述导入将造成的差异。 */
data class ImportChanges(
    /** 将新增的服务器数量（备份中存在、本地按 name 不存在的）。 */
    val newServers: Int,
    /** 将覆盖的服务器数量（按 name 匹配已存在的）。 */
    val overwrittenServers: Int,
    /** 将删除的本地服务器数量（备份中不存在、本地存在的）。 */
    val removedServers: Int,
    /** 设置是否将发生变化（含模板串）。 */
    val settingsChanged: Boolean,
)
