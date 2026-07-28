package xa.refile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 设置中心页（计划 §M5 Task 5.4）。
 *
 * 作为所有子设置功能的统一入口，按分组卡片组织：
 * - 组 1 TMDB 配置：入口项，跳转 [TmdbConfigScreen]。
 * - 组 2 命名与模板：模板编辑器入口。
 * - 组 3 数据管理：备份与恢复。
 *
 * 列表项统一用 [SettingsRow]（图标 + 标题 + 副标题 + 右箭头 + 点击）。
 * 子页跳转通过 [SettingsViewModel.events] 一次性事件驱动。
 * 长按「refile」标题触发错误日志导出。
 *
 * @param onBack 返回服务器列表。
 * @param onOpenTmdbConfig 跳转 TMDB 配置子页。
 * @param onOpenTemplateEditor 跳转模板编辑器。
 * @param onOpenBackup 跳转备份与恢复。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTmdbConfig: () -> Unit,
    onOpenTemplateEditor: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val versionName by viewModel.versionName.collectAsStateWithLifecycle()
    val logExportResult by viewModel.logExportResult.collectAsStateWithLifecycle()
    val openSourceNotices by viewModel.openSourceNotices.collectAsStateWithLifecycle()

    val logLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) viewModel.writeDebugLog(uri)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsNavEvent.OpenTemplateEditor -> onOpenTemplateEditor()
                SettingsNavEvent.OpenBackup -> onOpenBackup()
                SettingsNavEvent.PickLogFile ->
                    logLauncher.launch("refile-error-${System.currentTimeMillis()}.log")
            }
        }
    }

    LaunchedEffect(logExportResult) {
        logExportResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearLogExportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---------- 组 1：TMDB 配置 ----------
            item {
                SettingsSection(title = "TMDB 配置") {
                    SettingsRow(
                        icon = Icons.Default.Key,
                        title = "TMDB 配置",
                        subtitle = "API Key 与语言偏好",
                        onClick = onOpenTmdbConfig,
                    )
                }
            }

            // ---------- 组 2：命名与模板 ----------
            item {
                SettingsSection(title = "命名与模板") {
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = "模板编辑器",
                        subtitle = "补零位数与变量插值",
                        onClick = viewModel::openTemplateEditor,
                    )
                }
            }

            // ---------- 组 3：数据管理 ----------
            item {
                SettingsSection(title = "数据管理") {
                    SettingsRow(
                        icon = Icons.Default.Backup,
                        title = "备份与恢复",
                        subtitle = "导出/导入设置与服务器",
                        onClick = viewModel::openBackup,
                    )
                }
            }

            // ---------- 组 4：关于 ----------
            item {
                SettingsSection(title = "关于") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "refile",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { viewModel.pickLogFile() },
                                ),
                            )
                            Text(
                                text = "版本 $versionName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            // TMDB API 署名（TMDB Terms of Use 强制要求）
                            Text(
                                text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 图片来自 TMDB（同样为 TMDB 服务条款要求）
                            Text(
                                text = "海报与背景图来自 TMDB，版权归原版权方所有。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            // 开源许可：点击查看随 APK 分发的 NOTICE.txt（含 dav4jvm MPL-2.0 披露）。
                            Text(
                                text = "开源许可（含 dav4jvm — MPL-2.0）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.loadOpenSourceNotices() },
                            )
                        }
                    }
                }
            }
        }
    }

    // 开源许可公告对话框：展示随 APK 分发的 NOTICE 全文（MPL-2.0 §3.3 披露要求）。
    openSourceNotices?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::clearOpenSourceNotices,
            confirmButton = {
                TextButton(onClick = viewModel::clearOpenSourceNotices) {
                    Text("关闭")
                }
            },
            title = { Text("开源许可") },
            text = {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
