package xa.refile.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbCacheRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 设置中心 ViewModel（计划 §M5 Task 5.4）。
 *
 * 作为所有子设置功能的统一入口状态持有者：
 * - [apiKey] / [apiKeyValid]：TMDB API Key 及其校验状态（非空且长度 ≥ 32 视为有效）。
 * - [language]：TMDB 请求语言偏好（如 `zh-CN`/`en-US`/`ja-JP`）。
 * - [availableLanguages]：可选语言列表（code → 显示名），供下拉选择。
 * - [presetId]：当前命名预设 ID，用于在「命名与模板」分组展示当前预设文案。
 * - [versionName]：应用版本号（取自 PackageInfo），用于「关于」分组展示。
 *
 * 导航跳转子设置页通过一次性 [events] 事件驱动，与 [BackupViewModel] 的 SAF 事件模式一致。
 *
 * 「导出调试日志」：[pickLogFile] 发出 [SettingsNavEvent.PickLogFile] 由 Composable 启动 SAF，
 * [writeDebugLog] 在回调返回 Uri 后通过 `logcat -d` 抓取当前进程日志并以 `.log` 文本写入。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val tmdbCache: TmdbCacheRepository,
) : ViewModel() {

    /** TMDB API Key；未设置返回空串。 */
    val apiKey: StateFlow<String> = settings.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "")

    /** API Key 是否有效：非空且长度 ≥ 32（TMDB v3 Key 固定 32 位）。 */
    val apiKeyValid: StateFlow<Boolean> = settings.apiKey
        .map { it.length >= TMDB_API_KEY_LENGTH }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** TMDB 请求语言偏好，默认简体中文。 */
    val language: StateFlow<String> = settings.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DEFAULT_LANGUAGE)

    /**
     * TMDB API 反代 baseUrl（自定义反代地址）。
     *
     * 空串表示用官方默认 `https://api.themoviedb.org/3/`。
     * 用户填自建反代地址（如 Cloudflare Workers Proxy）绕过 DNS 污染，格式需以 `/3/` 结尾。
     */
    val tmdbBaseUrl: StateFlow<String> = settings.tmdbBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "")

    /** 可选语言列表（语言码 → 显示名），供下拉选择。 */
    val availableLanguages: List<Pair<String, String>> = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en-US" to "English",
        "ja-JP" to "日本語",
        "ko-KR" to "한국어",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch",
        "es-ES" to "Español",
    )

    /** 当前命名预设 ID（默认 DEFAULT，无内置媒体服务器预设区分）。 */
    val presetId: StateFlow<String> = settings.presetId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DEFAULT_PRESET)

    /** 应用版本号（取自 PackageInfo；读取失败回退占位串）。 */
    val versionName: StateFlow<String> = MutableStateFlow(readVersionName()).asStateFlow()

    /** 一次性导航事件，由 Composable 收集后调用对应导航回调。 */
    private val _events = MutableSharedFlow<SettingsNavEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsNavEvent> = _events.asSharedFlow()

    /** 调试日志导出文案（成功/失败），由 Composable 弹 Snackbar。 */
    private val _logExportResult = MutableStateFlow<String?>(null)
    val logExportResult: StateFlow<String?> = _logExportResult.asStateFlow()

    /** 是否正在导出调试日志。 */
    private val _exportingLog = MutableStateFlow(false)
    val exportingLog: StateFlow<Boolean> = _exportingLog.asStateFlow()

    /** 保存 API Key 到 DataStore。 */
    fun setApiKey(value: String) {
        viewModelScope.launch { settings.setApiKey(value) }
    }

    /** 保存语言偏好到 DataStore。 */
    fun setLanguage(code: String) {
        viewModelScope.launch { settings.setLanguage(code) }
    }

    /** 保存 TMDB 反代 baseUrl 到 DataStore（空串表示用官方默认）。 */
    fun setTmdbBaseUrl(value: String) {
        viewModelScope.launch { settings.setTmdbBaseUrl(value) }
    }

    /**
     * 清空全部 TMDB 缓存（设置页"清空 TMDB 缓存"调用）。
     *
     * 同时清空持久化数据库缓存与会话级内存缓存，结果通过 [cacheCleared] 一次性事件回传文案。
     */
    fun clearTmdbCache() {
        viewModelScope.launch {
            try {
                tmdbCache.clearCache()
                _cacheCleared.value = "TMDB 缓存已清空"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _cacheCleared.value = "清空失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** 清空缓存结果文案（成功/失败），由 Composable 弹 Snackbar。 */
    private val _cacheCleared = MutableStateFlow<String?>(null)
    val cacheCleared: StateFlow<String?> = _cacheCleared.asStateFlow()

    /** 清除清空缓存结果文案。 */
    fun clearCacheClearedResult() {
        _cacheCleared.value = null
    }

    /** 触发跳转模板编辑器事件。 */
    fun openTemplateEditor() {
        viewModelScope.launch { _events.emit(SettingsNavEvent.OpenTemplateEditor) }
    }

    /** 触发跳转备份与恢复事件。 */
    fun openBackup() {
        viewModelScope.launch { _events.emit(SettingsNavEvent.OpenBackup) }
    }

    /** 请求启动 SAF CreateDocument 选择调试日志保存位置（关于分组）。 */
    fun pickLogFile() {
        viewModelScope.launch { _events.emit(SettingsNavEvent.PickLogFile) }
    }

    /** SAF 回调返回 Uri 后抓取 logcat 并写入 .log 文本。 */
    fun writeDebugLog(uri: Uri) {
        viewModelScope.launch {
            _exportingLog.value = true
            try {
                val log = withContext(Dispatchers.IO) { captureLogcat() }
                val written = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(log.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }
                _logExportResult.value = if (written) "调试日志已导出" else "无法写入所选文件"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _logExportResult.value = "导出失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _exportingLog.value = false
            }
        }
    }

    /** 清除调试日志导出文案。 */
    fun clearLogExportResult() {
        _logExportResult.value = null
    }

    /** 抓取错误级 logcat（dump 一次，不限进程）。 */
    private fun captureLogcat(): String {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "*:E", "-v", "time"))
        return try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            // 确保进程被回收：先等其退出（带超时），再 destroy 兜底，避免进程/文件描述符泄漏。
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            process.destroy()
        }
    }

    /** 从 PackageInfo 读取 versionName，失败回退占位。 */
    private fun readVersionName(): String = runCatching {
        val pm = context.packageManager
        val pkg = pm.getPackageInfo(context.packageName, 0)
        pkg.versionName ?: "unknown"
    }.getOrDefault("unknown")

    private companion object {
        const val TMDB_API_KEY_LENGTH = 32
        const val DEFAULT_LANGUAGE = "zh-CN"
        const val DEFAULT_PRESET = "DEFAULT"
    }
}

/** 设置中心一次性导航事件。 */
sealed interface SettingsNavEvent {
    /** 跳转模板编辑器。 */
    object OpenTemplateEditor : SettingsNavEvent

    /** 跳转备份与恢复。 */
    object OpenBackup : SettingsNavEvent

    /** 触发 SAF CreateDocument 选择调试日志保存位置。 */
    object PickLogFile : SettingsNavEvent
}
