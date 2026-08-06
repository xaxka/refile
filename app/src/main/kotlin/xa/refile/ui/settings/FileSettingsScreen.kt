package xa.refile.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.core.rename.ConflictStrategy

/**
 * 文件设置子页（从设置中心「文件」分组拆出）。
 *
 * 承载执行阶段相关配置：
 * - 冲突处理策略（[ConflictStrategy]）。
 * - 回收站总开关与回收站目录。
 * - 批量操作并发线程数。
 *
 * 复用 [SettingsViewModel] 的状态与 setter，与设置中心页共享同一 Hilt 作用域。
 *
 * @param onBack 返回设置中心。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val conflictStrategy by viewModel.conflictStrategy.collectAsStateWithLifecycle()
    val trashDir by viewModel.trashDir.collectAsStateWithLifecycle()
    val trashEnabled by viewModel.trashEnabled.collectAsStateWithLifecycle()
    val concurrencyLimit by viewModel.concurrencyLimit.collectAsStateWithLifecycle()

    var showConflictDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_file)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---------- 文件（执行阶段冲突策略与回收站） ----------
            item {
                FileSettingsSection {
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
                    // 批量操作并发线程数：控制重命名/匹配/撤销的单服务器并发请求数
                    SettingsRow(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.settings_concurrency),
                        subtitle = stringResource(R.string.settings_concurrency_value, concurrencyLimit),
                        onClick = { showConcurrencyDialog = true },
                    )
                }
            }
        }
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

    // 并发线程数选择对话框
    if (showConcurrencyDialog) {
        ConcurrencyDialog(
            current = concurrencyLimit,
            onDismiss = { showConcurrencyDialog = false },
            onSelect = {
                viewModel.setConcurrencyLimit(it)
                showConcurrencyDialog = false
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
private fun ConcurrencyDialog(
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.settings_concurrency)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_concurrency_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                (1..10).forEach { n ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(n) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = n == current,
                            onClick = { onSelect(n) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_concurrency_value, n),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 文件设置分组的本地容器：标题 + 卡片。
 *
 * 与 [SettingsScreen] 的 SettingsSection 视觉一致，但在此文件本地实现以避免跨文件复用私有组件。
 */
@Composable
private fun FileSettingsSection(content: @Composable () -> Unit) {
    Column {
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
