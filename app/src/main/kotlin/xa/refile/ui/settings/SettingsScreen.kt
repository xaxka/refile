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
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R

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
 * @param onOpenFileSettings 跳转文件设置子页（冲突策略/回收站/并发）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTmdbConfig: () -> Unit,
    onOpenTemplateEditor: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenFileSettings: () -> Unit,
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

    // 不再使用 SharedFlow 事件驱动导航/SAF。
    // 旧实现 MutableSharedFlow(extraBufferCapacity = 1) 会在无订阅者时缓冲事件，
    // 用户点了子页项后导航离开 → LaunchedEffect 取消 → 若 emit 尚未完成，
    // 事件被缓冲 → 用户返回设置页时新 collector 收到缓冲事件 → 突然跳子页面。
    // 改为直接回调 onOpenXxx / logLauncher.launch()，从根源消除缓冲问题。

    LaunchedEffect(logExportResult) {
        logExportResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearLogExportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                SettingsSection(title = stringResource(R.string.settings_section_tmdb)) {
                    SettingsRow(
                        icon = Icons.Default.Key,
                        title = stringResource(R.string.settings_tmdb_config),
                        subtitle = stringResource(R.string.settings_tmdb_config_subtitle),
                        onClick = onOpenTmdbConfig,
                    )
                }
            }

            // ---------- 组 2：命名与模板 ----------
            item {
                SettingsSection(title = stringResource(R.string.settings_section_naming)) {
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.settings_template_editor),
                        subtitle = stringResource(R.string.settings_template_editor_subtitle),
                        onClick = onOpenTemplateEditor,
                    )
                }
            }

            // ---------- 组 3：文件（冲突策略/回收站/并发，跳转子页） ----------
            item {
                SettingsSection(title = stringResource(R.string.settings_section_file)) {
                    SettingsRow(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.settings_section_file),
                        subtitle = stringResource(R.string.settings_file_settings_subtitle),
                        onClick = onOpenFileSettings,
                    )
                }
            }

            // ---------- 组 4：数据管理 ----------
            item {
                SettingsSection(title = stringResource(R.string.settings_section_data)) {
                    SettingsRow(
                        icon = Icons.Default.Backup,
                        title = stringResource(R.string.settings_backup),
                        subtitle = stringResource(R.string.settings_backup_subtitle),
                        onClick = onOpenBackup,
                    )
                }
            }

            // ---------- 组 4：关于 ----------
            item {
                SettingsSection(title = stringResource(R.string.settings_section_about)) {
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
                                    onLongClick = {
                                        logLauncher.launch("refile-error-${System.currentTimeMillis()}.log")
                                    },
                                ),
                            )
                            Text(
                                text = stringResource(R.string.settings_version, versionName),
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
                                text = stringResource(R.string.settings_tmdb_image_notice),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            // 开源许可：点击查看随 APK 分发的 NOTICE.txt（含 dav4jvm MPL-2.0 披露）。
                            Text(
                                text = stringResource(R.string.settings_open_source_license),
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
                    Text(stringResource(R.string.common_close))
                }
            },
            title = { Text(stringResource(R.string.settings_open_source_title)) },
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
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * 带开关的设置行（图标 + 标题 + 副标题 + 右侧 Switch）。
 * 用于「启用回收站」等布尔开关项。
 */
@Composable
internal fun SwitchSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // 打开时只显示主题色：轨道为 primary（浅蓝），滑块用白色避免黑色
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedBorderColor = Color.Transparent,
                // 关闭时同样去除黑色边框/滑块，保持浅色清爽
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
