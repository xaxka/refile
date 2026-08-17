package xa.refile.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

/**
 * 添加/编辑服务器页（计划 §M1 SubTask 1.4.2）。
 *
 * 表单字段：
 * - 别名
 * - 服务器类型（webdav / openlist）
 * - 完整 URL（含 scheme/host/port/路径，如 `https://dav.example.com:8443/dav`）
 * - 用户名（必填，不支持匿名访问）
 * - 密码（PasswordVisualTransformation）
 *
 * 已移除：Base URL/端口/根路径/HTTPS 开关拆分字段（合并为完整 URL）、匿名访问、认证方式选择器（固定 auto）。
 *
 * - 「测试连接」调用 [ServerEditViewModel.testConnection]，结果以彩色文案反馈。
 * - 「保存」调用 [ServerEditViewModel.save]，成功后 [onSaved]；失败展示错误。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    serverId: Long?,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var passwordVisible by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val saveFailedMsg = stringResource(R.string.server_edit_save_failed)

    LaunchedEffect(serverId) {
        viewModel.load(serverId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isEditing) stringResource(R.string.server_edit_title_edit) else stringResource(R.string.server_edit_title_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.server_edit_alias)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                label = { Text(stringResource(R.string.server_edit_url_label)) },
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.server_edit_url_supporting))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::updateUsername,
                label = { Text(stringResource(R.string.server_edit_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::updatePassword,
                label = { Text(if (uiState.isEditing) stringResource(R.string.server_edit_password_edit) else stringResource(R.string.server_edit_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val (icon, desc) = if (passwordVisible) {
                        Icons.Default.Visibility to stringResource(R.string.server_edit_hide_password)
                    } else {
                        Icons.Default.VisibilityOff to stringResource(R.string.server_edit_show_password)
                    }
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(icon, contentDescription = desc)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // P2 修复（报告 #13）：编辑模式且有已存密码时提供「清空密码」开关。
            // 密码留空 = 保留原密码，勾选开关 = 保存时清除已存密码；输入新密码自动取消勾选。
            if (uiState.isEditing && uiState.hasStoredPassword) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = uiState.clearPassword,
                        onCheckedChange = { viewModel.toggleClearPassword() },
                        enabled = uiState.password.isEmpty(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.server_edit_clear_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.password.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                    )
                }
            }

            Text(stringResource(R.string.server_edit_type), style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "webdav" to "WebDAV",
                    "openlist" to "OpenList",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = uiState.type == value,
                        onClick = { viewModel.updateType(value) },
                        label = { Text(label) },
                    )
                }
            }

            Button(
                onClick = viewModel::testConnection,
                enabled = !uiState.isTesting && !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.server_edit_testing))
                } else {
                    Text(stringResource(R.string.server_edit_test_connection))
                }
            }

            uiState.testResult?.let { result ->
                when (result) {
                    is ServerEditViewModel.TestResultUi.Success -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            result.message,
                            color = SuccessGreen,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ServerEditViewModel.TestResultUi.Error -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            result.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            saveError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    saveError = null
                    scope.launch {
                        try {
                            val id = viewModel.save()
                            onSaved(id)
                        } catch (e: Exception) {
                            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                            saveError = e.message ?: saveFailedMsg
                        }
                    }
                },
                enabled = !uiState.isSaving && !uiState.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_saving))
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}
