package xa.refile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.data.backup.ImportResult

/**
 * 备份与恢复页（计划 §M5 SubTask 5.2）。
 *
 * 布局：
 * - TopAppBar「备份与恢复」+ 返回。
 * - 导出区：可选口令输入框、「包含密码」开关（需口令非空才启用）、「导出」按钮。
 * - 导入区：「选择备份文件」按钮 → 解析后显示变更预览 → 「应用导入」按钮。
 * - 进行中显示 [CircularProgressIndicator]，结果以 Snackbar 反馈。
 *
 * SAF 由 [rememberLauncherForActivityResult] 持有，ViewModel 通过一次性事件触发其启动，
 * 解耦 ViewModel 与 Activity 结果 API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val passphrase by viewModel.passphrase.collectAsStateWithLifecycle()
    val includePasswords by viewModel.includePasswords.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    var showPassphrase by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF 启动器：导出（创建文档）/ 导入（打开文档）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    // 不再使用 SharedFlow 事件驱动 SAF 选择器（避免 extraBufferCapacity 缓冲问题）。
    // Composable 直接调用 launcher.launch()。

    // 结果文案变化时弹 Snackbar
    LaunchedEffect(result) {
        result?.let {
            snackbarHostState.showSnackbar(it)
            // 清除结果，使连续相同结果时 StateFlow 仍能产生新 emission，触发再次提示
            viewModel.clearResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(stringResource(R.string.backup_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val busy = exporting || importing
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- 导出区 ----------
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.backup_export_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = viewModel::setPassphrase,
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassphrase) stringResource(R.string.backup_hide_passphrase) else stringResource(R.string.backup_show_passphrase),
                                )
                            }
                        },
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = includePasswords,
                            onCheckedChange = viewModel::toggleIncludePasswords,
                            enabled = passphrase.isNotBlank(),
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
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.backup_include_passwords),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (passphrase.isNotBlank())
                                MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (passphrase.isBlank()) {
                        Text(
                            stringResource(R.string.backup_no_passphrase_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { exportLauncher.launch("refile-backup.json") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (exporting) stringResource(R.string.backup_exporting) else stringResource(R.string.backup_export))
                    }
                }
            }

            // ---------- 导入区 ----------
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.backup_import_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.backup_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (importing) stringResource(R.string.backup_parsing) else stringResource(R.string.backup_select_file))
                    }

                    // 变更预览
                    val preview = importPreview as? ImportResult.Preview
                    if (preview != null) {
                        ImportPreviewCard(
                            changes = preview.changes,
                            onApply = { viewModel.applyImport() },
                            onCancel = { viewModel.cancelImportPreview() },
                            applyEnabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

/** 导入变更预览卡片。 */
@Composable
private fun ImportPreviewCard(
    changes: xa.refile.data.backup.ImportChanges,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    applyEnabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.backup_import_preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            PreviewLine(stringResource(R.string.backup_new_servers), changes.newServers)
            PreviewLine(stringResource(R.string.backup_overwritten_servers), changes.overwrittenServers)
            PreviewLine(stringResource(R.string.backup_removed_servers), changes.removedServers)
            PreviewLine(stringResource(R.string.common_settings), if (changes.settingsChanged) stringResource(R.string.backup_will_change) else stringResource(R.string.backup_no_change))
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = onApply,
                    enabled = applyEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.backup_apply_import)) }
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: Any) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}
