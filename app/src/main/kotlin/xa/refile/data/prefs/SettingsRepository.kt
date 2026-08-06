package xa.refile.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import xa.refile.core.naming.Preset
import xa.refile.core.rename.ConflictStrategy
import xa.refile.data.crypto.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级设置仓库（计划 §M2 / Task 2.4 依赖）。
 *
 * 基于 Preferences DataStore 持久化：
 * - [apiKey]：TMDB API Key（敏感，不进日志；经 Android Keystore 加密后落盘）。
 * - [language]：TMDB 请求语言，默认 `zh-CN`。
 * - [presetId]：命名预设，默认 `DEFAULT`。
 * - [templateString]：用户自定义模板字符串（兼容旧版，单模板）。
 * - [movieTemplateString]：电影模板字符串（测试反馈 Item 9，与剧集分离）。
 * - [episodeTemplateString]：剧集模板字符串（测试反馈 Item 9）。
 *
 * 用 `@Inject constructor` + `@Singleton`，Hilt 直接构造，无需 @Provides。
 * DataStore 通过顶层 [Context.dataStore] 扩展按进程单例创建。
 *
 * API Key 加密策略：写入前用 [KeystoreCrypto] 加密为 `base64(iv||cipherText)`，
 * 读取时解密。空串表示未配置，不入库加密。第一次升级到加密版本时，会读取到旧的明文
 * Key（无法 decrypt 成功），此时把明文重新加密回写并置位 `api_key_v2` 标志，完成一次性迁移。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: KeystoreCrypto,
) {

    /**
     * TMDB API Key；未设置返回空串。
     *
     * 读取流程：
     * 1. 取 `api_key` 字段，空串直接返回（未配置）。
     * 2. 已迁移标记 `api_key_v2=true`：直接 decrypt，失败回退空串（Keystore 失效场景）。
     * 3. 未迁移（旧版明文）：尝试 decrypt；成功说明其实已是密文（中途异常），置 v2 标志；
     *    失败视为明文 Key，加密回写并置 v2 标志，完成一次性迁移。
     *
     * 迁移在 Flow 内完成——仅执行一次（v2 标志之后走分支 2）。
     */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        val stored = prefs[KEY_API_KEY] ?: ""
        if (stored.isEmpty()) return@map ""
        if (prefs[KEY_API_KEY_V2] == true) {
            runCatching { crypto.decrypt(stored) }.getOrDefault("")
        } else {
            // 兼容旧明文：尝试解密成功 → 已是密文（异常路径）；失败 → 当作明文重新加密回写。
            val plain = runCatching { crypto.decrypt(stored) }.getOrNull()
            if (plain != null) {
                migrateApiKeyDone(stored)
                plain
            } else {
                migrateApiKeyDone(crypto.encrypt(stored))
                stored
            }
        }
    }

    /** TMDB 请求语言，默认简体中文。 */
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }

    /**
     * TMDB 反代地址（Cloudflare Workers Proxy 等）。
     *
     * 为空表示直连官方 `api.themoviedb.org` / `image.tmdb.org`。
     * 用户只需填 Workers 根地址（如 `https://your-worker.workers.dev/`），API 与图片请求
     * 会自动在内部拼上官方目标地址，绕过国内 DNS 污染。
     * - API：[xa.refile.data.repository.TmdbClientProvider] 构造 TmdbClient 时拼接。
     * - 图片：[xa.refile.core.tmdb.TmdbImages.proxyUrl] 由 app 启动时同步。
     */
    val tmdbProxyUrl: Flow<String> = context.dataStore.data.map { it[KEY_TMDB_PROXY_URL] ?: "" }

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

    /**
     * 执行阶段冲突处理策略（Task 4.1 增强），默认 [ConflictStrategy.FAIL]。
     *
     * 预览后到执行期间服务器可能新增同名文件，该策略决定执行时如何处理这类冲突：
     * SKIP 跳过 / FAIL 失败记录 / INDEX 自动加序号 / OVERWRITE 覆盖。
     * 持久化为策略名（[ConflictStrategy.name]），读取时回退默认值。
     */
    val conflictStrategy: Flow<ConflictStrategy> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_CONFLICT_STRATEGY]
        name?.let { runCatching { ConflictStrategy.valueOf(it) }.getOrNull() } ?: ConflictStrategy.FAIL
    }

    /**
     * WebDAV 回收站目录（Task 4.1 增强），默认 `.trash`。
     *
     * 供 [xa.refile.core.rename.RenameExecutor.safeDelete] 使用：删除/覆盖前把文件移动到该目录
     * 而非物理删除。空串表示未配置（safeDelete 将直接返回 false）。
     *
     * 是否启用由 [trashEnabled] 独立控制：关闭后即使目录非空也不走回收站（safeDelete 视作未配置）。
     */
    val trashDir: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRASH_DIR] ?: DEFAULT_TRASH_DIR
    }

    /**
     * WebDAV 回收站总开关，默认开启。
     *
     * 关闭后 [RenameWorker] 会把生效回收站目录置空，[RenameExecutor.safeDelete] 直接返回 false
     * （即不移动到回收站，等同于不执行回收备份）。用户可在「设置 → 文件 → 启用回收站」切换。
     */
    val trashEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRASH_ENABLED] ?: true
    }

    /**
     * 批量操作并发线程数，默认 5，范围 1..10。
     *
     * 控制 RenameExecutor / HistoryRepository.revertBatch / MatchViewModel.startMatch
     * 三类批量操作的单服务器并发请求数（Semaphore 限流）。
     * 同时同步调整 OkHttp Dispatcher.maxRequestsPerHost，确保 HTTP 层不成为瓶颈。
     */
    val concurrencyLimit: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_CONCURRENCY_LIMIT] ?: DEFAULT_CONCURRENCY_LIMIT).coerceIn(1, MAX_CONCURRENCY_LIMIT)
    }

    /**
     * 保存 TMDB API Key。空串清空；非空串经 [KeystoreCrypto] 加密后写入并置 v2 标志。
     */
    suspend fun setApiKey(value: String) {
        context.dataStore.edit {
            if (value.isEmpty()) {
                it[KEY_API_KEY] = ""
                it[KEY_API_KEY_V2] = false
            } else {
                it[KEY_API_KEY] = crypto.encrypt(value)
                it[KEY_API_KEY_V2] = true
            }
        }
    }

    /**
     * 一次性迁移收尾：把（已是密文或刚加密的）API Key 与 v2 标志一并落盘。
     *
     * 在 [apiKey] Flow 首次读取到未迁移数据时同步触发；后续 v2=true 走快路径不再调用。
     */
    private suspend fun migrateApiKeyDone(encryptedValue: String) {
        context.dataStore.edit {
            it[KEY_API_KEY] = encryptedValue
            it[KEY_API_KEY_V2] = true
        }
    }

    suspend fun setLanguage(value: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = value }
    }

    /** 保存 TMDB 反代地址到 DataStore（空串表示直连官方）。 */
    suspend fun setTmdbProxyUrl(value: String) {
        context.dataStore.edit { it[KEY_TMDB_PROXY_URL] = value.trim() }
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

    /** 保存执行阶段冲突处理策略。 */
    suspend fun setConflictStrategy(value: ConflictStrategy) {
        context.dataStore.edit {
            it[KEY_CONFLICT_STRATEGY] = value.name
        }
    }

    /** 保存 WebDAV 回收站目录（去首尾斜杠；空串表示未配置）。 */
    suspend fun setTrashDir(value: String) {
        context.dataStore.edit {
            it[KEY_TRASH_DIR] = value.trim('/')
        }
    }

    /** 保存 WebDAV 回收站总开关。 */
    suspend fun setTrashEnabled(value: Boolean) {
        context.dataStore.edit {
            it[KEY_TRASH_ENABLED] = value
        }
    }

    /** 保存批量操作并发线程数（自动 clamp 到 1..10）。 */
    suspend fun setConcurrencyLimit(value: Int) {
        context.dataStore.edit {
            it[KEY_CONCURRENCY_LIMIT] = value.coerceIn(1, MAX_CONCURRENCY_LIMIT)
        }
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "zh-CN"
        val DEFAULT_PRESET = Preset.DEFAULT.name
        const val DEFAULT_TRASH_DIR = ".trash"
        const val DEFAULT_CONCURRENCY_LIMIT = 5
        const val MAX_CONCURRENCY_LIMIT = 10
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_API_KEY_V2 = booleanPreferencesKey("api_key_v2")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_TMDB_PROXY_URL = stringPreferencesKey("tmdb_proxy_url")
        private val KEY_PRESET_ID = stringPreferencesKey("preset_id")
        private val KEY_TEMPLATE_STRING = stringPreferencesKey("template_string")
        private val KEY_MOVIE_TEMPLATE = stringPreferencesKey("movie_template")
        private val KEY_EPISODE_TEMPLATE = stringPreferencesKey("episode_template")
        private val KEY_CONFLICT_STRATEGY = stringPreferencesKey("conflict_strategy")
        private val KEY_TRASH_DIR = stringPreferencesKey("trash_dir")
        private val KEY_TRASH_ENABLED = booleanPreferencesKey("trash_enabled")
        private val KEY_CONCURRENCY_LIMIT = intPreferencesKey("concurrency_limit")
    }
}

/** 进程级 Preferences DataStore 单例（名称 "settings"）。 */
private val Context.dataStore by preferencesDataStore("settings")
