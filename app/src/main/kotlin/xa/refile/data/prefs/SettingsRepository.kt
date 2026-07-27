package xa.refile.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import xa.refile.core.naming.NamingOptions
import xa.refile.core.naming.Preset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
     * 为空表示用官方默认 `https://api.themoviedb.org/3/`。
     * 用户可在设置页填自建反代地址（如 Cloudflare Workers Proxy）绕过 DNS 污染，格式需以 `/3/` 结尾。
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
