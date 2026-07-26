package xa.refile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.core.backup.HostEntry
import xa.refile.core.backup.HostsConfig
import xa.refile.core.backup.HostsIpResolver
import xa.refile.core.backup.HostsSpeedTest
import xa.refile.core.backup.HostPresets
import xa.refile.core.backup.HostsSpeedTest.DirectTestResult
import xa.refile.core.backup.HostsSpeedTest.IpSpeedTestResult
import xa.refile.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts 设置 ViewModel（spec §5.3.3–5.3.5）。
 *
 * 状态：
 * - [hostsConfig]：从 [SettingsRepository.hostsConfig] 派生的可观察配置（开关 + 条目）。
 * - [testing]：测速选优进行中标志。
 * - [resolving]：DoH 解析进行中标志（addHost/applyPreset 时）。
 * - [testResults]：每个 hostname 的测速结果列表。
 * - [directTesting]：直连检测进行中标志。
 * - [directResults]：直连检测结果（hostname → 是否可直连）。
 *
 * 流程优化（测试反馈：按钮重复、功能太多）：
 * - [addHost]：ips 为空时自动 DoH 解析，测速选优后有可用 IP 才保存（测试请求成功才添加）。
 * - [testAndPick]：合并原「测试」+「自动选优」——一次测速后自动选最快 IP 写回。
 * - [applyPreset]：添加预设域名并自动 DoH 解析 + 测速选优。
 * - [testDirect]：直连检测，测试预设 TMDB 域名能否不走 hosts 直连，判断是否需要启用 hosts。
 *
 * Hosts 写入全部经 [SettingsRepository.setHostsConfig] 落盘，OkHttpClient 在使用方
 * （TmdbClient 与 ServerRepository）构造时读取该 Flow 应用 HostsDns。
 */
@HiltViewModel
class HostsSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val speedTest: HostsSpeedTest,
    private val ipResolver: HostsIpResolver,
) : ViewModel() {

    /** 当前 hosts 配置（开关 + 条目，持久化的用户配置，不含运行时临时禁用）。 */
    val hostsConfig: StateFlow<HostsConfig> = settings.hostsConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HostsConfig())

    /** 运行时是否被自动检测临时禁用（启动时检测到可直连 TMDB 时为 true）。 */
    val runtimeDisabled: StateFlow<Boolean> = settings.hostsRuntimeDisabled

    /** 是否正在测速选优。 */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** 是否正在 DoH 解析 IP（addHost/applyPreset 时）。 */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /** 各 hostname 的测速结果。 */
    private val _testResults = MutableStateFlow<Map<String, List<IpSpeedTestResult>>>(emptyMap())
    val testResults: StateFlow<Map<String, List<IpSpeedTestResult>>> = _testResults.asStateFlow()

    /** 直连检测进行中。 */
    private val _directTesting = MutableStateFlow(false)
    val directTesting: StateFlow<Boolean> = _directTesting.asStateFlow()

    /** 直连检测结果（hostname → 结果）。 */
    private val _directResults = MutableStateFlow<Map<String, DirectTestResult>>(emptyMap())
    val directResults: StateFlow<Map<String, DirectTestResult>> = _directResults.asStateFlow()

    /** 解析/测速的提示消息（成功/失败），由 Composable 弹 Snackbar。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 切换总开关（§5.3.5）。用户手动操作时重置运行时临时禁用状态，恢复持久化配置生效。 */
    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setRuntimeHostsDisabled(false)
            val current = settings.hostsConfig.first()
            settings.setHostsConfig(current.copy(enabled = enabled))
        }
    }

    /** 编辑指定 hostname 的 ips 列表（直接保存，不强制测速；用户可后续点「测速选优」）。 */
    fun editHost(hostname: String, newIps: List<String>) {
        viewModelScope.launch {
            upsertAndPersist(hostname, newIps)
        }
    }

    /** 删除指定 hostname 条目并清除其测速结果。 */
    fun removeHost(hostname: String) {
        viewModelScope.launch {
            val current = settings.hostsConfig.first()
            val newEntries = current.entries
                .filterNot { it.hostname.equals(hostname, ignoreCase = true) }
            settings.setHostsConfig(current.copy(entries = newEntries))
            _testResults.update { results ->
                results.filterKeys { !it.equals(hostname, ignoreCase = true) }
            }
        }
    }

    /**
     * 测速选优（合并原「测试」+「自动选优」）。
     *
     * 测该 hostname 全部 IP，展示完整测速结果，并自动把最快可用 IP 写回 ips。
     * 全部不可用时仅展示结果，不更新 ips。
     */
    fun testAndPick(hostname: String) {
        viewModelScope.launch {
            val ips = currentIpsFor(hostname)
            if (ips.isEmpty()) return@launch
            _testing.value = true
            try {
                val results = speedTest.testAllIps(hostname, ips)
                _testResults.update { it + (hostname to results) }
                val fastest = results
                    .filter { it.isAvailable && it.latencyMs != null }
                    .minByOrNull { it.latencyMs!! }
                if (fastest != null) {
                    upsertAndPersist(hostname, listOf(fastest.ip))
                    _message.value = "已选优最快 IP：${fastest.ip}（${fastest.latencyMs}ms）"
                } else {
                    _message.value = "所有 IP 不可用，未更新"
                }
            } finally {
                _testing.value = false
            }
        }
    }

    /**
     * 直连检测：测试所有预设 TMDB 域名能否不走 hosts 直连。
     *
     * 用 [HostsSpeedTest.testDirectConnect]（系统 DNS，不应用 HostsDns）测每个预设域名：
     * - 全部可直连 → 提示「当前网络可直接访问，无需启用 Hosts」。
     * - 部分失败 → 提示哪些域名无法直连，建议启用 Hosts。
     *
     * 用户手动触发检测时重置运行时临时禁用状态（恢复持久化配置），让检测结果反映真实配置。
     */
    fun testDirect() {
        viewModelScope.launch {
            settings.setRuntimeHostsDisabled(false)
            _directTesting.value = true
            try {
                val results = mutableMapOf<String, DirectTestResult>()
                for (h in HostPresets.DEFAULT_CANDIDATES) {
                    results[h] = speedTest.testDirectConnect(h)
                }
                _directResults.value = results
                val allDirect = results.values.all { it.isDirectAvailable }
                _message.value = if (allDirect) {
                    "当前网络可直接访问所有预设域名，无需启用 Hosts"
                } else {
                    val failed = results.filterValues { !it.isDirectAvailable }.keys
                    "以下域名无法直连：${failed.joinToString()}，建议启用 Hosts"
                }
            } finally {
                _directTesting.value = false
            }
        }
    }

    /** 清除提示消息。 */
    fun clearMessage() {
        _message.value = null
    }

    /** 取当前 hostname 的 ips 列表。 */
    private suspend fun currentIpsFor(hostname: String): List<String> =
        settings.hostsConfig.first()
            .entries
            .firstOrNull { it.hostname.equals(hostname, ignoreCase = true) }
            ?.ips
            ?: emptyList()

    /** 插入或更新条目并持久化（按 hostname 忽略大小写匹配）。 */
    private suspend fun upsertAndPersist(hostname: String, ips: List<String>) {
        val current = settings.hostsConfig.first()
        val newEntries = upsertEntry(current.entries, hostname, ips)
        settings.setHostsConfig(current.copy(entries = newEntries))
    }

    /** 插入或更新条目（按 hostname 忽略大小写匹配）。 */
    private fun upsertEntry(
        entries: List<HostEntry>,
        hostname: String,
        ips: List<String>,
    ): List<HostEntry> {
        val existing = entries.firstOrNull { it.hostname.equals(hostname, ignoreCase = true) }
        val cleanedIps = ips.map { it.trim() }.filter { it.isNotEmpty() }
        return if (existing != null) {
            entries.map { if (it.hostname.equals(hostname, ignoreCase = true)) it.copy(ips = cleanedIps) else it }
        } else {
            entries + HostEntry(hostname = hostname, ips = cleanedIps)
        }
    }
}
