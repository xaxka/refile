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
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.core.rename.ConflictStrategy

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
    val conflictStrategy by viewModel.conflictStrategy.collectAsStateWithLifecycle()
    val trashDir by viewModel.trashDir.collectAsStateWithLifecycle()
    val trashEnabled by viewModel.trashEnabled.collectAsStateWithLifecycle()

    var showConflictDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }

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
                        onClick = viewModel::openTemplateEditor,
                    )
                }
            }

            // ---------- 组 3：文件（执行阶段冲突策略与回收站） ----------
            item {
                SettingsSection(title = stringResource(R.string.settings_section_file)) {
                    SettingsRow(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.settings_conflict_strategy),
                        subtitle = conflictStrategyLabel(conflictStrategy),
                        onClick = { showConflictDialog = true },
                    )
                    // 回收站总开关：关闭后回收站目录项禁用，safeDelete 不执行回收备份。
                    SwitchSettingsRow(
                        icon = Icons.Default.DeleteOutline,
                        title = stringResource(R.string.settings_enable_trash),
                        subtitle = if (trashEnabled) stringResource(R.string.settings_trash_enabled_subtitle) else stringResource(R.string.settings_trash_disabled_subtitle),
                        checked = trashEnabled,
                        onCheckedChange = viewModel::setTrashEnabled,
                    )
                    SettingsRow(
                        icon = Icons.Default.DeleteOutline,
                        title = stringResource(R.string.settings_trash_dir),
                        subtitle = when {
                            !trashEnabled -> stringResource(R.string.settings_trash_closed)
                            trashDir.isBlank() -> stringResource(R.string.settings_trash_unconfigured)
                            else -> trashDir
                        },
                        onClick = { if (trashEnabled) showTrashDialog = true },
                        enabled = trashEnabled,
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
                        onClick = viewModel::openBackup,
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
                                    onLongClick = { viewModel.pickLogFile() },
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

    // 冲突处理策略选择对话框（Task 4.1 增强）。
    if (showConflictDialog) {
        ConflictStrategyDialog(
            current = conflictStrategy,
            onDismiss = { showConflictDialog = false },
            onSelect = {
                viewModel.setConflictStrategy(it)
                showConflictDialog = false
            },
        )
    }

    // 回收站目录输入对话框（Task 4.1 增强）。
    if (showTrashDialog) {
        TrashDirDialog(
            current = trashDir,
            onDismiss = { showTrashDialog = false },
            onConfirm = {
                viewModel.setTrashDir(it)
                showTrashDialog = false
            },
        )
    }
}

/** 冲突策略 → 简短文案（用于设置行副标题）。 */
@Composable
private fun conflictStrategyLabel(strategy: ConflictStrategy): String = when (strategy) {
    ConflictStrategy.SKIP -> stringResource(R.string.settings_conflict_skip)
    ConflictStrategy.FAIL -> stringResource(R.string.settings_conflict_fail)
    ConflictStrategy.INDEX -> stringResource(R.string.settings_conflict_index)
    ConflictStrategy.OVERWRITE -> stringResource(R.string.settings_conflict_overwrite)
}

/** 冲突策略 → 详细说明（用于选择对话框）。 */
@Composable
private fun conflictStrategyDescription(strategy: ConflictStrategy): String = when (strategy) {
    ConflictStrategy.SKIP -> stringResource(R.string.settings_conflict_skip_desc)
    ConflictStrategy.FAIL -> stringResource(R.string.settings_conflict_fail_desc)
    ConflictStrategy.INDEX -> stringResource(R.string.settings_conflict_index_desc)
    ConflictStrategy.OVERWRITE -> stringResource(R.string.settings_conflict_overwrite_desc)
}

@Composable
private fun ConflictStrategyDialog(
    current: ConflictStrategy,
    onDismiss: () -> Unit,
    onSelect: (ConflictStrategy) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.settings_conflict_strategy)) },
        text = {
            Column {
                ConflictStrategy.values().forEach { strategy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(strategy) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = strategy == current,
                            onClick = { onSelect(strategy) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = conflictStrategyLabel(strategy),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = conflictStrategyDescription(strategy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun TrashDirDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.settings_trash_dir)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_trash_dir_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(".trash") },
                )
            }
        },
    )
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
private fun SwitchSettingsRow(
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
        )
    }
}
