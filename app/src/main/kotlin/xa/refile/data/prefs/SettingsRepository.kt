package xa.refile.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import xa.refile.core.backup.HostsConfig
import xa.refile.core.naming.NamingOptions
import xa.refile.core.naming.Preset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级设置仓库（计划 §M2 / Task 2.4 依赖）。
 *
 * 基于 Preferences DataStore 持久化：
 * - [apiKey]：TMDB API Key（敏感，不进日志）。
 * - [language]：TMDB 请求语言，默认 `zh-CN`。
 * - [presetId]：命名预设，默认 `DEFAULT`。
 * - [templateString]：用户自定义模板字符串（兼容旧版，单模板）。
 * - [movieTemplateString]：电影模板字符串（测试反馈 Item 9，与剧集分离）。
 * - [episodeTemplateString]：剧集模板字符串（测试反馈 Item 9）。
 * - [visualOptions]：命名可视化选项（分隔符/大小写/非法字符处理/补零位数，Task 3.3）。
 * - [hostsConfig]：自定义 Hosts 配置（开关 + 域名→IP 条目，Task 5.3.5），以 JSON 字符串持久化。
 *
 * 用 `@Inject constructor` + `@Singleton`，Hilt 直接构造，无需 @Provides。
 * DataStore 通过顶层 [Context.dataStore] 扩展按进程单例创建。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** TMDB API Key；未设置返回空串。 */
    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }

    /** TMDB 请求语言，默认简体中文。 */
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }

    /**
     * TMDB API baseUrl（自定义反代地址）。
     *
     * 为空表示用官方默认 `https://api.themoviedb.org/3/`（经 hosts 解析）。
     * 用户可在设置页填自建反代地址（如 Vercel/NAS 反代）绕过 DNS 污染，格式需以 `/3/` 结尾。
     * [xa.refile.data.repository.TmdbCacheRepository] 构造 TmdbClient 时读取本值。
     */
    val tmdbBaseUrl: Flow<String> = context.dataStore.data.map { it[KEY_TMDB_BASE_URL] ?: "" }

    /** 命名预设 ID，默认 DEFAULT。 */
    val presetId: Flow<String> = context.dataStore.data.map { it[KEY_PRESET_ID] ?: DEFAULT_PRESET }

    /** 用户自定义模板字符串（兼容旧版单模板）；空串表示尚未设置（由调用方回退到预设）。 */
    val templateString: Flow<String> = context.dataStore.data.map { it[KEY_TEMPLATE_STRING] ?: "" }

    /** 电影模板字符串（测试反馈 Item 9）；空串表示回退到 [templateString] 或预设。 */
    val movieTemplateString: Flow<String> = context.dataStore.data.map {
        it[KEY_MOVIE_TEMPLATE] ?: ""
    }

    /** 剧集模板字符串（测试反馈 Item 9）；空串表示回退到 [templateString] 或预设。 */
    val episodeTemplateString: Flow<String> = context.dataStore.data.map {
        it[KEY_EPISODE_TEMPLATE] ?: ""
    }

    /** 命名可视化选项（仅补零位数；分隔符/大小写/非法字符处理已移除，固定使用默认值）。 */
    val visualOptions: Flow<VisualOptions> = context.dataStore.data.map { prefs ->
        VisualOptions(
            padDigits = prefs[KEY_PAD_DIGITS] ?: 2,
        )
    }

    /**
     * 自定义 Hosts 配置（Task 5.3.5）。
     *
     * 以 JSON 字符串持久化；未设置或反序列化失败时返回默认 [HostsConfig]（enabled=true, 空 entries）。
     * 备份导出/导入由 Task 5.2 通用备份机制处理，此处只负责持久化与读取。
     *
     * 注意：本 Flow 返回的是用户持久化配置（[HostsConfig.enabled] 反映用户开关）。
     * 实际生效配置请用 [effectiveHostsConfig]（合并运行时临时禁用状态）。
     */
    val hostsConfig: Flow<HostsConfig> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOSTS_CONFIG]?.let { json ->
            runCatching { hostsJson.decodeFromString<HostsConfig>(json) }.getOrNull()
        } ?: HostsConfig()
    }

    /**
     * 运行时临时禁用 hosts 的内存状态（不持久化）。
     *
     * 程序启动时若检测到当前网络可直连 TMDB（无需 hosts），则置 true，[effectiveHostsConfig]
     * 会把 [HostsConfig.enabled] 强制改为 false，所有 OkHttpClient 走系统 DNS。
     * 用户在设置页手动测试/开关 hosts 时会重置为 false（恢复持久化配置）。
     */
    private val runtimeHostsDisabled = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 运行时临时禁用状态（供 UI 展示「已自动关闭」提示）。 */
    val hostsRuntimeDisabled: kotlinx.coroutines.flow.StateFlow<Boolean> = runtimeHostsDisabled

    /**
     * 实际生效的 hosts 配置：[hostsConfig] 合并 [runtimeHostsDisabled]。
     *
     * - [runtimeHostsDisabled] 为 true → 返回 enabled=false 的配置（临时走系统 DNS）。
     * - 否则 → 返回原 [hostsConfig]。
     *
     * [ServerRepository.clientFor] 与 [xa.refile.data.repository.TmdbCacheRepository] 应读本 Flow
     * 而非 [hostsConfig]，以尊重运行时自动检测的结果。
     */
    val effectiveHostsConfig: Flow<HostsConfig> = kotlinx.coroutines.flow.combine(
        hostsConfig,
        runtimeHostsDisabled,
    ) { config, disabled ->
        if (disabled) config.copy(enabled = false) else config
    }

    /** 临时禁用 hosts（程序启动自动检测到可直连时调用）。 */
    fun setRuntimeHostsDisabled(disabled: Boolean) {
        runtimeHostsDisabled.value = disabled
    }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value }
    }

    suspend fun setLanguage(value: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = value }
    }

    /** 保存 TMDB 反代 baseUrl 到 DataStore（空串表示用官方默认）。 */
    suspend fun setTmdbBaseUrl(value: String) {
        context.dataStore.edit { it[KEY_TMDB_BASE_URL] = value.trim() }
    }

    suspend fun setPresetId(value: String) {
        context.dataStore.edit { it[KEY_PRESET_ID] = value }
    }

    /** 保存模板字符串。 */
    suspend fun setTemplateString(value: String) {
        context.dataStore.edit { it[KEY_TEMPLATE_STRING] = value }
    }

    /** 保存电影模板字符串（测试反馈 Item 9）。 */
    suspend fun setMovieTemplateString(value: String) {
        context.dataStore.edit { it[KEY_MOVIE_TEMPLATE] = value }
    }

    /** 保存剧集模板字符串（测试反馈 Item 9）。 */
    suspend fun setEpisodeTemplateString(value: String) {
        context.dataStore.edit { it[KEY_EPISODE_TEMPLATE] = value }
    }

    /** 保存可视化选项（仅补零位数）。 */
    suspend fun setVisualOptions(value: VisualOptions) {
        context.dataStore.edit {
            it[KEY_PAD_DIGITS] = value.padDigits
        }
    }

    /** 保存自定义 Hosts 配置（序列化为 JSON 字符串存储）。 */
    suspend fun setHostsConfig(value: HostsConfig) {
        val json = hostsJson.encodeToString(HostsConfig.serializer(), value)
        context.dataStore.edit { it[KEY_HOSTS_CONFIG] = json }
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "zh-CN"
        val DEFAULT_PRESET = Preset.DEFAULT.name
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_TMDB_BASE_URL = stringPreferencesKey("tmdb_base_url")
        private val KEY_PRESET_ID = stringPreferencesKey("preset_id")
        private val KEY_TEMPLATE_STRING = stringPreferencesKey("template_string")
        private val KEY_MOVIE_TEMPLATE = stringPreferencesKey("movie_template")
        private val KEY_EPISODE_TEMPLATE = stringPreferencesKey("episode_template")
        private val KEY_PAD_DIGITS = intPreferencesKey("visual_pad_digits")
        private val KEY_HOSTS_CONFIG = stringPreferencesKey("hosts_config")

        /** Hosts 配置 JSON 实例（容错：未知字段忽略，便于备份文件向前兼容）。 */
        private val hostsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * 命名可视化选项（Task 3.3 模板编辑器）。
 *
 * 仅保留补零位数；分隔符/大小写/非法字符处理已按需求移除，[toNamingOptions] 固定使用默认值。
 */
data class VisualOptions(
    val padDigits: Int = 2,
) {
    /** 转为 core 层 [NamingOptions] 供模板引擎使用（其余字段用默认值）。 */
    fun toNamingOptions(): NamingOptions = NamingOptions(
        padLength = padDigits,
    )
}

/** 进程级 Preferences DataStore 单例（名称 "settings"）。 */
private val Context.dataStore by preferencesDataStore("settings")
