package xa.refile.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.R
import xa.refile.data.db.RenameBatchEntity
import xa.refile.data.db.RenameEntryEntity
import xa.refile.ui.common.EmptyState
import xa.refile.ui.history.HistoryViewModel.EntryStatus
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.SuccessGreen
import xa.refile.ui.theme.TextDisabled
import xa.refile.ui.theme.TextSecondary
import xa.refile.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页（计划 §M5 SubTask 5.1.3）。
 *
 * 两态共用一个 Composable：
 * - 列表态（[selectedBatchId] == null）：批次卡片倒序展示，点击进入详情态。
 * - 详情态（[selectedBatchId] != null）：条目列表 + 底部「撤销整批」按钮（已撤销禁用）。
 *
 * 撤销进行中在底部按钮上方显示 LinearProgressIndicator（current/total）并禁用按钮；完成弹 Snackbar 反馈「已回滚 N/M 条」。
 * 已撤销的批次卡片置灰并显示「已撤销」标签。
 *
 * [onBack]：列表态返回上层；详情态返回列表态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val selectedBatch by viewModel.selectedBatch.collectAsStateWithLifecycle()
    val selectedEntries by viewModel.selectedEntries.collectAsStateWithLifecycle()
    val reverting by viewModel.reverting.collectAsStateWithLifecycle()
    val revertProgress by viewModel.revertProgress.collectAsStateWithLifecycle()
    val revertResult by viewModel.revertResult.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRevertBatchId by remember { mutableStateOf<Long?>(null) }

    // 撤销结果 → Snackbar
    LaunchedEffect(revertResult) {
        val r = revertResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(viewModel.revertResultMessage(r))
        viewModel.clearRevertResult()
    }

    val current = selectedBatch
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (current != null) {
                            viewModel.clearSelection()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = {
                    Text(
                        text = if (current != null) stringResource(R.string.history_batch_detail) else stringResource(R.string.common_history),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                // 撤销进度改为底部进度条展示，右上角不再放转圈。
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (current != null) {
                RevertButtons(
                    batch = current,
                    reverting = reverting,
                    progress = revertProgress,
                    onRevert = { pendingRevertBatchId = current.id },
                )
            }
        },
    ) { padding ->
        if (current == null) {
            BatchListContent(
                batches = batches,
                contentPadding = padding,
                onOpen = { id -> viewModel.selectBatch(id) },
            )
        } else {
            BatchDetailContent(
                batch = current,
                entries = selectedEntries,
                statusOf = viewModel::entryStatus,
                contentPadding = padding,
            )
        }
    }

    pendingRevertBatchId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRevertBatchId = null },
            title = { Text(stringResource(R.string.history_revert_batch)) },
            text = {
                Text(stringResource(R.string.history_revert_confirm_text))
            },
            confirmButton = {
                TextButton(
                    enabled = !reverting,
                    onClick = {
                        viewModel.revertBatch(id)
                        pendingRevertBatchId = null
                    },
                ) {
                    Text(stringResource(R.string.history_revert), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevertBatchId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ---- 列表态 ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatchListContent(
    batches: List<RenameBatchEntity>,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
) {
    if (batches.isEmpty()) {
        // Task 5.5：无历史时友好空状态。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            EmptyState(
                icon = Icons.Filled.History,
                title = stringResource(R.string.history_empty_title),
                subtitle = stringResource(R.string.history_empty_subtitle),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(batches, key = { it.id }) { batch ->
            BatchCard(
                batch = batch,
                onClick = { onOpen(batch.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** 单条批次卡片：批次名 + 服务器名 + 时间 + 成功/失败统计 + 已撤销标签。 */
@Composable
private fun BatchCard(
    batch: RenameBatchEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColors = if (batch.isReverted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = TextDisabled,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        colors = cardColors,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = batch.batchName,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (batch.isReverted) TextDisabled else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (batch.isReverted) {
                    RevertedChip()
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.history_server_name, batch.serverName),
                style = MaterialTheme.typography.bodySmall,
                color = if (batch.isReverted) TextDisabled else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTimestamp(batch.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = if (batch.isReverted) TextDisabled else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatText(stringResource(R.string.history_stat_success), batch.succeededCount, SuccessGreen, disabled = batch.isReverted)
                Spacer(Modifier.width(16.dp))
                StatText(stringResource(R.string.history_stat_failed), batch.failedCount, ErrorRed, disabled = batch.isReverted)
                Spacer(Modifier.width(16.dp))
                StatText(
                    stringResource(R.string.history_stat_total),
                    batch.totalOperations,
                    if (batch.isReverted) TextDisabled else TextSecondary,
                    disabled = batch.isReverted,
                )
            }
        }
    }
}

@Composable
private fun RevertedChip() {
    Row(
        modifier = Modifier
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = TextDisabled,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.history_reverted),
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled,
        )
    }
}

@Composable
private fun StatText(label: String, count: Int, color: Color, disabled: Boolean) {
    val c = if (disabled) TextDisabled else color
    Text(
        text = "$label $count",
        style = MaterialTheme.typography.labelLarge,
        color = c,
        fontWeight = FontWeight.SemiBold,
    )
}

// ---- 详情态 ----

@Composable
private fun BatchDetailContent(
    batch: RenameBatchEntity,
    entries: List<RenameEntryEntity>,
    statusOf: (String) -> EntryStatus,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BatchSummaryCard(batch) }
        item {
            Text(
                text = stringResource(R.string.history_entries_count, entries.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
        items(entries, key = { it.id }) { entry ->
            EntryRow(entry = entry, status = statusOf(entry.status), disabled = batch.isReverted)
            HorizontalDivider()
        }
    }
}

@Composable
private fun BatchSummaryCard(batch: RenameBatchEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = batch.batchName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (batch.isReverted) TextDisabled else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.history_server_name, batch.serverName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.history_time, formatTimestamp(batch.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (batch.isReverted && batch.revertedAt != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.history_reverted_at, formatTimestamp(batch.revertedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDisabled,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatText(stringResource(R.string.history_stat_success), batch.succeededCount, SuccessGreen, disabled = false)
                Spacer(Modifier.width(16.dp))
                StatText(stringResource(R.string.history_stat_failed), batch.failedCount, ErrorRed, disabled = false)
                Spacer(Modifier.width(16.dp))
                StatText(stringResource(R.string.history_stat_total), batch.totalOperations, TextSecondary, disabled = false)
            }
        }
    }
}

/** 单条 entry 行：状态图标 + 原路径→新路径（monospace 小字）。 */
@Composable
private fun EntryRow(entry: RenameEntryEntity, status: EntryStatus, disabled: Boolean) {
    val (icon, color) = statusIcon(status, disabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.sourcePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = if (disabled) TextDisabled else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.sourcePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (disabled) TextDisabled else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.history_arrow_target, entry.targetPath),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (disabled) TextDisabled else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (disabled) TextDisabled else ErrorRed,
                )
            }
        }
    }
}

@Composable
private fun statusIcon(status: EntryStatus, disabled: Boolean): Pair<ImageVector, Color> {
    val color = if (disabled) TextDisabled
    else when (status) {
        EntryStatus.SUCCESS -> SuccessGreen
        EntryStatus.PARTIAL -> WarningAmber
        EntryStatus.FAILED -> ErrorRed
        EntryStatus.SKIPPED -> TextSecondary
    }
    val icon = when (status) {
        EntryStatus.SUCCESS -> Icons.Filled.CheckCircle
        EntryStatus.PARTIAL -> Icons.Filled.Warning
        EntryStatus.FAILED -> Icons.Filled.RemoveCircle
        EntryStatus.SKIPPED -> Icons.Filled.SkipNext
    }
    return icon to color
}

// ---- 底部按钮 ----

@Composable
private fun RevertButtons(
    batch: RenameBatchEntity,
    reverting: Boolean,
    progress: HistoryViewModel.RevertProgress?,
    onRevert: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // 撤销中：在按钮上方展示线性进度条 + N/M 文本（替代原转圈）。
        // current=0/total=0 是撤销刚启动尚未进入首条目的瞬时态，按 0 处理避免除零。
        AnimatedVisibility(visible = reverting) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                val current = progress?.current ?: 0
                val total = progress?.total ?: 0
                val ratio = if (total > 0) current.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.history_reverting, current, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onRevert,
                enabled = !batch.isReverted && !reverting,
            ) {
                Text(if (batch.isReverted) stringResource(R.string.history_reverted) else stringResource(R.string.history_revert_batch))
            }
        }
    }
}

// ---- 工具 ----

/** 格式化时间戳为 yyyy-MM-dd HH:mm。 */
private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}
