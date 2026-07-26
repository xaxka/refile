package xa.refile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 历史记录设置页。
 *
 * 两项保留策略：
 * - 最大记录条数（0=不限）：超过该上限时自动删除最旧的批次。
 * - 自动清理多少天前的记录（0=不清理）：应用启动与每次重命名后按此清理过期批次。
 *
 * 配置即时落盘；「立即清理」按当前策略执行一次清理。
 *
 * @param onBack 返回设置中心。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySettingsScreen(
    onBack: () -> Unit,
    viewModel: HistorySettingsViewModel = hiltViewModel(),
) {
    val maxCount by viewModel.maxCount.collectAsStateWithLifecycle()
    val autoClearDays by viewModel.autoClearDays.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 文本框以字符串承载，避免光标跳动；初始值取自持久化配置。
    var maxCountText by remember(maxCount) { mutableStateOf(maxCount.takeIf { it > 0 }?.toString() ?: "0") }
    var autoClearDaysText by remember(autoClearDays) {
        mutableStateOf(autoClearDays.takeIf { it > 0 }?.toString() ?: "0")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史记录") },
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
            // ---------- 最大记录条数 ----------
            item {
                SettingsCard(title = "最大记录条数") {
                    OutlinedTextField(
                        value = maxCountText,
                        onValueChange = { newValue ->
                            // 仅允许数字（含空串），过滤非法输入。
                            val digits = newValue.filter { it.isDigit() }
                            maxCountText = digits
                            digits.toIntOrNull()?.let { viewModel.saveMaxCount(it) }
                        },
                        label = { Text("保留条数") },
                        supportingText = { Text("0 表示不限（默认）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---------- 自动清理 ----------
            item {
                SettingsCard(title = "自动清理") {
                    OutlinedTextField(
                        value = autoClearDaysText,
                        onValueChange = { newValue ->
                            val digits = newValue.filter { it.isDigit() }
                            autoClearDaysText = digits
                            digits.toIntOrNull()?.let { viewModel.saveAutoClearDays(it) }
                        },
                        label = { Text("清理多少天前的记录") },
                        supportingText = { Text("0 表示不清理（默认）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = viewModel::cleanNow) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null)
                            Text(
                                text = "立即清理",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }

            // ---------- 说明 ----------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "保留策略在每次重命名后与应用启动时自动执行。" +
                                "撤销状态的历史记录也会被一并清理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
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
