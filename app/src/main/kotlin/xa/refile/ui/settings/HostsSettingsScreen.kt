package xa.refile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.core.backup.HostEntry
import xa.refile.core.backup.HostsSpeedTest.DirectTestResult
import xa.refile.core.backup.HostsSpeedTest.IpSpeedTestResult
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.SuccessGreen
import xa.refile.ui.theme.WarningAmber

/**
 * Hosts 设置页（spec §5.3.3–5.3.5）。
 *
 * 布局（精简后，去除重复按钮）：
 * - TopAppBar「Hosts 设置」+ 返回。
 * - 直连检测卡片：测试预设 TMDB 域名能否不走 hosts 直连，判断是否需要启用 hosts。
 * - 总开关 [Switch]（启用/禁用 HostsDns）。
 * - 预设按钮行：TMDB API / TMDB Image / 默认候选，点击添加并自动 DoH 解析+测速选优。
 * - 「新增 Host」按钮 → 弹出编辑对话框（IP 留空将自动 DoH 解析+测速选优）。
 * - hostname 列表（LazyColumn）：每行 hostname + IP 列表 + 测速选优/编辑/删除按钮 + 测速结果。
 *
 * 按钮精简：原每条 entry 有 5 个按钮（测试/自动选优/解析IP/编辑/删除）+ 底部「测试所有连接」，
 * 现合并为 3 个（测速选优/编辑/删除），底部按钮移除（与每条「测速选优」重复）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsSettingsScreen(
    onBack: () -> Unit,
    viewModel: HostsSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.hostsConfig.collectAsStateWithLifecycle()
    val runtimeDisabled by viewModel.runtimeDisabled.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val directTesting by viewModel.directTesting.collectAsStateWithLifecycle()
    val directResults by viewModel.directResults.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 解析/测速/直连检测提示消息弹 Snackbar
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 编辑对话框状态
    var editingHost by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hosts 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 直连检测按钮 + 结果
            item {
                DirectTestSection(
                    testing = directTesting,
                    results = directResults,
                    onTest = viewModel::testDirect,
                )
            }

            // 总开关卡片（含运行时自动禁用提示）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用 Hosts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "开启后命中下方条目的域名将按 hosts 解析；关闭则全部走系统 DNS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = viewModel::toggleEnabled,
                            )
                        }
                        // 运行时自动禁用提示：启动检测到可直连时临时关闭 hosts
                        if (runtimeDisabled && config.enabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = WarningAmber,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "启动时检测到当前网络可直连 TMDB，已临时关闭 Hosts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarningAmber,
                                )
                            }
                        }
                    }
                }
            }

            // hostname 条目列表
            items(config.entries, key = { it.hostname.lowercase() }) { entry ->
                HostEntryCard(
                    entry = entry,
                    testing = testing,
                    results = testResults[entry.hostname],
                    onTestAndPick = { viewModel.testAndPick(entry.hostname) },
                    onEdit = { editingHost = entry.hostname },
                    onRemove = { viewModel.removeHost(entry.hostname) },
                )
            }
        }
    }

    // 编辑对话框
    editingHost?.let { hostname ->
        val existing = config.entries.firstOrNull { it.hostname.equals(hostname, ignoreCase = true) }
        if (existing != null) {
            HostEditDialog(
                initialHostname = existing.hostname,
                initialIps = existing.ips.joinToString("\n"),
                title = "编辑 Host",
                hostnameEditable = false,
                resolving = false,
                onConfirm = { _, ips ->
                    viewModel.editHost(hostname, ips)
                    editingHost = null
                },
                onDismiss = { editingHost = null },
            )
        }
    }
}

/**
 * 直连检测区：一个按钮 + 结果列表。
 *
 * 点击按钮测试预设 TMDB 域名能否不走 hosts 直连。能直连则无需启用 hosts。
 * 结果按域名逐行展示可达状态与延迟。
 */
@Composable
private fun DirectTestSection(
    testing: Boolean,
    results: Map<String, DirectTestResult>,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("检测中...")
            } else {
                Icon(Icons.Default.WifiTethering, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("直连检测")
            }
        }
        // 直连结果
        if (results.isNotEmpty()) {
            results.forEach { (hostname, result) ->
                DirectResultRow(hostname, result)
            }
        }
    }
}

/** 单个域名直连结果行：域名 + 可达状态 + 延迟。 */
@Composable
private fun DirectResultRow(hostname: String, result: DirectTestResult) {
    val color = if (result.isDirectAvailable) SuccessGreen else ErrorRed
    val statusText = if (result.isDirectAvailable) {
        "可直连 ${result.latencyMs?.let { "(${it}ms)" } ?: ""}"
    } else {
        "不可达 ${result.errorMessage?.let { "· $it" } ?: ""}"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = hostname,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 单条 hostname 卡片：标题 + IP 列表 + 测速结果 + 操作按钮。 */
@Composable
private fun HostEntryCard(
    entry: HostEntry,
    testing: Boolean,
    results: List<IpSpeedTestResult>?,
    onTestAndPick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.hostname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // IP 列表
            if (entry.ips.isEmpty()) {
                Text(
                    text = "（未配置 IP）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entry.ips.forEach { ip ->
                    Text(
                        text = "• $ip",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 测速结果
            if (!results.isNullOrEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "测速结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.forEach { result ->
                    IpResultRow(result)
                }
            }

            // 操作按钮：测速选优（合并原「测试」+「自动选优」）
            Button(
                onClick = onTestAndPick,
                enabled = !testing && entry.ips.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("测速选优中...")
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("测速选优")
                }
            }
        }
    }
}

/** 单个 IP 测速结果行：延迟（颜色编码）+ 状态码 + 错误信息。 */
@Composable
private fun IpResultRow(result: IpSpeedTestResult) {
    val latency = result.latencyMs
    val color = when {
        !result.isAvailable -> ErrorRed
        latency == null -> ErrorRed
        latency < 200L -> SuccessGreen
        latency < 1000L -> WarningAmber
        else -> ErrorRed
    }
    val latencyText = result.latencyMs?.let { "${it} ms" } ?: "—"
    val statusText = result.statusCode?.let { "HTTP $it" } ?: ""

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = result.ip,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = latencyText,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    result.errorMessage?.let { msg ->
        Text(
            text = msg,
            style = MaterialTheme.typography.labelSmall,
            color = ErrorRed,
        )
    }
}

/** 新增/编辑对话框。hostname 在新增时可编辑，编辑时只读。 */
@Composable
private fun HostEditDialog(
    initialHostname: String,
    initialIps: String,
    title: String,
    hostnameEditable: Boolean,
    resolving: Boolean,
    onConfirm: (hostname: String, ips: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hostField by remember { mutableStateOf(initialHostname) }
    var ipsField by remember { mutableStateOf(initialIps) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostField,
                    onValueChange = { hostField = it },
                    label = { Text("域名") },
                    singleLine = true,
                    enabled = hostnameEditable,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ipsField,
                    onValueChange = { ipsField = it },
                    label = { Text("IP 列表（每行一个或逗号分隔）") },
                    supportingText = {
                        Text(
                            if (hostnameEditable) {
                                "留空将自动通过 DoH 解析并测速选优；测试成功才添加"
                            } else {
                                "修改后可点「测速选优」重新选最快 IP"
                            },
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (resolving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在解析并测速选优...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !resolving,
                onClick = {
                    val host = hostField.trim()
                    if (host.isEmpty()) return@TextButton
                    val ips = ipsField
                        .split(",", "\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onConfirm(host, ips)
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
